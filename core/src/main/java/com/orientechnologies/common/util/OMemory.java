/*
 *
 *  *  Copyright 2016 OrientDB LTD (info(at)orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientdb.com
 */

package com.orientechnologies.common.util;

import com.orientechnologies.common.jna.ONative;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.engine.local.OEngineLocalPaginated;
import com.sun.jna.Platform;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides various utilities related to memory management and configuration.
 *
 * @author Sergey Sitnikov
 */
public class OMemory {
  /**
   * @param unlimitedCap the upper limit on reported memory, if JVM reports unlimited memory.
   *
   * @return same as {@link Runtime#maxMemory()} except that {@code unlimitedCap} limit is applied if JVM reports {@link
   * Long#MAX_VALUE unlimited memory}.
   */
  public static long getCappedRuntimeMaxMemory(long unlimitedCap) {
    final long jvmMaxMemory = Runtime.getRuntime().maxMemory();
    return jvmMaxMemory == Long.MAX_VALUE ? unlimitedCap : jvmMaxMemory;
  }

  /**
   * Calculates the total configured maximum size of all OrientDB caches.
   *
   * @return the total maximum size of all OrientDB caches in bytes.
   */
  private static long getMaxCacheMemorySize() {
    return OGlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong() * 1024 * 1024;
  }

  /**
   * Checks the OrientDB cache memory configuration and emits a warning if configuration is invalid.
   */
  public static void checkCacheMemoryConfiguration() {
    final long maxHeapSize = Runtime.getRuntime().maxMemory();
    final long maxCacheSize = getMaxCacheMemorySize();
    final ONative.MemoryLimitResult physicalMemory = ONative.instance().getMemoryLimit(false);

    if (maxHeapSize != Long.MAX_VALUE && physicalMemory != null && maxHeapSize + maxCacheSize > physicalMemory.memoryLimit)
      OLogManager.instance().warnNoDb(OMemory.class,
          "The sum of the configured JVM maximum heap size (" + maxHeapSize + " bytes) " + "and the OrientDB maximum cache size ("
              + maxCacheSize + " bytes) is larger than the available physical memory size " + "(" + physicalMemory
              + " bytes). That may cause out of memory errors, please tune the configuration up. Use the "
              + "-Xmx JVM option to lower the JVM maximum heap memory size or storage.diskCache.bufferSize OrientDB option to "
              + "lower memory requirements of the cache.");
  }

  public static void lockMemory() {
    if (OGlobalConfiguration.MEMORY_LOCK.getValueAsBoolean()) {
      if (Platform.isLinux()) {
        if (!ONative.instance().isUnlimitedMemoryLocking()) {
          OLogManager.instance().warnNoDb(OMemory.class,
              "To allow preventing OrientDB buffers from swapping you should set unlimited soft limit for current user.");
          OGlobalConfiguration.MEMORY_LOCK.setValue(false);
          return;
        }

        final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        final List<String> inputArgs = runtimeMXBean.getInputArguments();

        String xms = null;
        String xmx = null;

        for (String inputArg : inputArgs) {
          if (inputArg.matches("-Xms\\d+[gGmMkK]?")) {
            xms = inputArg;
          } else if (inputArg.matches("-Xmx\\d+[gGmMkK]?")) {
            xmx = inputArg;
          }
        }

        if (xmx == null || xms == null) {
          OLogManager.instance().infoNoDb(OMemory.class,
              "Can not prevent heap memory from swapping because initial or maximum values of heap memory usage are NOT set.");
          return;
        }

        final long xmsBytes = extractMemoryLimitInBytes(xms);
        final long xmxBytes = extractMemoryLimitInBytes(xmx);

        if (xmsBytes == xmxBytes) {
          ONative.instance().mlockall(ONative.MCL_CURRENT);
          OLogManager.instance()
              .infoNoDb(OMemory.class, "Memory currently allocated by process is locked and can not be swapped to the disk.");
        } else {
          OLogManager.instance().infoNoDb(OMemory.class,
              "Initial and maximum values of heap memory usage are NOT equal, containers of "
                  + "results of SQL executors will NOT use soft references by default");
        }
      } else {
        OLogManager.instance().infoNoDb(OEngineLocalPaginated.class,
            "Can not prevent OrientDB buffers from swapping, this feature is supported only for OS Linux");
        OGlobalConfiguration.MEMORY_LOCK.setValue(false);
      }
    }
  }

