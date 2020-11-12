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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * A builder for {@link ManagedChannel} instances.
 * ManagedChannel 实例的构建器
 *
 * @param <T> The concrete type of this builder.
 *            构建器对应的类型
 */
public abstract class ManagedChannelBuilder<T extends ManagedChannelBuilder<T>> {

    /**
     * Creates a channel with the target's address and port number.
     * 使用指定的地址和端口创建 Channel
     *
     * @see #forTarget(String)
     * @since 1.0.0
     */
    public static ManagedChannelBuilder<?> forAddress(String name, int port) {
        return ManagedChannelProvider.provider().builderForAddress(name, port);
    }

    /**
     * Creates a channel with a target string, which can be either a valid {@link
     * NameResolver}-compliant URI, or an authority string.
     * 使用目标字符串创建 Channel，这个字符串是可以被 NameResolver 解析的 URI，或者服务名
     *
     * <p>A {@code NameResolver}-compliant URI is an absolute hierarchical URI as defined by {@link
     * java.net.URI}. Example URIs:
     * 符合 NameResovler 规范的 URI,如：
     * <ul>
     *   <li>{@code "dns:///foo.googleapis.com:8080"}</li>
     *   <li>{@code "dns:///foo.googleapis.com"}</li>
     *   <li>{@code "dns:///%5B2001:db8:85a3:8d3:1319:8a2e:370:7348%5D:443"}</li>
     *   <li>{@code "dns://8.8.8.8/foo.googleapis.com:8080"}</li>
     *   <li>{@code "dns://8.8.8.8/foo.googleapis.com"}</li>
     *   <li>{@code "zookeeper://zk.example.com:9900/example_service"}</li>
     * </ul>
     *
     * <p>An authority string will be converted to a {@code NameResolver}-compliant URI, which has
     * the scheme from the name resolver with the highest priority (e.g. {@code "dns"}),
     * no authority, and the original authority string as its path after properly escaped.
     * We recommend libraries to specify the schema explicitly if it is known, since libraries cannot
     * know which NameResolver will be default during runtime.
     * 服务名称将被转换为符合命名解析的 URI，具有命名解析中最高优先级的协议，如 dns，在转换后不会再有原有的服务名，
     * 建议库明确指定协议，因为不知道哪个 NameResolver 会是默认的
     * Example authority strings:
     * <ul>
     *   <li>{@code "localhost"}</li>
     *   <li>{@code "127.0.0.1"}</li>
     *   <li>{@code "localhost:8080"}</li>
     *   <li>{@code "foo.googleapis.com:8080"}</li>
     *   <li>{@code "127.0.0.1:8080"}</li>
     *   <li>{@code "[2001:db8:85a3:8d3:1319:8a2e:370:7348]"}</li>
     *   <li>{@code "[2001:db8:85a3:8d3:1319:8a2e:370:7348]:443"}</li>
     * </ul>
     *
     * @since 1.0.0
     */
    public static ManagedChannelBuilder<?> forTarget(String target) {
        return ManagedChannelProvider.provider().builderForTarget(target);
    }

    /**
     * Execute application code directly in the transport thread.
     * 在 Transport 的线程内直接执行应用代码
     *
     * <p>Depending on the underlying transport, using a direct executor may lead to substantial
     * performance improvements. However, it also requires the application to not block under
     * any circumstances.
     * 取决于 Transport，直接运行可能大幅提高性能，然而，要求应用任何情况下都不能阻塞线程
     *
     * <p>Calling this method is semantically equivalent to calling {@link #executor(Executor)} and
     * passing in a direct executor. However, this is the preferred way as it may allow the transport
     * to perform special optimizations.
     * 调用这个方法语义上等同于调用 executor(Executor)，传入直接运行的 executor；然而，这个方法是首选方法，因为允许
     * Transport 做特殊优化
     *
     * @return this
     * @since 1.0.0
     */
    public abstract T directExecutor();

