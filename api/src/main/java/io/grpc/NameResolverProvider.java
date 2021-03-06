/*
 * Copyright 2016 The gRPC Authors
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

import java.util.List;

/**
 * Provider of name resolvers for name agnostic consumption.
 * 提供服务发现
 *
 * <p>Implementations can be automatically discovered by gRPC via Java's SPI mechanism. For
 * automatic discovery, the implementation must have a zero-argument constructor and include
 * a resource named {@code META-INF/services/io.grpc.NameResolverProvider} in their JAR. The
 * file's contents should be the implementation's class name. Implementations that need arguments in
 * their constructor can be manually registered by {@link NameResolverRegistry#register}.
 * 实现可以通过 Java 的 SPI 机制自动发现，如果要使用自动发现，实现必须要有无参的构造器，并且在 Jar 中包含一个
 * META-INF/services/io.grpc.NameResolverProvider 资源文件，实现需要在构造器里手动的通过 NameResolverRegistry#register
 * 注册
 *
 * <p>Implementations <em>should not</em> throw. If they do, it may interrupt class loading. If
 * exceptions may reasonably occur for implementation-specific reasons, implementations should
 * generally handle the exception gracefully and return {@code false} from {@link #isAvailable()}.
 * 实现不应该抛出异常，如果抛出异常可能会打断类加载，如果异常是因为实现原因抛出的，实现应当处理异常后通过 isAvailable()
 * 返回 false 优雅处理
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4159")
public abstract class NameResolverProvider extends NameResolver.Factory {

    /**
     * The port number used in case the target or the underlying naming system doesn't provide a
     * port number.
     * 端口号用于解析地址时没有提供端口号的情况
     *
     * @since 1.0.0
     */
    @SuppressWarnings("unused") // Avoids outside callers accidentally depending on the super class.
    @Deprecated
    public static final Attributes.Key<Integer> PARAMS_DEFAULT_PORT = NameResolver.Factory.PARAMS_DEFAULT_PORT;

    /**
     * Returns non-{@code null} ClassLoader-wide providers, in preference order.
     * 以优先顺序返回非空的类加载范围的提供器
     *
     * @since 1.0.0
     * @deprecated Has no replacement
     */
    @Deprecated
    public static List<NameResolverProvider> providers() {
        return NameResolverRegistry.getDefaultRegistry().providers();
    }

    /**
     * 返回命名解析的工厂
     *
     * @since 1.0.0
     * @deprecated Use NameResolverRegistry.getDefaultRegistry().asFactory()
     */
    @Deprecated
    public static NameResolver.Factory asFactory() {
        return NameResolverRegistry.getDefaultRegistry().asFactory();
    }

    /**
     * Whether this provider is available for use, taking the current environment into consideration.
     * If {@code false}, no other methods are safe to be called.
     * 考虑当前环境，当前提供器是否可用，如果是 false，其他的方法不能安全的调用
     *
     * @since 1.0.0
     */
    protected abstract boolean isAvailable();

    /**
     * A priority, from 0 to 10 that this provider should be used, taking the current environment into
     * consideration. 5 should be considered the default, and then tweaked based on environment
     * detection. A priority of 0 does not imply that the provider wouldn't work; just that it should
     * be last in line.
     * 优先级，考虑当前环境，从 0 到 10，5应当作为默认值，然后根据环境进行调整，优先级为 0 并不代表提供器不工作，
     * 仅仅是最后一个被使用
     *
     * @since 1.0.0
     */
    protected abstract int priority();
}
