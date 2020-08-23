/*
 * Copyright 2019 The gRPC Authors
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

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Registry of {@link NameResolverProvider}s.  The {@link #getDefaultRegistry default instance}
 * loads providers at runtime through the Java service provider mechanism.
 * <p>
 * NameResolverProvider 的提供器，getDefaultRegistry 获取的默认的提供器在运行加载提供器
 *
 * @since 1.21.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4159")
@ThreadSafe
public final class NameResolverRegistry {

    private static final Logger logger = Logger.getLogger(NameResolverRegistry.class.getName());

    /**
     * 注册器实例
     */
    private static NameResolverRegistry instance;

    /**
     * 命名解析工厂
     */
    private final NameResolver.Factory factory = new NameResolverFactory();

    /**
     * 提供器集合
     */
    @GuardedBy("this")
    private final LinkedHashSet<NameResolverProvider> allProviders = new LinkedHashSet<>();

    /**
     * Immutable, sorted version of {@code allProviders}. Is replaced instead of mutating.
     * 有序不可变的提供器，被替换而不是变异
     */
    @GuardedBy("this")
    private List<NameResolverProvider> effectiveProviders = Collections.emptyList();

    /**
     * Register a provider.
     * 注册一个新的提供器
     *
     * <p>If the provider's {@link NameResolverProvider#isAvailable isAvailable()} returns
     * {@code false}, this method will throw {@link IllegalArgumentException}.
     * 如果 NameResolverProvider#isAvailable 方法返回false，这个方法将会抛出异常
     *
     * <p>Providers will be used in priority order. In case of ties, providers are used in
     * registration order.
     * 提供器按照优先级顺序使用，如果有关系，注册器使用注册的顺序使用
     */
    public synchronized void register(NameResolverProvider provider) {
        // 添加提供器
        addProvider(provider);
        // 排序并返回不可变的集合
        refreshProviders();
    }

    /**
     * 添加提供器
     *
     * @param provider 提供器
     */
    private synchronized void addProvider(NameResolverProvider provider) {
        checkArgument(provider.isAvailable(), "isAvailable() returned false");
        allProviders.add(provider);
    }

    /**
     * Deregisters a provider.  No-op if the provider is not in the registry.
     * 取消注册提供器，如果提供器没有注册则没有影响
     *
     * @param provider the provider that was added to the register via {@link #register}.
     *                 通过 register 方法注册的提供器
     */
    public synchronized void deregister(NameResolverProvider provider) {
        // 移除提供器
        allProviders.remove(provider);
        // 重新排序并替换集合
        refreshProviders();
    }

    /**
     * 排序提供器并返回不可变的集合
     */
    private synchronized void refreshProviders() {
        List<NameResolverProvider> providers = new ArrayList<>(allProviders);
        // Sort descending based on priority.
        // sort() must be stable, as we prefer first-registered providers
        // 排序
        Collections.sort(providers, Collections.reverseOrder(new Comparator<NameResolverProvider>() {
            @Override
            public int compare(NameResolverProvider o1, NameResolverProvider o2) {
                return o1.priority() - o2.priority();
            }
        }));
        // 替换为不可变的集合
        effectiveProviders = Collections.unmodifiableList(providers);
    }

    /**
     * Returns the default registry that loads providers via the Java service loader mechanism.
     * 返回通过 Java 服务加载程序机制加载提供的注册器
     */
    public static synchronized NameResolverRegistry getDefaultRegistry() {
        // 检查是否为空
        if (instance == null) {
            List<NameResolverProvider> providerList = ServiceProviders.loadAll(NameResolverProvider.class,
                    getHardCodedClasses(),
                    NameResolverProvider.class.getClassLoader(),
                    new NameResolverPriorityAccessor());

            // 如果没有提供器，则提示异常
            if (providerList.isEmpty()) {
                logger.warning("No NameResolverProviders found via ServiceLoader, including for DNS. This "
                        + "is probably due to a broken build. If using ProGuard, check your configuration");
            }
            // 创建新的注册器实例
            instance = new NameResolverRegistry();
            // 遍历加载器，将有效的添加到注册器中
            for (NameResolverProvider provider : providerList) {
                logger.fine("Service loader found " + provider);
                if (provider.isAvailable()) {
                    instance.addProvider(provider);
                }
            }

            // 排序并赋值集合
            instance.refreshProviders();
        }
        return instance;
    }

    /**
     * Returns effective providers, in priority order.
     * 以优先级顺序返回有效的提供器
     */
    @VisibleForTesting
    synchronized List<NameResolverProvider> providers() {
        return effectiveProviders;
    }

    /**
     * 返回工厂
     *
     * @return 服务解析工厂
     */
    public NameResolver.Factory asFactory() {
        return factory;
    }

    /**
     * 返回硬编码的类集合
     *
     * @return 类集合
     */
    @VisibleForTesting
    static List<Class<?>> getHardCodedClasses() {
        // Class.forName(String) is used to remove the need for ProGuard configuration. Note that
        // ProGuard does not detect usages of Class.forName(String, boolean, ClassLoader):
        // https://sourceforge.net/p/proguard/bugs/418/
        // Class.forName 用于移除 ProGuard 配置的依赖，需要注意 ProGuard 不检测
        // Class.forName(String, boolean, ClassLoader) 的使用
        ArrayList<Class<?>> list = new ArrayList<>();
        try {
            // 将 DNS 解析提供器添加到集合中
            list.add(Class.forName("io.grpc.internal.DnsNameResolverProvider"));
        } catch (ClassNotFoundException e) {
            logger.log(Level.FINE, "Unable to find DNS NameResolver", e);
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 服务解析工厂实现
     */
    private final class NameResolverFactory extends NameResolver.Factory {
        /**
         * 根据给定的 URI 创建 NameResolver，如果 URI 不能解析则返回 null，该决定仅基于 URI 方案
         *
         * @param targetUri the target URI to be resolved, whose scheme must not be {@code null}
         *                  需要解析的 URI，schema 不能为 null
         * @param args      other information that may be useful
         *                  其他有用的信息
         * @return NameResolver
         */
        @Override
        @Nullable
        public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
            List<NameResolverProvider> providers = providers();
            // 获取所有可用的提供器，并遍历返回第一个创建的 NameResolver
            for (NameResolverProvider provider : providers) {
                NameResolver resolver = provider.newNameResolver(targetUri, args);
                if (resolver != null) {
                    return resolver;
                }
            }
            return null;
        }

        /**
         * 返回默认的 Schema
         *
         * @return schema
         */
        @Override
        public String getDefaultScheme() {
            // 获取提供器，如果为空则返回 unknown，不为空则返回获取到的第一个提供器的 Schema
            List<NameResolverProvider> providers = providers();
            if (providers.isEmpty()) {
                return "unknown";
            }
            return providers.get(0).getDefaultScheme();
        }
    }

    /**
     * 返回提供器优先级访问器
     */
    private static final class NameResolverPriorityAccessor
            implements ServiceProviders.PriorityAccessor<NameResolverProvider> {

        /**
         * 是否可用
         *
         * @param provider
         * @return
         */
        @Override
        public boolean isAvailable(NameResolverProvider provider) {
            return provider.isAvailable();
        }

        /**
         * 返回优先级
         *
         * @param provider
         * @return
         */
        @Override
        public int getPriority(NameResolverProvider provider) {
            return provider.priority();
        }
    }
}