    /**
     * Provides a custom executor.
     * 提供自定义的 Executor
     *
     * <p>It's an optional parameter. If the user has not provided an executor when the channel is
     * built, the builder will use a static cached thread pool.
     * 参数是可选的，如果没有在 Channel 构建时提供，Builder 会使用静态的缓存线程池
     *
     * <p>The channel won't take ownership of the given executor. It's caller's responsibility to
     * shut down the executor when it's desired.
     * 这个 Channel 不会拥有给定的线程池，需要关闭时需要调用者主动关闭
     *
     * @return this
     * @since 1.0.0
     */
    public abstract T executor(Executor executor);

    /**
     * Provides a custom executor that will be used for operations that block or are expensive.
     * 提供自定义的线程池，用于执行阻塞或者有消耗的操作
     *
     * <p>It's an optional parameter. If the user has not provided an executor when the channel is
     * built, the builder will use a static cached thread pool.
     * 参数是可选的，如果没有在 Channel 构建时提供，Builder 会使用静态的缓存线程池
     *
     * <p>The channel won't take ownership of the given executor. It's caller's responsibility to shut
     * down the executor when it's desired.
     * 这个 Channel 不会拥有给定的线程池，需要关闭时需要调用者主动关闭
     *
     * @return this
     * @throws UnsupportedOperationException if unsupported
     * @since 1.25.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6279")
    public T offloadExecutor(Executor executor) {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6279")
    public T blockingExecutor(Executor executor) {
        return offloadExecutor(executor);
    }

    /**
     * Adds interceptors that will be called before the channel performs its real work. This is
     * functionally equivalent to using {@link ClientInterceptors#intercept(Channel, List)}, but while
     * still having access to the original {@code ManagedChannel}. Interceptors run in the reverse
     * order in which they are added, just as with consecutive calls to {@code
     * ClientInterceptors.intercept()}.
     * 添加拦截器，用于在 Channel 真正工作之前调用，这个方法等同于 ClientInterceptors#intercept(Channel, List)，
     * 但是这个方法可以访问原始的 ManagedChannel，拦截器的执行顺序与添加顺序相反，就像连续调用 ClientInterceptors.intercept()
     * 一样
     *
     * @return this
     * @since 1.0.0
     */
    public abstract T intercept(List<ClientInterceptor> interceptors);

    /**
     * Adds interceptors that will be called before the channel performs its real work. This is
     * functionally equivalent to using {@link ClientInterceptors#intercept(Channel,
     * ClientInterceptor...)}, but while still having access to the original {@code ManagedChannel}.
     * Interceptors run in the reverse order in which they are added, just as with consecutive calls
     * to {@code ClientInterceptors.intercept()}.
     * 添加拦截器，用于在 Channel 真正工作之前调用，这个方法等同于 ClientInterceptors#intercept(Channel, ClientInterceptor...)，
     * 但是这个方法可以访问原始的 ManagedChannel，拦截器的执行顺序与添加顺序相反，就像连续调用 ClientInterceptors.intercept()
     *
     * @return this
     * @since 1.0.0
     */
    public abstract T intercept(ClientInterceptor... interceptors);

    /**
     * Provides a custom {@code User-Agent} for the application.
     * 为应用提供自定义的 User-Agent
     *
     * <p>It's an optional parameter. The library will provide a user agent independent of this
     * option. If provided, the given agent will prepend the library's user agent information.
     * 是一个可选的参数，库会提供独立的 User Agent，如果提供了，会将库提供的 User Agent 前置
     *
     * @return this
     * @since 1.0.0
     */
    public abstract T userAgent(String userAgent);

    /**
     * Overrides the authority used with TLS and HTTP virtual hosting. It does not change what host is
     * actually connected to. Is commonly in the form {@code host:port}.
     * 使用虚拟的主机覆盖服务名，不会改变真正连接的地址，通常是 host:port 形式
     *
     * <p>This method is intended for testing, but may safely be used outside of tests as an
     * alternative to DNS overrides.
     * 此方法用于测试，但是也可以用于 DNS
     *
     * @return this
     * @since 1.0.0
     */
    public abstract T overrideAuthority(String authority);

