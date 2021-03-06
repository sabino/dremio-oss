/*
 * Copyright (C) 2017 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.parquet;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.codehaus.jackson.annotate.JsonIgnore;

import com.dremio.common.expression.SchemaPath;
import com.dremio.common.util.DremioVersionInfo;
import com.dremio.exec.store.AbstractRecordReader;
import com.dremio.exec.store.TimedRunnable;
import com.dremio.exec.store.dfs.DefaultPathFilter;
import com.dremio.exec.util.ImpersonationUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonGenerator.Feature;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.module.afterburner.AfterburnerModule;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class Metadata {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Metadata.class);

  public static final String[] OLD_METADATA_FILENAMES = {".drill.parquet_metadata.v2", ".drill.parquet_metadata"};
  public static final String METADATA_FILENAME = ".dremio.parquet_metadata";

  private final FileSystem fs;
  private final ParquetFormatConfig formatConfig;

  /**
   * Create the parquet metadata file for the directory at the given path, and for any subdirectories
   *
   * @param fs
   * @param path
   * @throws IOException
   */
  public static void createMeta(FileSystem fs, String path, ParquetFormatConfig formatConfig, Configuration fsConf) throws IOException {
    Metadata metadata = new Metadata(fs, formatConfig, fsConf);
    metadata.createMetaFilesRecursively(path);
  }

  /**
   * Get the parquet metadata for the parquet files in the given directory, including those in subdirectories
   *
   * @param fs
   * @param path
   * @return
   * @throws IOException
   */
  public static ParquetTableMetadata_v2 getParquetTableMetadata(ParquetFooterCache cache, FileSystem fs, String path, ParquetFormatConfig formatConfig, Configuration fsConf)
      throws IOException {
    Metadata metadata = new Metadata(fs, formatConfig, fsConf);
    FileStatus status = fs.getFileStatus(new Path(path));
    return metadata.getParquetTableMetadata(ImmutableList.of(status), cache);
  }

  /**
   * Get the parquet metadata for a list of parquet files
   *
   * @param fs
   * @param fileStatuses
   * @return
   * @throws IOException
   */
  public static ParquetTableMetadata_v2 getParquetTableMetadata(ParquetFooterCache cache, FileSystem fs,
      List<FileStatus> fileStatuses, ParquetFormatConfig formatConfig, Configuration fsConf) throws IOException {
    Metadata metadata = new Metadata(fs, formatConfig, fsConf);
    return metadata.getParquetTableMetadata(fileStatuses, cache);
  }

  /**
   * Get the parquet metadata for a directory by reading the metadata file
   *
   * @param fs
   * @param path The path to the metadata file, located in the directory that contains the parquet files
   * @return
   * @throws IOException
   */
  public static ParquetTableMetadataBase readBlockMeta(FileSystem fs, String path, ParquetFormatConfig formatConfig, Configuration fsConf) throws IOException {
    Metadata metadata = new Metadata(fs, formatConfig, fsConf);
    return metadata.readBlockMeta(path);
  }

  private Metadata(FileSystem fs, ParquetFormatConfig formatConfig, Configuration fsConf) {
    this.fs = ImpersonationUtil.createFileSystem(ImpersonationUtil.getProcessUserName(), fsConf);
    this.formatConfig = formatConfig;
  }

  /**
   * Create the parquet metadata file for the directory at the given path, and for any subdirectories
   *
   * @param path
   * @throws IOException
   */
  private ParquetTableMetadata_v2 createMetaFilesRecursively(final String path) throws IOException {
    List<ParquetFileMetadata_v2> metaDataList = Lists.newArrayList();
    List<String> directoryList = Lists.newArrayList();
    ConcurrentHashMap<ColumnTypeMetadata_v2.Key, ColumnTypeMetadata_v2> columnTypeInfoSet =
        new ConcurrentHashMap<>();
    Path p = new Path(path);
    FileStatus fileStatus = fs.getFileStatus(p);
    assert fileStatus.isDirectory() : "Expected directory";

    final List<FileStatus> childFiles = Lists.newArrayList();

    for (final FileStatus file : fs.listStatus(p, new DefaultPathFilter())) {
      if (file.isDirectory()) {
        ParquetTableMetadata_v2 subTableMetadata = createMetaFilesRecursively(file.getPath().toString());
        metaDataList.addAll(subTableMetadata.files);
        directoryList.addAll(subTableMetadata.directories);
        directoryList.add(file.getPath().toString());
        // Merge the schema from the child level into the current level
        //TODO: We need a merge method that merges two colums with the same name but different types
        columnTypeInfoSet.putAll(subTableMetadata.columnTypeInfo);
      } else {
        childFiles.add(file);
      }
    }
    ParquetTableMetadata_v2 parquetTableMetadata = new ParquetTableMetadata_v2(true);
    if (childFiles.size() > 0) {
      List<ParquetFileMetadata_v2> childFilesMetadata = getParquetFileMetadata_v2(null, parquetTableMetadata, childFiles);
      metaDataList.addAll(childFilesMetadata);
      // Note that we do not need to merge the columnInfo at this point. The columnInfo is already added
      // to the parquetTableMetadata.
    }

    parquetTableMetadata.directories = directoryList;
    parquetTableMetadata.files = metaDataList;
    //TODO: We need a merge method that merges two colums with the same name but different types
    if (parquetTableMetadata.columnTypeInfo == null) {
      parquetTableMetadata.columnTypeInfo = new ConcurrentHashMap<>();
    }
    parquetTableMetadata.columnTypeInfo.putAll(columnTypeInfoSet);

    for (String oldname : OLD_METADATA_FILENAMES) {
      fs.delete(new Path(p, oldname), false);
    }
    writeFile(parquetTableMetadata, new Path(p, METADATA_FILENAME));
    return parquetTableMetadata;
  }