  private static long extractMemoryLimitInBytes(String limit) {
    final Pattern pattern = Pattern.compile("((-Xms)|(-Xmx))(\\d+)([gGmMkK]?)");
    final Matcher matcher = pattern.matcher(limit);
    if (!matcher.find()) {
      throw new IllegalArgumentException("Invalid value of memory limit was provided '" + limit + "'");
    }
    final String value = matcher.group(4);
    String dimension = matcher.group(5);

    long bytes = Long.parseLong(value);
    if (dimension == null || dimension.isEmpty()) {
      return bytes;
    }

    dimension = dimension.toLowerCase(Locale.ENGLISH);

    switch (dimension) {
    case "g":
      bytes = bytes * 1024 * 1024 * 1024;
      break;
    case "m":
      bytes = bytes * 1024 * 1024;
      break;
    case "k":
      bytes = bytes * 1024;
      break;
    default:
      throw new IllegalArgumentException("Invalid dimension of memory limit + '" + dimension + "'");
    }

    return bytes;
  }


  /**
   * Tries to fix some common cache/memory configuration problems: <ul> <li>Cache size is larger than direct memory size.</li>
   * <li>Memory chunk size is larger than cache size.</li> <ul/>
   */
  public static void fixCommonConfigurationProblems() {
    long diskCacheSize = OGlobalConfiguration.DISK_CACHE_SIZE.getValueAsLong();

    final int max32BitCacheSize = 512;
    if (getJavaBitWidth() == 32 && diskCacheSize > max32BitCacheSize) {
      OLogManager.instance()
          .infoNoDb(OGlobalConfiguration.class, "32 bit JVM is detected. Lowering disk cache size from %,dMB to %,dMB.",
              diskCacheSize, max32BitCacheSize);
      OGlobalConfiguration.DISK_CACHE_SIZE.setValue(max32BitCacheSize);
    }
  }

  private static int getJavaBitWidth() {
    // Figure out whether bit width of running JVM
    // Most of JREs support property "sun.arch.data.model" which is exactly what we need here
    String dataModel = System.getProperty("sun.arch.data.model", "64"); // By default assume 64bit
    int size = 64;
    try {
      size = Integer.parseInt(dataModel);
    } catch (NumberFormatException ignore) {
      // Ignore
    }
    return size;
  }

  /**
   * Parses the size specifier formatted in the JVM style, like 1024k or 4g. Following units are supported: k or K – kilobytes, m or
   * M – megabytes, g or G – gigabytes. If no unit provided, it is bytes.
   *
   * @param text the text to parse.
   *
   * @return the parsed size value.
   *
   * @throws IllegalArgumentException if size specifier is not recognized as valid.
   */
  private static long parseVmArgsSize(String text) throws IllegalArgumentException {
    if (text == null)
      throw new IllegalArgumentException("text can't be null");
    if (text.length() == 0)
      throw new IllegalArgumentException("text can't be empty");

    final char unit = text.charAt(text.length() - 1);
    if (Character.isDigit(unit))
      return Long.parseLong(text);

    final long value = Long.parseLong(text.substring(0, text.length() - 1));
    switch (Character.toLowerCase(unit)) {
    case 'g':
      return value * 1024 * 1024 * 1024;
    case 'm':
      return value * 1024 * 1024;
    case 'k':
      return value * 1024;
    }

    throw new IllegalArgumentException("text '" + text + "' is not a size specifier.");
  }

  private OMemory() {
  }
}
