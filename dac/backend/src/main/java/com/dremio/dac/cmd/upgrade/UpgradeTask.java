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
package com.dremio.dac.cmd.upgrade;

import com.dremio.common.Version;
import com.google.common.base.Function;

/**
 * Base implementation for all upgrade tasks
 */
abstract class UpgradeTask {
  static final Version VERSION_106 = new Version("1.0.6", 1, 0, 6, 0, "");
  static final Version VERSION_107 = new Version("1.0.7", 1, 0, 7, 0, "");
  static final Function<UpgradeTask, Version> TASK_MIN_VERSION = new Function<UpgradeTask, Version>() {
    @Override
    public Version apply(UpgradeTask task) {
      return task.getMinVersion();
    }
  };

  private final String name;
  private final Version minVersion; // task cannot be run if KVStore version is below min
  private final Version maxVersion; // task can be ignored if KVStore version is above max

  UpgradeTask(String name, Version minVersion, Version maxVersion) {
    this.name = name;
    this.minVersion = minVersion;
    this.maxVersion = maxVersion;
  }

  Version getMinVersion() {
    return minVersion;
  }

  Version getMaxVersion() {
    return maxVersion;
  }

  abstract void upgrade(UpgradeContext context);

  @Override
  public String toString() {
    return String.format("%s [%s, %s]", name, minVersion.getVersion(), maxVersion.getVersion());
  }
}
