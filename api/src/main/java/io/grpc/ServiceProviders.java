/*
 * Copyright 2017 The gRPC Authors
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

package io.grpc;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

final class ServiceProviders {
  private ServiceProviders() {
    // do not instantiate
  }

  /**
   * If this is not Android, returns the highest priority implementation of the class via
   * {@link ServiceLoader}.
   * If this is Android, returns an instance of the highest priority class in {@code hardcoded}.
   */
  public static <T> T load(
      Class<T> klass,
      Iterable<Class<?>> hardcoded,
      ClassLoader cl,
      PriorityAccessor<T> priorityAccessor) {
    List<T> candidates = loadAll(klass, hardcoded, cl, priorityAccessor);
    if (candidates.isEmpty()) {
      return null;
    }
    return candidates.get(0);
  }

  /**
   * If this is not Android, returns all available implementations discovered via
   * {@link ServiceLoader}.
   * If this is Android, returns all available implementations in {@code hardcoded}.
   * The list is sorted in descending priority order.
   */
  public static <T> List<T> loadAll(
      Class<T> klass,
      Iterable<Class<?>> hardcoded,
      ClassLoader cl,
      final PriorityAccessor<T> priorityAccessor) {
    Iterable<T> candidates;
    if (isAndroid(cl)) {
      candidates = getCandidatesViaHardCoded(klass, hardcoded);
    } else {
      candidates = getCandidatesViaServiceLoader(klass, cl);
    }
    List<T> list = new ArrayList<>();
    for (T current: candidates) {
      if (!priorityAccessor.isAvailable(current)) {
        continue;
      }
      list.add(current);
    }

    // Sort descending based on priority.  If priorities are equal, compare the class names to
    // get a reliable result.
    Collections.sort(list, Collections.reverseOrder(new Comparator<T>() {
      @Override
      public int compare(T f1, T f2) {
        int pd = priorityAccessor.getPriority(f1) - priorityAccessor.getPriority(f2);
        if (pd != 0) {
          return pd;
        }
        return f1.getClass().getName().compareTo(f2.getClass().getName());
      }
    }));
    return Collections.unmodifiableList(list);
  }

  /**
   * Returns true if the {@link ClassLoader} is for android.
   */
  static boolean isAndroid(ClassLoader cl) {
    try {
      // Specify a class loader instead of null because we may be running under Robolectric
      Class.forName("android.app.Application", /*initialize=*/ false, cl);
      return true;
    } catch (Exception e) {
      // If Application isn't loaded, it might as well not be Android.
      return false;
    }
  }

  /**
   * Loads service providers for the {@code klass} service using {@link ServiceLoader}.
   */
  @VisibleForTesting
  public static <T> Iterable<T> getCandidatesViaServiceLoader(Class<T> klass, ClassLoader cl) {
    Iterable<T> i = ServiceLoader.load(klass, cl);
    // Attempt to load using the context class loader and ServiceLoader.
    // This allows frameworks like http://aries.apache.org/modules/spi-fly.html to plug in.
    if (!i.iterator().hasNext()) {
      i = ServiceLoader.load(klass);
    }
    return i;
  }

  /**
   * Load providers from a hard-coded list. This avoids using getResource(), which has performance
   * problems on Android (see https://github.com/grpc/grpc-java/issues/2037).
   */
  @VisibleForTesting
  static <T> Iterable<T> getCandidatesViaHardCoded(Class<T> klass, Iterable<Class<?>> hardcoded) {
    List<T> list = new ArrayList<>();
    for (Class<?> candidate : hardcoded) {
      list.add(create(klass, candidate));
    }
    return list;
  }

  @VisibleForTesting
  static <T> T create(Class<T> klass, Class<?> rawClass) {
    try {
      return rawClass.asSubclass(klass).getConstructor().newInstance();
    } catch (Throwable t) {
      throw new ServiceConfigurationError(
          String.format("Provider %s could not be instantiated %s", rawClass.getName(), t), t);
    }
  }

  /**
   * An interface that allows us to get priority information about a provider.
   * 允许获取有关提供器优先级的接口
   */
  public interface PriorityAccessor<T> {
    /**
     * Checks this provider is available for use, taking the current environment into consideration.
     * If {@code false}, no other methods are safe to be called.
     * 检查提供器是否可用，考虑当前环境，如果为 false，没有其他的方法可以安全的调用
     */
    boolean isAvailable(T provider);

    /**
     * A priority, from 0 to 10 that this provider should be used, taking the current environment
     * into consideration. 5 should be considered the default, and then tweaked based on environment
     * detection. A priority of 0 does not imply that the provider wouldn't work; just that it
     * should be last in line.
     * 提供器的优先级，从 0 到 10，考虑当前环境，默认值是 5，然后根据环境检测调整，优先级为 0 不代表不工作，仅代表
     * 优先级最低
     */
    int getPriority(T provider);
  }
}
