/*
 * Copyright 2018 The gRPC Authors
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
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Registry of {@link LoadBalancerProvider}s.  The {@link #getDefaultRegistry default instance}
 * loads providers at runtime through the Java service provider mechanism.
 * <p>
 * LoadBalancerProvider 注册器，getDefaultRegistry 通过Java服务加载机制加载默认的实例
 *
 * @since 1.17.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
@ThreadSafe
public final class LoadBalancerRegistry {

    private static final Logger logger = Logger.getLogger(LoadBalancerRegistry.class.getName());

    private static LoadBalancerRegistry instance;

    private static final Iterable<Class<?>> HARDCODED_CLASSES = getHardCodedClasses();

    private final LinkedHashSet<LoadBalancerProvider> allProviders = new LinkedHashSet<>();

    private final LinkedHashMap<String, LoadBalancerProvider> effectiveProviders = new LinkedHashMap<>();

    /**
     * Register a provider.
     * 注册一个提供器
     *
     * <p>If the provider's {@link LoadBalancerProvider#isAvailable isAvailable()} returns
     * {@code false}, this method will throw {@link IllegalArgumentException}.
     * 如果 LoadBalancerProvider#isAvailable 返回 false，方法会抛出异常
     *
     * <p>If more than one provider with the same {@link LoadBalancerProvider#getPolicyName policy
     * name} are registered, the one with the highest {@link LoadBalancerProvider#getPriority
     * priority} will be effective.  If there are more than one name-sake providers rank the highest
     * priority, the one registered first will be effective.
     * <p>
     * 如果名称相同的策略有多个提供器注册，最高优先级的生效，如果优先级也相同，那么先注册的会生效
     */
    public synchronized void register(LoadBalancerProvider provider) {
        addProvider(provider);
        refreshProviderMap();
    }

    /**
     * 添加负载均衡提供器
     *
     * @param provider 提供器
     */
    private synchronized void addProvider(LoadBalancerProvider provider) {
        checkArgument(provider.isAvailable(), "isAvailable() returned false");
        allProviders.add(provider);
    }

    /**
     * Deregisters a provider.  No-op if the provider is not in the registry.  If there are more
     * than one providers with the same policy name as the deregistered one in the registry, one
     * of them will become the effective provider for that policy, per the rule documented in {@link
     * #register}.
     * <p>
     * 注销提供器，如果提供器没有注册则不执行操作，如果有多个策略名称相同的提供器注销，其中的一个会成为有效的提供器，
     * 与注册策略相同
     *
     * @param provider the provider that was added to the register via {@link #register}.
     *                 在 register 方法中注册的提供器
     */
    public synchronized void deregister(LoadBalancerProvider provider) {
        allProviders.remove(provider);
        refreshProviderMap();
    }

    /**
     * 刷新提供器集合
     */
    private synchronized void refreshProviderMap() {
        // 清除原有的
        effectiveProviders.clear();

        // 遍历添加所有有效的提供器
        for (LoadBalancerProvider provider : allProviders) {
            String policy = provider.getPolicyName();
            LoadBalancerProvider existing = effectiveProviders.get(policy);
            // 如果不存在，或者已经存在但是优先级低于新的，则添加到集合中
            if (existing == null || existing.getPriority() < provider.getPriority()) {
                effectiveProviders.put(policy, provider);
            }
        }
    }

    /**
     * Returns the default registry that loads providers via the Java service loader mechanism.
     * 通过 Java 服务加载机制获取默认的提供器
     */
    public static synchronized LoadBalancerRegistry getDefaultRegistry() {
        if (instance == null) {
            List<LoadBalancerProvider> providerList = ServiceProviders.loadAll(LoadBalancerProvider.class,
                    HARDCODED_CLASSES,
                    LoadBalancerProvider.class.getClassLoader(),
                    new LoadBalancerPriorityAccessor());

            instance = new LoadBalancerRegistry();

            // 遍历所有的提供器，将所有可用的提供器都添加到集合中
            for (LoadBalancerProvider provider : providerList) {
                logger.fine("Service loader found " + provider);
                if (provider.isAvailable()) {
                    instance.addProvider(provider);
                }
            }
            // 刷新集合
            instance.refreshProviderMap();
        }
        return instance;
    }

    /**
     * Returns the effective provider for the given load-balancing policy, or {@code null} if no
     * suitable provider can be found.  Each provider declares its policy name via {@link
     * LoadBalancerProvider#getPolicyName}.
     * <p>
     * 根据给定的策略名称返回可用的提供器，如果没有则返回 null，每个提供器通过 LoadBalancerProvider#getPolicyName 获取名称
     */
    @Nullable
    public synchronized LoadBalancerProvider getProvider(String policy) {
        return effectiveProviders.get(checkNotNull(policy, "policy"));
    }

    /**
     * Returns effective providers in a new map.
     * 返回有效的提供器集合
     */
    @VisibleForTesting
    synchronized Map<String, LoadBalancerProvider> providers() {
        return new LinkedHashMap<>(effectiveProviders);
    }

    @VisibleForTesting
    static List<Class<?>> getHardCodedClasses() {
        // Class.forName(String) is used to remove the need for ProGuard configuration. Note that
        // ProGuard does not detect usages of Class.forName(String, boolean, ClassLoader):
        // https://sourceforge.net/p/proguard/bugs/418/
        // Class.forName() 用于消除对ProGuard配置的需要，需要注意 ProGuard 不会检测 Class.forName 的使用
        ArrayList<Class<?>> list = new ArrayList<>();

        // 先加载 pick_first 策略的提供器
        try {
            list.add(Class.forName("io.grpc.internal.PickFirstLoadBalancerProvider"));
        } catch (ClassNotFoundException e) {
            logger.log(Level.WARNING, "Unable to find pick-first LoadBalancer", e);
        }

        // 再加载 round_robin 策略的提供器
        try {
            list.add(Class.forName("io.grpc.util.SecretRoundRobinLoadBalancerProvider$Provider"));
        } catch (ClassNotFoundException e) {
            // Since hard-coded list is only used in Android environment, and we don't expect round-robin
            // to be actually used there, we log it as a lower level.
            logger.log(Level.FINE, "Unable to find round-robin LoadBalancer", e);
        }
        return Collections.unmodifiableList(list);
    }

    private static final class LoadBalancerPriorityAccessor
            implements ServiceProviders.PriorityAccessor<LoadBalancerProvider> {

        LoadBalancerPriorityAccessor() {
        }

        @Override
        public boolean isAvailable(LoadBalancerProvider provider) {
            return provider.isAvailable();
        }

        @Override
        public int getPriority(LoadBalancerProvider provider) {
            return provider.getPriority();
        }
    }
}
