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

import com.google.common.base.Preconditions;

import javax.annotation.Nullable;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * A builder for {@link Server} instances.
 * Server 实例构建器
 *
 * @param <T> The concrete type of this builder.
 *            builder 的具体类型
 * @since 1.0.0
 */
public abstract class ServerBuilder<T extends ServerBuilder<T>> {

    /**
     * Static factory for creating a new ServerBuilder.
     * 创建 ServerBuilder 的静态工厂
     *
     * @param port the port to listen on
     *             监听的端口
     * @since 1.0.0
     */
    public static ServerBuilder<?> forPort(int port) {
        return ServerProvider.provider().builderForPort(port);
    }

    /**
     * Execute application code directly in the transport thread.
     * 在 Transport 线程内直接执行应用代码
     *
     * <p>Depending on the underlying transport, using a direct executor may lead to substantial
     * performance improvements. However, it also requires the application to not block under
     * any circumstances.
     * 取决于底层的 Transport，使用直接线程池可能会大幅提升性能，然而，这要求应用程序不能有任何的阻塞
     *
     * <p>Calling this method is semantically equivalent to calling {@link #executor(Executor)} and
     * passing in a direct executor. However, this is the preferred way as it may allow the transport
     * to perform special optimizations.
     * 调用这个方法语义上和调用 executor(Executor) 并传递一个直接的线程池是一样的，但是这个是首选的方法，因为 Transport
     * 可能会做一些特殊的优化
     *
     * @return this
     * @since 1.0.0
     */
    public abstract T directExecutor();

    /**
     * Provides a custom executor.
     * 提供一个自定义的线程池
     * <p>It's an optional parameter. If the user has not provided an executor when the server is
     * built, the builder will use a static cached thread pool.
     * 是一个可选的参数，如果用户在构建 server 的时候没有提供，则会使用默认的静态缓存的线程池
     *
     * <p>The server won't take ownership of the given executor. It's caller's responsibility to
     * shut down the executor when it's desired.
     * server 不会持有提供的线程池，由调用者确保在需要时关闭
     *
     * @return this
     * @since 1.0.0
     */
    public abstract T executor(@Nullable Executor executor);

    /**
     * Adds a service implementation to the handler registry.
     * 为处理注册器添加一个服务实现
     *
     * @param service ServerServiceDefinition object
     *                服务实现的定义对象
     * @return this
     * @since 1.0.0
     */
    public abstract T addService(ServerServiceDefinition service);

    /**
     * Adds a service implementation to the handler registry.
     * 为处理注册器添加一个服务实现
     *
     * @param bindableService BindableService object
     * @return this
     * @since 1.0.0
     */
    public abstract T addService(BindableService bindableService);

    /**
     * Adds a {@link ServerInterceptor} that is run for all services on the server.  Interceptors
     * added through this method always run before per-service interceptors added through {@link
     * ServerInterceptors}.  Interceptors run in the reverse order in which they are added, just as
     * with consecutive calls to {@code ServerInterceptors.intercept()}.
     * <p>
     * 添加作用于 Server 所有的方法的服务端拦截器，通过这个方法添加的拦截器总是在 ServerInterceptors 添加的每个服务的拦截器之前
     * 执行，拦截器以添加的倒序执行，就像连续调用 ServerInterceptors.intercept()
     *
     * @param interceptor the all-service interceptor
     *                    所有的服务的拦截器
     * @return this
     * @since 1.5.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3117")
    public T intercept(ServerInterceptor interceptor) {
        throw new UnsupportedOperationException();
    }

    /**
     * Adds a {@link ServerTransportFilter}. The order of filters being added is the order they will
     * be executed.
     * 添加 ServerTransportFilter，过滤器添加的顺序就是执行的顺序
     *
     * @return this
     * @since 1.2.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2132")
    public T addTransportFilter(ServerTransportFilter filter) {
        throw new UnsupportedOperationException();
    }

    /**
     * Adds a {@link ServerStreamTracer.Factory} to measure server-side traffic.  The order of
     * factories being added is the order they will be executed.  Tracers should not
     * 添加 ServerStreamTracer.Factory 用于统计服务端的流量，工厂添加的顺序就是执行的顺序
     *
     * @return this
     * @since 1.3.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2861")
    public T addStreamTracerFactory(ServerStreamTracer.Factory factory) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets a fallback handler registry that will be looked up in if a method is not found in the
     * primary registry. The primary registry (configured via {@code addService()}) is faster but
     * immutable. The fallback registry is more flexible and allows implementations to mutate over
     * time and load services on-demand.
     * 设置回退的注册处理器，当主注册器中没有发现时在这个注册器中查找，主注册器快速但是不可变，回退注册器更灵活，
     * 允许实现随时间变化并按需加载服务
     *
     * @return this
     * @since 1.0.0
     */
    public abstract T fallbackHandlerRegistry(@Nullable HandlerRegistry fallbackRegistry);

    /**
     * Makes the server use TLS.
     * 让 Server 使用 TLS
     *
     * @param certChain  file containing the full certificate chain
     *                   证书的文件
     * @param privateKey file containing the private key
     *                   私钥的文件
     * @return this
     * @throws UnsupportedOperationException if the server does not support TLS.
     * @since 1.0.0
     */
    public abstract T useTransportSecurity(File certChain, File privateKey);