    /**
     * Use of a plaintext connection to the server. By default a secure connection mechanism
     * such as TLS will be used.
     * 使用纯文本的服务端连接，默认使用 TLS 机制的连接
     *
     * <p>Should only be used for testing or for APIs where the use of such API or the data
     * exchanged is not sensitive.
     * 应当仅用于测试和数据不敏感的接口
     *
     * <p>This assumes prior knowledge that the target of this channel is using plaintext.  It will
     * not perform HTTP/1.1 upgrades.
     * 该 Channel 使用纯文本，不会执行 HTTP/1.1 升级
     *
     * @return this
     * @throws UnsupportedOperationException if plaintext mode is not supported.
     * @since 1.11.0
     */
    public T usePlaintext() {
        throw new UnsupportedOperationException();
    }

    /**
     * Makes the client use TLS.
     * 客户端使用 TLS
     *
     * @return this
     * @throws UnsupportedOperationException if transport security is not supported.
     * @since 1.9.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3713")
    public T useTransportSecurity() {
        throw new UnsupportedOperationException();
    }

    /**
     * Provides a custom {@link NameResolver.Factory} for the channel. If this method is not called,
     * the builder will try the providers registered in the default {@link NameResolverRegistry} for
     * the given target.
     * 为 Channel 提供自定义的 NameResolver.Factory，如果这个方法没有调用，Builder 会尝试
     * 使用 NameResolverRegistry 中提供的默认的
     *
     * <p>This method should rarely be used, as name resolvers should provide a {@code
     * NameResolverProvider} and users rely on service loading to find implementations in the class
     * path. That allows application's configuration to easily choose the name resolver via the
     * 'target' string passed to {@link ManagedChannelBuilder#forTarget(String)}.
     * 这个方法应当尽可能少的使用，服务命名解析应当通过 NameResolverProvider 提供，并且用户依赖于 class 路径下的
     * 实现类加载，这样可以容易的选择命名解析的实现
     *
     * @return this
     * @since 1.0.0
     * @deprecated Most usages should use a globally-registered {@link NameResolverProvider} instead,
     * with either the SPI mechanism or {@link NameResolverRegistry#register}. Replacements for
     * all use-cases are not necessarily available yet. See
     * <a href="https://github.com/grpc/grpc-java/issues/7133">#7133</a>.
     * 应当更多的使用全局的 NameResolverProvider 注册的提供，或者通过 NameResolverRegistry#register 的 SPI 实现，
     * 所有用例的替换都不一定可用
     */
    @Deprecated
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    public abstract T nameResolverFactory(NameResolver.Factory resolverFactory);

