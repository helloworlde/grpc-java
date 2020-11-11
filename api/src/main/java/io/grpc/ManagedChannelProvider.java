/*
 * Copyright 2015 The gRPC Authors
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
import io.grpc.ServiceProviders.PriorityAccessor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Provider of managed channels for transport agnostic consumption.
 * ManagedChannel 的提供者
 *
 * <p>Implementations can be automatically discovered by gRPC via Java's SPI mechanism. For
 * automatic discovery, the implementation must have a zero-argument constructor and include
 * a resource named {@code META-INF/services/io.grpc.ManagedChannelProvider} in their JAR. The
 * file's contents should be the implementation's class name.
 * 实现可以通过 Java 的 SPI 机制被 gRPC 自动发现，为了实现 SPI，实现类必须要有一个无参的构造器，并且在
 * Jar 包中包含名为 META-INF/services/io.grpc.ManagedChannelProvider 的文件，文件的内容应当是实现类
 * 的名称
 *
 * <p>Implementations <em>should not</em> throw. If they do, it may interrupt class loading. If
 * exceptions may reasonably occur for implementation-specific reasons, implementations should
 * generally handle the exception gracefully and return {@code false} from {@link #isAvailable()}.
 * 实现类不应当抛出异常，如果抛出异常可能会打断类加载，如果特定的实现可能发生合理的异常，应当优雅处理，并通过
 * isAvailable 方法返回 false
 */
@Internal
public abstract class ManagedChannelProvider {

    /**
     * 硬编码的类名，返回 ChannelProvider，根据不同的实现可能返回 OkHttp 的 或者 Netty 的
     */
    @VisibleForTesting
    static final Iterable<Class<?>> HARDCODED_CLASSES = new HardcodedClasses();

    /**
     * 加载 ManagedChannelProvider 的所有可用实现
     */
    private static final ManagedChannelProvider provider = ServiceProviders.load(
            ManagedChannelProvider.class,
            HARDCODED_CLASSES,
            ManagedChannelProvider.class.getClassLoader(),
            new PriorityAccessor<ManagedChannelProvider>() {
                @Override
                public boolean isAvailable(ManagedChannelProvider provider) {
                    return provider.isAvailable();
                }

                @Override
                public int getPriority(ManagedChannelProvider provider) {
                    return provider.priority();
                }
            });

    /**
     * Returns the ClassLoader-wide default channel.
     * 返回类加载级别的可用的提供器
     *
     * @throws ProviderNotFoundException if no provider is available
     */
    public static ManagedChannelProvider provider() {
        if (provider == null) {
            throw new ProviderNotFoundException("No functional channel service provider found. "
                    + "Try adding a dependency on the grpc-okhttp, grpc-netty, or grpc-netty-shaded "
                    + "artifact");
        }
        return provider;
    }

    /**
     * Whether this provider is available for use, taking the current environment into consideration.
     * If {@code false}, no other methods are safe to be called.
     * 提供器是否可用，考虑当前环境，如果是 false，其他的方法不能被安全的调用
     */
    protected abstract boolean isAvailable();

    /**
     * A priority, from 0 to 10 that this provider should be used, taking the current environment into
     * consideration. 5 should be considered the default, and then tweaked based on environment
     * detection. A priority of 0 does not imply that the provider wouldn't work; just that it should
     * be last in line.
     * Provider 的优先级从 0 到 10，考虑环境，5应当是默认的优先级，根据环境决定，0表示不会工作，会被放在最后
     */
    protected abstract int priority();

    /**
     * Creates a new builder with the given host and port.
     * 根据所给的名称和端口创建构建器
     */
    protected abstract ManagedChannelBuilder<?> builderForAddress(String name, int port);

    /**
     * Creates a new builder with the given target URI.
     * 根据所给的 URI 创建构建器
     */
    protected abstract ManagedChannelBuilder<?> builderForTarget(String target);

    /**
     * Thrown when no suitable {@link ManagedChannelProvider} objects can be found.
     * 当没有可用的 ManagedChannelProvider 对象时抛出
     */
    public static final class ProviderNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1;

        public ProviderNotFoundException(String msg) {
            super(msg);
        }
    }

    /**
     * 硬编码的类
     */
    private static final class HardcodedClasses implements Iterable<Class<?>> {
        @Override
        public Iterator<Class<?>> iterator() {
            List<Class<?>> list = new ArrayList<>();
            try {
                list.add(Class.forName("io.grpc.okhttp.OkHttpChannelProvider"));
            } catch (ClassNotFoundException ex) {
                // ignore
            }
            try {
                list.add(Class.forName("io.grpc.netty.NettyChannelProvider"));
            } catch (ClassNotFoundException ex) {
                // ignore
            }
            return list.iterator();
        }
    }
}