//  /**
//   * Get the parquet metadata for the parquet files in a directory
//   *
//   * @param path the path of the directory
//   * @return
//   * @throws IOException
//   */
//  private ParquetTableMetadata_v2 getParquetTableMetadata(String path) throws IOException {
//    Path p = new Path(path);
//    FileStatus fileStatus = fs.getFileStatus(p);
//    final Stopwatch watch = Stopwatch.createStarted();
//    List<FileStatus> fileStatuses = getFileStatuses(fileStatus);
//    long statusRetrieval = watch.elapsed(TimeUnit.MILLISECONDS);
//    if(statusRetrieval > 1000){
//      logger.info("Took {} ms to get file statuses", statusRetrieval);
//    } else {
//      logger.debug("Took {} ms to get file statuses", statusRetrieval);
//    }
//
//    watch.reset();
//    watch.start();
//    ParquetTableMetadata_v2 metadata_v1 = getParquetTableMetadata(fileStatuses);
//    long mdRet = watch.elapsed(TimeUnit.MILLISECONDS);
//    if(mdRet > 1000){
//      logger.info("Took {} ms to read file metadata", mdRet);
//    } else {
//      logger.debug("Took {} ms to read file metadata", mdRet);
//    }
//    return metadata_v1;
//  }

  /**
   * Get the parquet metadata for a list of parquet files
   *
   * @param fileStatuses
   * @return
   * @throws IOException
   */
  private ParquetTableMetadata_v2 getParquetTableMetadata(List<FileStatus> fileStatuses, ParquetFooterCache cache)
      throws IOException {
    ParquetTableMetadata_v2 tableMetadata = new ParquetTableMetadata_v2();
    List<ParquetFileMetadata_v2> fileMetadataList = getParquetFileMetadata_v2(cache, tableMetadata, fileStatuses);
    tableMetadata.files = fileMetadataList;
    tableMetadata.directories = new ArrayList<>();
    return tableMetadata;
  }

  /**
   * Get a list of file metadata for a list of parquet files
   *
   * @param fileStatuses
   * @return
   * @throws IOException
   */
  private List<ParquetFileMetadata_v2> getParquetFileMetadata_v2(ParquetFooterCache cache,
      ParquetTableMetadata_v2 parquetTableMetadata_v2, List<FileStatus> fileStatuses) throws IOException {
    List<TimedRunnable<ParquetFileMetadata_v2>> gatherers = Lists.newArrayList();
    for (FileStatus file : fileStatuses) {
      gatherers.add(new MetadataGatherer(parquetTableMetadata_v2, file, cache));
    }

    List<ParquetFileMetadata_v2> metaDataList = Lists.newArrayList();
    metaDataList.addAll(TimedRunnable.run("Fetch parquet metadata", logger, gatherers, 16));
    return metaDataList;
  }

  /**
   * Recursively get a list of files
   *
   * @param fileStatus
   * @return
   * @throws IOException
   */
  private List<FileStatus> getFileStatuses(FileStatus fileStatus) throws IOException {
    List<FileStatus> statuses = Lists.newArrayList();
    if (fileStatus.isDirectory()) {
      for (FileStatus child : fs.listStatus(fileStatus.getPath(), new DefaultPathFilter())) {
        statuses.addAll(getFileStatuses(child));
      }
    } else {
      statuses.add(fileStatus);
    }
    return statuses;
  }

  /**
   * TimedRunnable that reads the footer from parquet and collects file metadata
   */
  private class MetadataGatherer extends TimedRunnable<ParquetFileMetadata_v2> {

    private FileStatus fileStatus;
    private ParquetTableMetadata_v2 parquetTableMetadata;
    private ParquetFooterCache cache;

    public MetadataGatherer(ParquetTableMetadata_v2 parquetTableMetadata, FileStatus fileStatus, ParquetFooterCache cache) {
      this.fileStatus = fileStatus;
      this.parquetTableMetadata = parquetTableMetadata;
      this.cache = cache;
    }

    @Override
    protected ParquetFileMetadata_v2 runInner() throws Exception {
      final UserGroupInformation processUGI = ImpersonationUtil.getProcessUserUGI();
      return processUGI.doAs(new PrivilegedExceptionAction<ParquetFileMetadata_v2>() {
        @Override
        public ParquetFileMetadata_v2 run() throws Exception {
          return getParquetFileMetadata_v2(cache, parquetTableMetadata, fileStatus);
        }
      });
    }

    @Override
    protected IOException convertToIOException(Exception e) {
      if (e instanceof IOException) {
        return (IOException) e;
      } else {
        return new IOException(e);
      }
    }
  }

  private OriginalType getOriginalType(Type type, String[] path, int depth) {
    if (type.isPrimitive()) {
      return type.getOriginalType();
    }
    Type t = ((GroupType) type).getType(path[depth]);
    return getOriginalType(t, path, depth + 1);
  }

  private ParquetFileMetadata_v2 getParquetFileMetadata_v2(ParquetFooterCache cache, ParquetTableMetadata_v2 parquetTableMetadata, FileStatus file) throws IOException {
    final ParquetMetadata metadata;
    if(cache != null){
      metadata = cache.getFooter(file);
    } else {
      metadata = ParquetFileReader.readFooter(fs.getConf(), file);
    }

    MessageType schema = metadata.getFileMetaData().getSchema();

    Map<SchemaPath, OriginalType> originalTypeMap = Maps.newHashMap();
    schema.getPaths();
    for (String[] path : schema.getPaths()) {
      originalTypeMap.put(SchemaPath.getCompoundPath(path), getOriginalType(schema, path, 0));
    }

    List<RowGroupMetadata_v2> rowGroupMetadataList = Lists.newArrayList();

    ArrayList<SchemaPath> ALL_COLS = new ArrayList<>();
    ALL_COLS.add(AbstractRecordReader.STAR_COLUMN);
    boolean autoCorrectCorruptDates = formatConfig.autoCorrectCorruptDates;
    ParquetReaderUtility.DateCorruptionStatus containsCorruptDates = ParquetReaderUtility.detectCorruptDates(metadata, ALL_COLS, autoCorrectCorruptDates);
    if(logger.isDebugEnabled()){
      logger.debug(containsCorruptDates.toString());
    }
    for (BlockMetaData rowGroup : metadata.getBlocks()) {
      List<ColumnMetadata_v2> columnMetadataList = Lists.newArrayList();
      long length = 0;
      for (ColumnChunkMetaData col : rowGroup.getColumns()) {
        ColumnMetadata_v2 columnMetadata;

        boolean statsAvailable = (col.getStatistics() != null && !col.getStatistics().isEmpty());

        Statistics<?> stats = col.getStatistics();
        String[] columnName = col.getPath().toArray();
        SchemaPath columnSchemaName = SchemaPath.getCompoundPath(columnName);
        ColumnTypeMetadata_v2 columnTypeMetadata =
            new ColumnTypeMetadata_v2(columnName, col.getType(), originalTypeMap.get(columnSchemaName));
        if (parquetTableMetadata.columnTypeInfo == null) {
          parquetTableMetadata.columnTypeInfo = new ConcurrentHashMap<>();
        }
        // Save the column schema info. We'll merge it into one list
        parquetTableMetadata.columnTypeInfo
            .put(new ColumnTypeMetadata_v2.Key(columnTypeMetadata.name), columnTypeMetadata);
        if (statsAvailable) {
          // Write stats only if minVal==maxVal. Also, we then store only maxVal
          Object mxValue = null;
          if (stats.genericGetMax() != null && stats.genericGetMin() != null &&
              stats.genericGetMax().equals(stats.genericGetMin())) {
            mxValue = stats.genericGetMax();
            if (containsCorruptDates == ParquetReaderUtility.DateCorruptionStatus.META_SHOWS_CORRUPTION
                && columnTypeMetadata.originalType == OriginalType.DATE) {
              mxValue = ParquetReaderUtility.autoCorrectCorruptedDate((Integer) mxValue);
            }
          }
          columnMetadata =
              new ColumnMetadata_v2(columnTypeMetadata.name, col.getType(), mxValue, stats.getNumNulls());
        } else {
          columnMetadata = new ColumnMetadata_v2(columnTypeMetadata.name, col.getType(), null, null);
        }
        columnMetadataList.add(columnMetadata);
        length += col.getTotalSize();
      }

      RowGroupMetadata_v2 rowGroupMeta =
          new RowGroupMetadata_v2(rowGroup.getStartingPos(), length, rowGroup.getRowCount(),
              getHostAffinity(cache, file, rowGroup.getStartingPos(), length), columnMetadataList);

      rowGroupMetadataList.add(rowGroupMeta);
    }
    String path = Path.getPathWithoutSchemeAndAuthority(file.getPath()).toString();

    return new ParquetFileMetadata_v2(path, file.getLen(), rowGroupMetadataList);
  }

  /**
   * Get the host affinity for a row group
   *
   * @param fileStatus the parquet file
   * @param start      the start of the row group
   * @param length     the length of the row group
   * @return
   * @throws IOException
   */
  private Map<String, Float> getHostAffinity(ParquetFooterCache cache, FileStatus fileStatus, long start, long length)
      throws IOException {
    BlockLocation[] blockLocations = cache != null ? cache.getBlockLocations(fileStatus, start, length) : fs.getFileBlockLocations(fileStatus, start, length);
    Map<String, Float> hostAffinityMap = Maps.newHashMap();
    for (BlockLocation blockLocation : blockLocations) {
      for (String host : blockLocation.getHosts()) {
        Float currentAffinity = hostAffinityMap.get(host);
        float blockStart = blockLocation.getOffset();
        float blockEnd = blockStart + blockLocation.getLength();
        float rowGroupEnd = start + length;
        Float newAffinity = (blockLocation.getLength() - (blockStart < start ? start - blockStart : 0) -
            (blockEnd > rowGroupEnd ? blockEnd - rowGroupEnd : 0)) / length;
        if (currentAffinity != null) {
          hostAffinityMap.put(host, currentAffinity + newAffinity);
        } else {
          hostAffinityMap.put(host, newAffinity);
        }
      }
    }
    return hostAffinityMap;
  }

  /**
   * Serialize parquet metadata to json and write to a file
   *
   * @param parquetTableMetadata
   * @param p
   * @throws IOException
   */
  private void writeFile(ParquetTableMetadata_v2 parquetTableMetadata, Path p) throws IOException {
    JsonFactory jsonFactory = new JsonFactory();
    jsonFactory.configure(Feature.AUTO_CLOSE_TARGET, false);
    jsonFactory.configure(JsonParser.Feature.AUTO_CLOSE_SOURCE, false);
    ObjectMapper mapper = new ObjectMapper(jsonFactory);
    SimpleModule module = new SimpleModule();
    module.addSerializer(ColumnMetadata_v2.class, new ColumnMetadata_v2.Serializer());
    mapper.registerModule(module);
    FSDataOutputStream os = fs.create(p);
    mapper.writerWithDefaultPrettyPrinter().writeValue(os, parquetTableMetadata);
    os.flush();
    os.close();
  }

  /**
   * Read the parquet metadata from a file
   *
   * @param path
   * @return
   * @throws IOException
   */
  private ParquetTableMetadataBase readBlockMeta(String path) throws IOException {
    Stopwatch timer = Stopwatch.createStarted();
    Path p = new Path(path);
    ObjectMapper mapper = new ObjectMapper();

    final SimpleModule serialModule = new SimpleModule();
    serialModule.addDeserializer(SchemaPath.class, new SchemaPath.De());
    serialModule.addKeyDeserializer(ColumnTypeMetadata_v2.Key.class, new ColumnTypeMetadata_v2.Key.DeSerializer());

    AfterburnerModule module = new AfterburnerModule();
    module.setUseOptimizedBeanDeserializer(true);

    mapper.registerModule(serialModule);
    mapper.registerModule(module);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    FSDataInputStream is = fs.open(p);

    ParquetTableMetadataBase parquetTableMetadata = mapper.readValue(is, ParquetTableMetadataBase.class);
    logger.debug("Took {} ms to read metadata from cache file", timer.elapsed(TimeUnit.MILLISECONDS));
    timer.stop();
    if (tableModified(parquetTableMetadata, p)) {
      parquetTableMetadata =
          createMetaFilesRecursively(Path.getPathWithoutSchemeAndAuthority(p.getParent()).toString());
    }
    return parquetTableMetadata;
  }

  /**
   * Check if the parquet metadata needs to be updated by comparing the modification time of the directories with
   * the modification time of the metadata file
   *
   * @param metaFilePath
   * @return
   * @throws IOException
   */
  private boolean tableModified(ParquetTableMetadataBase tableMetadata, Path metaFilePath)
      throws IOException {
    long metaFileModifyTime = fs.getFileStatus(metaFilePath).getModificationTime();
    FileStatus directoryStatus = fs.getFileStatus(metaFilePath.getParent());
    if (directoryStatus.getModificationTime() > metaFileModifyTime) {
      return true;
    }
    for (String directory : tableMetadata.getDirectories()) {
      directoryStatus = fs.getFileStatus(new Path(directory));
      if (directoryStatus.getModificationTime() > metaFileModifyTime) {
        return true;
      }
    }
    return false;
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "metadata_version")
  @JsonSubTypes({
      @JsonSubTypes.Type(value = ParquetTableMetadata_v1.class, name="v1"),
      @JsonSubTypes.Type(value = ParquetTableMetadata_v2.class, name="v2")
      })
  public static abstract class ParquetTableMetadataBase {

    @JsonIgnore public abstract List<String> getDirectories();

    @JsonIgnore public abstract List<? extends ParquetFileMetadata> getFiles();

    @JsonIgnore public abstract void assignFiles(List<? extends ParquetFileMetadata> newFiles);

    public abstract boolean hasColumnMetadata();

    @JsonIgnore public abstract PrimitiveTypeName getPrimitiveType(String[] columnName);

    @JsonIgnore public abstract OriginalType getOriginalType(String[] columnName);

    @Override
    @JsonIgnore public abstract ParquetTableMetadataBase clone();

    @JsonIgnore public abstract String getDremioVersion();

    @JsonIgnore public abstract boolean isDateCorrect();
  }

  public static abstract class ParquetFileMetadata {
    @JsonIgnore public abstract String getPath();

    @JsonIgnore public abstract Long getLength();

    @JsonIgnore public abstract List<? extends RowGroupMetadata> getRowGroups();
  }


  public static abstract class RowGroupMetadata {
    @JsonIgnore public abstract Long getStart();

    @JsonIgnore public abstract Long getLength();

    @JsonIgnore public abstract Long getRowCount();

    @JsonIgnore public abstract Map<String, Float> getHostAffinity();

    @JsonIgnore public abstract List<? extends ColumnMetadata> getColumns();
  }


  public static abstract class ColumnMetadata {
    public abstract String[] getName();

    public abstract Long getNulls();

    public abstract boolean hasSingleValue();

    public abstract Object getMaxValue();

    /**
     * Set the max value recorded in the parquet metadata statistics.
     *
     * This object would just be immutable, but due to Drill-4203 we need to correct
     * date values that had been corrupted by earlier versions of Drill.
     */
    public abstract void setMax(Object newMax);

    /**
     * Set the min value recorded in the parquet metadata statistics.
     *
     * This object would just be immutable, but due to Drill-4203 we need to correct
     * date values that had been corrupted by earlier versions of Drill.
     */
    public abstract void setMin(Object newMax);

    public abstract PrimitiveTypeName getPrimitiveType();

    public abstract OriginalType getOriginalType();
  }



  @JsonTypeName("v1")
  public static class ParquetTableMetadata_v1 extends ParquetTableMetadataBase {
    @JsonProperty List<ParquetFileMetadata_v1> files;
    @JsonProperty List<String> directories;

    public ParquetTableMetadata_v1() {
      super();
    }

    public ParquetTableMetadata_v1(List<ParquetFileMetadata_v1> files, List<String> directories) {
      this.files = files;
      this.directories = directories;
    }

    @JsonIgnore @Override public List<String> getDirectories() {
      return directories;
    }

    @JsonIgnore @Override public List<? extends ParquetFileMetadata> getFiles() {
      return files;
    }

    @JsonIgnore @Override public void assignFiles(List<? extends ParquetFileMetadata> newFiles) {
      this.files = (List<ParquetFileMetadata_v1>) newFiles;
    }

    @Override public boolean hasColumnMetadata() {
      return false;
    }

    @JsonIgnore @Override public PrimitiveTypeName getPrimitiveType(String[] columnName) {
      return null;
    }

    @JsonIgnore @Override public OriginalType getOriginalType(String[] columnName) {
      return null;
    }

    @JsonIgnore @Override public ParquetTableMetadataBase clone() {
      return new ParquetTableMetadata_v1(files, directories);
    }

    @Override
    public String getDremioVersion() {
      return null;
    }

    @JsonIgnore @Override
    public boolean isDateCorrect() {
      return false;
    }
  }


  /**
   * Struct which contains the metadata for a single parquet file
   */
  public static class ParquetFileMetadata_v1 extends ParquetFileMetadata {
    @JsonProperty
    public String path;
    @JsonProperty
    public Long length;
    @JsonProperty
    public List<RowGroupMetadata_v1> rowGroups;

    public ParquetFileMetadata_v1() {
      super();
    }

    public ParquetFileMetadata_v1(String path, Long length, List<RowGroupMetadata_v1> rowGroups) {
      this.path = path;
      this.length = length;
      this.rowGroups = rowGroups;
    }

    @Override
    public String toString() {
      return String.format("path: %s rowGroups: %s", path, rowGroups);
    }

    @JsonIgnore @Override public String getPath() {
      return path;
    }

    @JsonIgnore @Override public Long getLength() {
      return length;
    }

    @JsonIgnore @Override public List<? extends RowGroupMetadata> getRowGroups() {
      return rowGroups;
    }
  }


  /**
   * A struct that contains the metadata for a parquet row group
   */
  public static class RowGroupMetadata_v1 extends RowGroupMetadata {
    @JsonProperty
    public Long start;
    @JsonProperty
    public Long length;
    @JsonProperty
    public Long rowCount;
    @JsonProperty
    public Map<String, Float> hostAffinity;
    @JsonProperty
    public List<ColumnMetadata_v1> columns;

    public RowGroupMetadata_v1() {
      super();
    }

    public RowGroupMetadata_v1(Long start, Long length, Long rowCount, Map<String, Float> hostAffinity,
        List<ColumnMetadata_v1> columns) {
      this.start = start;
      this.length = length;
      this.rowCount = rowCount;
      this.hostAffinity = hostAffinity;
      this.columns = columns;
    }

    @Override public Long getStart() {
      return start;
    }

    @Override public Long getLength() {
      return length;
    }

    @Override public Long getRowCount() {
      return rowCount;
    }

    @Override public Map<String, Float> getHostAffinity() {
      return hostAffinity;
    }

    @Override public List<? extends ColumnMetadata> getColumns() {
      return columns;
    }
  }


  /**
   * A struct that contains the metadata for a column in a parquet file
   */
  public static class ColumnMetadata_v1 extends ColumnMetadata {
    @JsonProperty
    public SchemaPath name;
    @JsonProperty
    public PrimitiveTypeName primitiveType;
    @JsonProperty
    public OriginalType originalType;
    @JsonProperty
    public Long nulls;

    // JsonProperty for these are associated with the getters and setters
    public Object max;
    public Object min;


    public ColumnMetadata_v1() {
      super();
    }

    public ColumnMetadata_v1(SchemaPath name, PrimitiveTypeName primitiveType, OriginalType originalType,
        Object max, Object min, Long nulls) {
      this.name = name;
      this.primitiveType = primitiveType;
      this.originalType = originalType;
      this.max = max;
      this.min = min;
      this.nulls = nulls;
    }

    @JsonProperty(value = "min")
    public Object getMin() {
      if (primitiveType == PrimitiveTypeName.BINARY && min != null) {
        return new String(((Binary) min).getBytes());
      }
      return min;
    }

    @JsonProperty(value = "max")
    public Object getMax() {
      if (primitiveType == PrimitiveTypeName.BINARY && max != null) {
        return new String(((Binary) max).getBytes());
      }
      return max;
    }

    @Override public PrimitiveTypeName getPrimitiveType() {
      return primitiveType;
    }

    @Override public OriginalType getOriginalType() {
      return originalType;
    }

    /**
     * setter used during deserialization of the 'min' field of the metadata cache file.
     *
     * @param min
     */
    @Override
    @JsonProperty(value = "min")
    public void setMin(Object min) {
      this.min = min;
    }

    /**
     * setter used during deserialization of the 'max' field of the metadata cache file.
     *
     * @param max
     */
    @Override
    @JsonProperty(value = "max")
    public void setMax(Object max) {
      this.max = max;
    }

    @Override public String[] getName() {
      String[] s = new String[1];
      String nameString = name.toString();
      // Strip out the surrounding backticks.
      s[0]=nameString.substring(1, nameString.length()-1);
      return s;
    }

    @Override public Long getNulls() {
      return nulls;
    }

    @Override public boolean hasSingleValue() {
      return (max != null && min != null && max.equals(min));
    }

    @Override public Object getMaxValue() {
      return max;
    }

  }

  /**
   * Struct which contains the metadata for an entire parquet directory structure
   */
  @JsonTypeName("v2") public static class ParquetTableMetadata_v2 extends ParquetTableMetadataBase {
    /*
     ColumnTypeInfo is schema information from all the files and row groups, merged into
     one. To get this info, we pass the ParquetTableMetadata object all the way dow to the
     RowGroup and the column type is built there as it is read from the footer.
     */
    @JsonProperty public ConcurrentHashMap<ColumnTypeMetadata_v2.Key, ColumnTypeMetadata_v2> columnTypeInfo;
    @JsonProperty List<ParquetFileMetadata_v2> files;
    @JsonProperty List<String> directories;
    @JsonProperty String dremioVersion;
    @JsonProperty boolean isDateCorrect;

    /**
     * For Jackson
     * TODO: currently not true
     */
    public ParquetTableMetadata_v2() {
      super();
    }

    /**
     * When creating new Metadata
     * TODO: remove? Only used with true
     * @param isDateCorrect
     */
    public ParquetTableMetadata_v2(boolean isDateCorrect) {
      this.dremioVersion = DremioVersionInfo.getVersion();
      this.isDateCorrect = isDateCorrect;
    }

    /**
     * When creating new Metadata
     * @param parquetTable
     * @param files
     * @param directories
     */
    public ParquetTableMetadata_v2(ParquetTableMetadataBase parquetTable,
        List<ParquetFileMetadata_v2> files, List<String> directories) {
      this(files, directories, ((ParquetTableMetadata_v2) parquetTable).columnTypeInfo, DremioVersionInfo.getVersion(), true);
    }

    private ParquetTableMetadata_v2(List<ParquetFileMetadata_v2> files, List<String> directories,
        ConcurrentHashMap<ColumnTypeMetadata_v2.Key, ColumnTypeMetadata_v2> columnTypeInfo,
        String dremioVersion, boolean isDateCorrect) {
      this.files = files;
      this.directories = directories;
      this.columnTypeInfo = columnTypeInfo;
      this.dremioVersion = dremioVersion;
      this.isDateCorrect = isDateCorrect;
    }

    public ColumnTypeMetadata_v2 getColumnTypeInfo(String[] name) {
      ColumnTypeMetadata_v2 columnMetadata = columnTypeInfo.get(new ColumnTypeMetadata_v2.Key(name));
      if (columnMetadata == null) {
        throw new IllegalArgumentException("no column for " + Arrays.toString(name) + " in " + columnTypeInfo);
      }
      return columnMetadata;
    }

    @JsonIgnore @Override public List<String> getDirectories() {
      return directories;
    }

    @JsonIgnore @Override public List<? extends ParquetFileMetadata> getFiles() {
      return files;
    }

    @JsonIgnore @Override public void assignFiles(List<? extends ParquetFileMetadata> newFiles) {
      this.files = (List<ParquetFileMetadata_v2>) newFiles;
    }

    @Override public boolean hasColumnMetadata() {
      return true;
    }

    @JsonIgnore @Override public PrimitiveTypeName getPrimitiveType(String[] columnName) {
      return getColumnTypeInfo(columnName).primitiveType;
    }

    @JsonIgnore @Override public OriginalType getOriginalType(String[] columnName) {
      return getColumnTypeInfo(columnName).originalType;
    }

    @JsonIgnore @Override public ParquetTableMetadataBase clone() {
      return new ParquetTableMetadata_v2(files, directories, columnTypeInfo, dremioVersion, isDateCorrect);
    }

    @JsonIgnore @Override
    public String getDremioVersion() {
      return dremioVersion;
    }

    @JsonIgnore @Override public boolean isDateCorrect() {
      return isDateCorrect;
    }

  }


  /**
   * Struct which contains the metadata for a single parquet file
   */
  public static class ParquetFileMetadata_v2 extends ParquetFileMetadata {
    @JsonProperty public String path;
    @JsonProperty public Long length;
    @JsonProperty public List<RowGroupMetadata_v2> rowGroups;

    public ParquetFileMetadata_v2() {
      super();
    }

    public ParquetFileMetadata_v2(String path, Long length, List<RowGroupMetadata_v2> rowGroups) {
      this.path = path;
      this.length = length;
      this.rowGroups = rowGroups;
    }

    @Override public String toString() {
      return String.format("path: %s rowGroups: %s", path, rowGroups);
    }

    @JsonIgnore @Override public String getPath() {
      return path;
    }

    @JsonIgnore @Override public Long getLength() {
      return length;
    }

    @JsonIgnore @Override public List<? extends RowGroupMetadata> getRowGroups() {
      return rowGroups;
    }
  }


  /**
   * A struct that contains the metadata for a parquet row group
   */
  public static class RowGroupMetadata_v2 extends RowGroupMetadata {
    @JsonProperty public Long start;
    @JsonProperty public Long length;
    @JsonProperty public Long rowCount;
    @JsonProperty public Map<String, Float> hostAffinity;
    @JsonProperty public List<ColumnMetadata_v2> columns;

    public RowGroupMetadata_v2() {
      super();
    }

    public RowGroupMetadata_v2(Long start, Long length, Long rowCount, Map<String, Float> hostAffinity,
        List<ColumnMetadata_v2> columns) {
      this.start = start;
      this.length = length;
      this.rowCount = rowCount;
      this.hostAffinity = hostAffinity;
      this.columns = columns;
    }

    @Override public Long getStart() {
      return start;
    }

    @Override public Long getLength() {
      return length;
    }

    @Override public Long getRowCount() {
      return rowCount;
    }

    @Override public Map<String, Float> getHostAffinity() {
      return hostAffinity;
    }

    @Override public List<? extends ColumnMetadata> getColumns() {
      return columns;
    }
  }


  public static class ColumnTypeMetadata_v2 {
    @JsonProperty public String[] name;
    @JsonProperty public PrimitiveTypeName primitiveType;
    @JsonProperty public OriginalType originalType;

    // Key to find by name only
    @JsonIgnore private Key key;

    public ColumnTypeMetadata_v2() {
      super();
    }

    public ColumnTypeMetadata_v2(String[] name, PrimitiveTypeName primitiveType, OriginalType originalType) {
      this.name = name;
      this.primitiveType = primitiveType;
      this.originalType = originalType;
      this.key = new Key(name);
    }



    @Override
    public String toString() {
      return "ColumnTypeMetadata_v2 [name=" + Arrays.toString(name) + ", primitiveType=" + primitiveType
          + ", originalType=" + originalType + "]";
    }

    @JsonIgnore private Key key() {
      return this.key;
    }

    private static class Key {
      private String[] name;
      private int hashCode = 0;

      public Key(String[] name) {
        this.name = name;
      }

      @Override public int hashCode() {
        if (hashCode == 0) {
          hashCode = Arrays.hashCode(name);
        }
        return hashCode;
      }

      @Override public boolean equals(Object obj) {
        if (obj == null) {
          return false;
        }
        if (getClass() != obj.getClass()) {
          return false;
        }
        final Key other = (Key) obj;
        return Arrays.equals(this.name, other.name);
      }

      @Override public String toString() {
        String s = null;
        for (String namePart : name) {
          if (s != null) {
            s += ".";
            s += namePart;
          } else {
            s = namePart;
          }
        }
        return s;
      }

      public static class DeSerializer extends KeyDeserializer {

        public DeSerializer() {
          super();
        }

        @Override
        public Object deserializeKey(String key, com.fasterxml.jackson.databind.DeserializationContext ctxt)
            throws IOException, com.fasterxml.jackson.core.JsonProcessingException {
          return new Key(key.split("\\."));
        }
      }
    }
  }


  /**
   * A struct that contains the metadata for a column in a parquet file
   */
  public static class ColumnMetadata_v2 extends ColumnMetadata {
    // Use a string array for name instead of Schema Path to make serialization easier
    @JsonProperty public String[] name;
    @JsonProperty public Long nulls;

    public Object mxValue;

    @JsonIgnore private PrimitiveTypeName primitiveType;

    public ColumnMetadata_v2() {
      super();
    }

    public ColumnMetadata_v2(String[] name, PrimitiveTypeName primitiveType, Object mxValue, Long nulls) {
      this.name = name;
      this.mxValue = mxValue;
      this.nulls = nulls;
      this.primitiveType = primitiveType;
    }

    @Override
    @JsonProperty(value = "mxValue") public void setMax(Object mxValue) {
      this.mxValue = mxValue;
    }

    @Override public String[] getName() {
      return name;
    }

    @Override public Long getNulls() {
      return nulls;
    }

    @Override
    public boolean hasSingleValue() {
      return (mxValue != null);
    }

    @Override public Object getMaxValue() {
      return mxValue;
    }

    @Override
    public void setMin(Object newMin) {
      // noop - min value not stored in this version of the metadata
    }

    @Override public PrimitiveTypeName getPrimitiveType() {
      return primitiveType;
    }

    @Override public OriginalType getOriginalType() {
      return null;
    }

    public static class DeSerializer extends JsonDeserializer<ColumnMetadata_v2> {
      @Override public ColumnMetadata_v2 deserialize(JsonParser jp, DeserializationContext ctxt)
          throws IOException, JsonProcessingException {
        return null;
      }
    }


    // We use a custom serializer and write only non null values.
    public static class Serializer extends JsonSerializer<ColumnMetadata_v2> {
      @Override
      public void serialize(ColumnMetadata_v2 value, JsonGenerator jgen, SerializerProvider provider)
          throws IOException, JsonProcessingException {
        jgen.writeStartObject();
        jgen.writeArrayFieldStart("name");
        for (String n : value.name) {
          jgen.writeString(n);
        }
        jgen.writeEndArray();
        if (value.mxValue != null) {
          Object val;
          if (value.primitiveType == PrimitiveTypeName.BINARY && value.mxValue != null) {
            val = new String(((Binary) value.mxValue).getBytes());
          } else {
            val = value.mxValue;
          }
          jgen.writeObjectField("mxValue", val);
        }
        if (value.nulls != null) {
          jgen.writeObjectField("nulls", value.nulls);
        }
        jgen.writeEndObject();
      }
    }

  }

}