    /**
     * Sets the default load-balancing policy that will be used if the service config doesn't specify
     * one.  If not set, the default will be the "pick_first" policy.
     * 设置默认的负载均衡策略，当服务的配置中没有指定时使用，如果没有设置，则会使用 pick_first 策略
     *
     * <p>Policy implementations are looked up in the
     * {@link LoadBalancerRegistry#getDefaultRegistry default LoadBalancerRegistry}.
     * 策略的实现从 LoadBalancerRegistry#getDefaultRegistry 中查找
     *
     * <p>This method is implemented by all stock channel builders that are shipped with gRPC, but may
     * not be implemented by custom channel builders, in which case this method will throw.
     *
     * @return this
     * @since 1.18.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
    public T defaultLoadBalancingPolicy(String policy) {
        throw new UnsupportedOperationException();
    }

    /**
     * Enables full-stream decompression of inbound streams. This will cause the channel's outbound
     * headers to advertise support for GZIP compressed streams, and gRPC servers which support the
     * feature may respond with a GZIP compressed stream.
     * 为入站的流开启整个流的压缩，这样会在 Channel 出站的 header 中通知支持 GZIP 流压缩，支持这个特性的服务端会
     * 使用 GZIP 压缩的流响应
     *
     * <p>EXPERIMENTAL: This method is here to enable an experimental feature, and may be changed or
     * removed once the feature is stable.
     *
     * @throws UnsupportedOperationException if unsupported
     * @since 1.7.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3399")
    public T enableFullStreamDecompression() {
        throw new UnsupportedOperationException();
    }

    /**
     * Set the decompression registry for use in the channel. This is an advanced API call and
     * shouldn't be used unless you are using custom message encoding. The default supported
     * decompressors are in {@link DecompressorRegistry#getDefaultInstance}.
     * 设置用于这个 Channel 的解压缩注册器，这是一个高级的 API，如果没有自定义的消息编码则不应当使用，默认的
     * 注册器通过 DecompressorRegistry#getDefaultInstance 获取
     *
     * @return this
     * @since 1.0.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
    public abstract T decompressorRegistry(DecompressorRegistry registry);

    /**
     * Set the compression registry for use in the channel.  This is an advanced API call and
     * shouldn't be used unless you are using custom message encoding.   The default supported
     * compressors are in {@link CompressorRegistry#getDefaultInstance}.
     * 设置用于这个 Channel 的压缩器注册器，如果没有自定义的消息编码则不应当使用，默认的
     * 注册器通过 CompressorRegistry#getDefaultInstance 获取
     *
     * @return this
     * @since 1.0.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
    public abstract T compressorRegistry(CompressorRegistry registry);

    /**
     * Set the duration without ongoing RPCs before going to idle mode.
     * 设置当没有请求时进入空闲模式的时间
     *
     * <p>In idle mode the channel shuts down all connections, the NameResolver and the
     * LoadBalancer. A new RPC would take the channel out of idle mode. A channel starts in idle mode.
     * Defaults to 30 minutes.
     * 在 IDLE 模式下 Channel 会关闭所有的连接以及 NameResolver 以及 LoadBalancer，新的请求会使 Channel 退出
     * 空闲模式，Channel 启动时处于空闲模式，默认是 30分钟
     *
     * <p>This is an advisory option. Do not rely on any specific behavior related to this option.
     * 是一个建议选项，不要依赖该选项用于执行特定的操作
     *
     * @return this
     * @since 1.0.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2022")
    public abstract T idleTimeout(long value, TimeUnit unit);

    /**
     * Sets the maximum message size allowed to be received on the channel. If not called,
     * defaults to 4 MiB. The default provides protection to clients who haven't considered the
     * possibility of receiving large messages while trying to be large enough to not be hit in normal
     * usage.
     * 设置 Channel 允许接收的消息的最大字节数，如果没有设置，默认是 4M，为没有准备接收大的消息的客户端提供保护
     *
     * <p>This method is advisory, and implementations may decide to not enforce this.  Currently,
     * the only known transport to not enforce this is {@code InProcessTransport}.
     * 这个方法是建议性的，实现可能不会强制执行此操作，当前已知的不会强制执行的是 InProcessTransport
     *
     * @param bytes the maximum number of bytes a single message can be.
     *              单个消息允许的最大字节数
     * @return this
     * @throws IllegalArgumentException if bytes is negative.
     * @since 1.1.0
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
     * 设置允许接收的最大的元数据的大小，Integer.MAX_VALUE 表示禁止强制执行，由实现决定，但是通常不小于 8kb，可能
     * 没有限制
     *
     * <p>This is cumulative size of the metadata. The precise calculation is
     * implementation-dependent, but implementations are encouraged to follow the calculation used for
     * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
     * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. It sums the bytes from each entry's key and value,
     * plus 32 bytes of overhead per entry.
     * 是累积的 metadata 的大小，精确的计算由实现类决定，每个 key 和 value 的字节数加上32字节的和
     *
     * @param bytes the maximum size of received metadata
     *              允许接收的 metadata 的字节数大小
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
     * Sets the time without read activity before sending a keepalive ping. An unreasonably small
     * value might be increased, and {@code Long.MAX_VALUE} nano seconds or an unreasonably large
     * value will disable keepalive. Defaults to infinite.
     * 设置发送 keepalive ping 之前没有读取活动的时间，可能会增加不合理的小值，Long.MAX_VALUE 表示禁用 keepalive
     * 默认是无限的
     *
     * <p>Clients must receive permission from the service owner before enabling this option.
     * Keepalives can increase the load on services and are commonly "invisible" making it hard to
     * notice when they are causing excessive load. Clients are strongly encouraged to use only as
     * small of a value as necessary.
     * 启用此项前，客户端必须活动服务端的允许，keepalive 可能会对服务端造成压力，建议客户端在必要时使用较小的值
     *
     * @throws UnsupportedOperationException if unsupported
     * @since 1.7.0
     */
    public T keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the time waiting for read activity after sending a keepalive ping. If the time expires
     * without any read activity on the connection, the connection is considered dead. An unreasonably
     * small value might be increased. Defaults to 20 seconds.
     * 设置发送 keepalive ping 之后等待读活动的时间，如果过了这个时间当前连接还没有读活动，则认为连接已经关闭，
     * 可能会增加不合理的小值，默认是 20s
     *
     * <p>This value should be at least multiple times the RTT to allow for lost packets.
     * 该值应至少是RTT的倍数，以允许丢失数据包
     *
     * @throws UnsupportedOperationException if unsupported
     * @since 1.7.0
     */
    public T keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets whether keepalive will be performed when there are no outstanding RPC on a connection.
     * Defaults to {@code false}.
     * 当连接上没有出站的请求时是否 keepalive，默认是 false
     *
     * <p>Clients must receive permission from the service owner before enabling this option.
     * Keepalives on unused connections can easilly accidentally consume a considerable amount of
     * bandwidth and CPU. {@link ManagedChannelBuilder#idleTimeout idleTimeout()} should generally be
     * used instead of this option.
     *
     * @throws UnsupportedOperationException if unsupported
     * @see #keepAliveTime(long, TimeUnit)
     * @since 1.7.0
     */
    public T keepAliveWithoutCalls(boolean enable) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets max number of retry attempts. The total number of retry attempts for each RPC will not
     * exceed this number even if service config may allow a higher number. Setting this number to
     * zero is not effectively the same as {@code disableRetry()} because the former does not disable
     * 设置重试的最大次数，每个请求的重试次数不会高于这个值，即使设置了比这个值大的数，设置为 0 不会禁止进行重试，
     * 因为前者没有禁用
     *
     * <a href="https://github.com/grpc/proposal/blob/master/A6-client-retries.md#transparent-retries">
     * transparent retry</a>.
     *
     * <p>This method may not work as expected for the current release because retry is not fully
     * implemented yet.
     *
     * @return this
     * @since 1.11.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3982")
    public T maxRetryAttempts(int maxRetryAttempts) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets max number of hedged attempts. The total number of hedged attempts for each RPC will not
     * exceed this number even if service config may allow a higher number.
     * 设置对冲的最大次数，每个请求的对冲次数不会高于这个值
     *
     * <p>This method may not work as expected for the current release because retry is not fully
     * implemented yet.
     *
     * @return this
     * @since 1.11.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3982")
    public T maxHedgedAttempts(int maxHedgedAttempts) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the retry buffer size in bytes. If the buffer limit is exceeded, no RPC
     * could retry at the moment, and in hedging case all hedges but one of the same RPC will cancel.
     * The implementation may only estimate the buffer size being used rather than count the
     * exact physical memory allocated. The method does not have any effect if retry is disabled by
     * the client.
     * 设置重试的缓冲区大小，如果缓冲区被耗尽了，则不会有重试请求，在对冲下，相同的请求将被取消，
     * 该实现可能仅估计正在使用的缓冲区大小，而不计算分配的确切物理内存，如果客户端禁用了重试则没有任何效果
     *
     * <p>This method may not work as expected for the current release because retry is not fully
     * implemented yet.
     *
     * @return this
     * @since 1.10.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3982")
    public T retryBufferSize(long bytes) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the per RPC buffer limit in bytes used for retry. The RPC is not retriable if its buffer
     * limit is exceeded. The implementation may only estimate the buffer size being used rather than
     * count the exact physical memory allocated. It does not have any effect if retry is disabled by
     * the client.
     *
     * <p>This method may not work as expected for the current release because retry is not fully
     * implemented yet.
     *
     * @return this
     * @since 1.10.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3982")
    public T perRpcBufferLimit(long bytes) {
        throw new UnsupportedOperationException();
    }


    /**
     * Disables the retry and hedging mechanism provided by the gRPC library. This is designed for the
     * case when users have their own retry implementation and want to avoid their own retry taking
     * place simultaneously with the gRPC library layer retry.
     *
     * @return this
     * @since 1.11.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3982")
    public T disableRetry() {
        throw new UnsupportedOperationException();
    }

    /**
     * Enables the retry and hedging mechanism provided by the gRPC library.
     *
     * <p>For the current release, this method may have a side effect that disables Census stats and
     * tracing.
     *
     * @return this
     * @since 1.11.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/3982")
    public T enableRetry() {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the BinaryLog object that this channel should log to. The channel does not take
     * ownership of the object, and users are responsible for calling {@link BinaryLog#close()}.
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
     * Sets the maximum number of channel trace events to keep in the tracer for each channel or
     * subchannel. If set to 0, channel tracing is effectively disabled.
     *
     * @return this
     * @since 1.13.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4471")
    public T maxTraceEvents(int maxTraceEvents) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the proxy detector to be used in addresses name resolution. If <code>null</code> is passed
     * the default proxy detector will be used.  For how proxies work in gRPC, please refer to the
     * documentation on {@link ProxyDetector}.
     *
     * @return this
     * @since 1.19.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/5113")
    public T proxyDetector(ProxyDetector proxyDetector) {
        throw new UnsupportedOperationException();
    }

    /**
     * Provides a service config to the channel. The channel will use the default service config when
     * the name resolver provides no service config or if the channel disables lookup service config
     * from name resolver (see {@link #disableServiceConfigLookUp()}). The argument
     * {@code serviceConfig} is a nested map representing a Json object in the most natural way:
     *
     *        <table border="1">
     *          <tr>
     *            <td>Json entry</td><td>Java Type</td>
     *          </tr>
     *          <tr>
     *            <td>object</td><td>{@link Map}</td>
     *          </tr>
     *          <tr>
     *            <td>array</td><td>{@link List}</td>
     *          </tr>
     *          <tr>
     *            <td>string</td><td>{@link String}</td>
     *          </tr>
     *          <tr>
     *            <td>number</td><td>{@link Double}</td>
     *          </tr>
     *          <tr>
     *            <td>boolean</td><td>{@link Boolean}</td>
     *          </tr>
     *          <tr>
     *            <td>null</td><td>{@code null}</td>
     *          </tr>
     *        </table>
     *
     * <p>If null is passed, then there will be no default service config.
     *
     * @return this
     * @throws IllegalArgumentException When the given serviceConfig is invalid or the current version
     *                                  of grpc library can not parse it gracefully. The state of the builder is unchanged if
     *                                  an exception is thrown.
     * @since 1.20.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/5189")
    public T defaultServiceConfig(@Nullable Map<String, ?> serviceConfig) {
        throw new UnsupportedOperationException();
    }

    /**
     * Disables service config look-up from the naming system, which is enabled by default.
     *
     * @return this
     * @since 1.20.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/5189")
    public T disableServiceConfigLookUp() {
        throw new UnsupportedOperationException();
    }

    /**
     * Builds a channel using the given parameters.
     *
     * @since 1.0.0
     */
    public abstract ManagedChannel build();

    /**
     * Returns the correctly typed version of the builder.
     */
    private T thisT() {
        @SuppressWarnings("unchecked")
        T thisT = (T) this;
        return thisT;
    }
}