    /**
     * Makes the server use TLS.
     * 让 Server 使用 TLS
     *
     * @param certChain  InputStream containing the full certificate chain
     *                   包含证书的文件流
     * @param privateKey InputStream containing the private key
     *                   包含私钥的文件流
     * @return this
     * @throws UnsupportedOperationException if the server does not support TLS, or does not support
     *                                       reading these files from an InputStream.
     * @since 1.12.0
     */
    public T useTransportSecurity(InputStream certChain, InputStream privateKey) {
        throw new UnsupportedOperationException();
    }


    /**
     * Set the decompression registry for use in the channel.  This is an advanced API call and
     * shouldn't be used unless you are using custom message encoding.   The default supported
     * decompressors are in {@code DecompressorRegistry.getDefaultInstance}.
     * 设置 Channel 使用的解压缩注册器
     *
     * @return this
     * @since 1.0.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
    public abstract T decompressorRegistry(@Nullable DecompressorRegistry registry);

    /**
     * Set the compression registry for use in the channel.  This is an advanced API call and
     * shouldn't be used unless you are using custom message encoding.   The default supported
     * compressors are in {@code CompressorRegistry.getDefaultInstance}.
     * 设置 Channel 使用的压缩注册器
     *
     * @return this
     * @since 1.0.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
    public abstract T compressorRegistry(@Nullable CompressorRegistry registry);

    /**
     * Sets the permitted time for new connections to complete negotiation handshakes before being
     * killed.
     * 设置新的连接的握手在被杀掉之前完成的最大时间
     *
     * @return this
     * @throws IllegalArgumentException      if timeout is negative
     * @throws UnsupportedOperationException if unsupported
     * @since 1.8.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3706")
    public T handshakeTimeout(long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the maximum message size allowed to be received on the server. If not called,
     * defaults to 4 MiB. The default provides protection to servers who haven't considered the
     * possibility of receiving large messages while trying to be large enough to not be hit in normal
     * usage.
     * 设置 Server 允许接收的最大的消息大小，如果没有设置，最大是 4M，缺省值为未考虑可能会收到大邮件而试图大到
     * 在正常使用中不会受到打击的服务器提供保护
     *
     * <p>This method is advisory, and implementations may decide to not enforce this.  Currently,
     * the only known transport to not enforce this is {@code InProcessServer}.
     * 这个方法是建议性的，实现可能会变为强制
     *
     * @param bytes the maximum number of bytes a single message can be.
     *              允许接收的单个消息的最大字节数
     * @return this
     * @throws IllegalArgumentException      if bytes is negative.
     * @throws UnsupportedOperationException if unsupported.
     * @since 1.13.0
     */
    public T maxInboundMessageSize(int bytes) {
        // intentional noop rather than throw, this method is only advisory.
        Preconditions.checkArgument(bytes >= 0, "bytes must be >= 0");
        return thisT();
    }

    /**
     * Sets the maximum size of metadata allowed to be received. {@code Integer.MAX_VALUE} disables
     * the enforcement. The default is implementation-dependent, but is not generally less than 8 KiB
     * and may be unlimited.
     * 设置允许接收的最大的 metadata 大小，设置为 Integer.MAX_VALUE 表示禁用，默认的实现是独立的，但通常不小于 8kb，
     * 可能不受限制
     *
     * <p>This is cumulative size of the metadata. The precise calculation is
     * implementation-dependent, but implementations are encouraged to follow the calculation used for
     * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
     * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. It sums the bytes from each entry's key and value,
     * plus 32 bytes of overhead per entry.
     *
     * @param bytes the maximum size of received metadata
     *              允许接收的 Metadata 最大字节数
     * @return this
     * @throws IllegalArgumentException if bytes is non-positive
     * @since 1.17.0
     */
    public T maxInboundMetadataSize(int bytes) {
        Preconditions.checkArgument(bytes > 0, "maxInboundMetadataSize must be > 0");
        // intentional noop rather than throw, this method is only advisory.
        return thisT();
    }

    /**
     * Sets the BinaryLog object that this server should log to. The server does not take
     * ownership of the object, and users are responsible for calling {@link BinaryLog#close()}.
     * 设置 Server 用于记录二进制日志的对象，Server 不持有这个对象，由用户处理关闭
     *
     * @param binaryLog the object to provide logging.
     * @return this
     * @since 1.13.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4017")
    public T setBinaryLog(BinaryLog binaryLog) {
        throw new UnsupportedOperationException();
    }

    /**
     * Builds a server using the given parameters.
     * 根据所给的参数构建 Server
     *
     * <p>The returned service will not been started or be bound a port. You will need to start it
     * with {@link Server#start()}.
     * 返回的服务不会开始或者绑定端口，需要通过 start 开始
     *
     * @return a new Server
     * @since 1.0.0
     */
    public abstract Server build();

    /**
     * Returns the correctly typed version of the builder.
     * 返回当前的构建的类型
     */
    private T thisT() {
        @SuppressWarnings("unchecked")
        T thisT = (T) this;
        return thisT;
    }
}
