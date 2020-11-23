/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.netty;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.ExperimentalApi;
import io.grpc.Internal;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.AbstractServerImplBuilder;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.internal.GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;
import static io.grpc.internal.GrpcUtil.DEFAULT_SERVER_KEEPALIVE_TIMEOUT_NANOS;
import static io.grpc.internal.GrpcUtil.DEFAULT_SERVER_KEEPALIVE_TIME_NANOS;
import static io.grpc.internal.GrpcUtil.SERVER_KEEPALIVE_TIME_NANOS_DISABLED;

/**
 * A builder to help simplify the construction of a Netty-based GRPC server.
 * 基于 Netty 的 gRPC Server 构建器
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1784")
@CanIgnoreReturnValue
public final class NettyServerBuilder extends AbstractServerImplBuilder<NettyServerBuilder> {

    // 1MiB
    public static final int DEFAULT_FLOW_CONTROL_WINDOW = 1024 * 1024;

    static final long MAX_CONNECTION_IDLE_NANOS_DISABLED = Long.MAX_VALUE;
    static final long MAX_CONNECTION_AGE_NANOS_DISABLED = Long.MAX_VALUE;
    static final long MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE = Long.MAX_VALUE;

    private static final long MIN_KEEPALIVE_TIME_NANO = TimeUnit.MILLISECONDS.toNanos(1L);
    private static final long MIN_KEEPALIVE_TIMEOUT_NANO = TimeUnit.MICROSECONDS.toNanos(499L);
    private static final long MIN_MAX_CONNECTION_IDLE_NANO = TimeUnit.SECONDS.toNanos(1L);
    private static final long MIN_MAX_CONNECTION_AGE_NANO = TimeUnit.SECONDS.toNanos(1L);
    private static final long AS_LARGE_AS_INFINITE = TimeUnit.DAYS.toNanos(1000L);

    private static final ObjectPool<? extends EventLoopGroup> DEFAULT_BOSS_EVENT_LOOP_GROUP_POOL = SharedResourcePool.forResource(Utils.DEFAULT_BOSS_EVENT_LOOP_GROUP);
    private static final ObjectPool<? extends EventLoopGroup> DEFAULT_WORKER_EVENT_LOOP_GROUP_POOL = SharedResourcePool.forResource(Utils.DEFAULT_WORKER_EVENT_LOOP_GROUP);

    private final List<SocketAddress> listenAddresses = new ArrayList<>();

    private ChannelFactory<? extends ServerChannel> channelFactory = Utils.DEFAULT_SERVER_CHANNEL_FACTORY;
    private final Map<ChannelOption<?>, Object> channelOptions = new HashMap<>();
    private final Map<ChannelOption<?>, Object> childChannelOptions = new HashMap<>();

    private ObjectPool<? extends EventLoopGroup> bossEventLoopGroupPool = DEFAULT_BOSS_EVENT_LOOP_GROUP_POOL;
    private ObjectPool<? extends EventLoopGroup> workerEventLoopGroupPool = DEFAULT_WORKER_EVENT_LOOP_GROUP_POOL;

    private boolean forceHeapBuffer;
    private SslContext sslContext;
    private ProtocolNegotiator protocolNegotiator;
    private int maxConcurrentCallsPerConnection = Integer.MAX_VALUE;

    private boolean autoFlowControl = true;
    private int flowControlWindow = DEFAULT_FLOW_CONTROL_WINDOW;

    private int maxMessageSize = DEFAULT_MAX_MESSAGE_SIZE;
    private int maxHeaderListSize = GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE;

    private long keepAliveTimeInNanos = DEFAULT_SERVER_KEEPALIVE_TIME_NANOS;
    private long keepAliveTimeoutInNanos = DEFAULT_SERVER_KEEPALIVE_TIMEOUT_NANOS;

    private long maxConnectionIdleInNanos = MAX_CONNECTION_IDLE_NANOS_DISABLED;
    private long maxConnectionAgeInNanos = MAX_CONNECTION_AGE_NANOS_DISABLED;
    private long maxConnectionAgeGraceInNanos = MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE;

    private boolean permitKeepAliveWithoutCalls;
    private long permitKeepAliveTimeInNanos = TimeUnit.MINUTES.toNanos(5);

    /**
     * Creates a server builder that will bind to the given port.
     * 创建一个 Server 构建器，绑定到给定的端口
     *
     * @param port the port on which the server is to be bound.
     *             server 需要绑定的端口
     * @return the server builder.
     */
    @CheckReturnValue
    public static NettyServerBuilder forPort(int port) {
        return new NettyServerBuilder(port);
    }

    /**
     * Creates a server builder configured with the given {@link SocketAddress}.
     * 根据所给的 SocketAddress 创建 Server 构建器
     *
     * @param address the socket address on which the server is to be bound.
     *                server 绑定的地址
     * @return the server builder
     */
    @CheckReturnValue
    public static NettyServerBuilder forAddress(SocketAddress address) {
        return new NettyServerBuilder(address);
    }

    /**
     * 创建 Server 并绑定到指定的端口
     */
    @CheckReturnValue
    private NettyServerBuilder(int port) {
        // 将本地 IP 和端口的地址添加到监听的地址集合中
        this.listenAddresses.add(new InetSocketAddress(port));
    }

    @CheckReturnValue
    private NettyServerBuilder(SocketAddress address) {
        // 将给定的地址添加到监听的地址集合中
        this.listenAddresses.add(address);
    }

    /**
     * Adds an additional address for this server to listen on.  Callers must ensure that all socket
     * addresses are compatible with the Netty channel type, and that they don't conflict with each
     * other.
     * 添加 Server 监听的额外的地址，调用者需要确保所有的地址都兼容 Netty 的 Channel 类型，互相之间没有冲突
     */
    public NettyServerBuilder addListenAddress(SocketAddress listenAddress) {
        this.listenAddresses.add(checkNotNull(listenAddress, "listenAddress"));
        return this;
    }

    /**
     * Specifies the channel type to use, by default we use {@code EpollServerSocketChannel} if
     * available, otherwise using {@link NioServerSocketChannel}.
     * 指定使用的 Channel 类型，当 EpollServerSocketChannel 可用时默认使用，除此之外使用 NioServerSocketChannel
     *
     * <p>You either use this or {@link #channelFactory(io.netty.channel.ChannelFactory)} if your
     * {@link ServerChannel} implementation has no no-args constructor.
     * 如果  ServerChannel 实现有无参的构造方法，也可以使用这个方法或者 channelFactory(io.netty.channel.ChannelFactory)
     *
     * <p>It's an optional parameter. If the user has not provided an Channel type or ChannelFactory
     * when the channel is built, the builder will use the default one which is static.
     * 是一个可选的参数，如果没有提供 Channel 类型或者 ChannelFactory，会使用默认的静态的
     *
     * <p>You must also provide corresponding {@link EventLoopGroup} using {@link
     * #workerEventLoopGroup(EventLoopGroup)} and {@link #bossEventLoopGroup(EventLoopGroup)}. For
     * example, {@link NioServerSocketChannel} must use {@link
     * io.netty.channel.nio.NioEventLoopGroup}, otherwise your server won't start.
     * 必须提供 workerEventLoopGroup 和 bossEventLoopGroup 对应的 EventLoopGroup，如，NioServerSocketChannel 必须使用
     * NioEventLoopGroup，否则 Server 无法启动
     */
    public NettyServerBuilder channelType(Class<? extends ServerChannel> channelType) {
        checkNotNull(channelType, "channelType");
        return channelFactory(new ReflectiveChannelFactory<>(channelType));
    }

    /**
     * Specifies the {@link ChannelFactory} to create {@link ServerChannel} instances. This method is
     * usually only used if the specific {@code ServerChannel} requires complex logic which requires
     * additional information to create the {@code ServerChannel}. Otherwise, recommend to use {@link
     * #channelType(Class)}.
     * 指定 ChannelFactory 创建 ServerChannel 实例，这个方法通常用于 ServerChannel 有复杂的逻辑，需要在创建
     * ServerChannel 时有附加的信息，否则建议使用 channelType(Class)
     *
     * <p>It's an optional parameter. If the user has not provided an Channel type or ChannelFactory
     * when the channel is built, the builder will use the default one which is static.
     * 是一个可选的参数，如果用户没有提供 Channel 类型或者 ChannelFactory 则会使用默认的静态的
     *
     * <p>You must also provide corresponding {@link EventLoopGroup} using {@link
     * #workerEventLoopGroup(EventLoopGroup)} and {@link #bossEventLoopGroup(EventLoopGroup)}. For
     * example, if the factory creates {@link NioServerSocketChannel} you must use {@link
     * io.netty.channel.nio.NioEventLoopGroup}, otherwise your server won't start.
     */
    public NettyServerBuilder channelFactory(ChannelFactory<? extends ServerChannel> channelFactory) {
        this.channelFactory = checkNotNull(channelFactory, "channelFactory");
        return this;
    }

    /**
     * Specifies a channel option. As the underlying channel as well as network implementation may
     * ignore this value applications should consider it a hint.
     * 指定 Channel 选项，底层的 Channel 和网络实现可能会忽略这个值，将其视为提示
     *
     * @since 1.30.0
     */
    public <T> NettyServerBuilder withOption(ChannelOption<T> option, T value) {
        this.channelOptions.put(option, value);
        return this;
    }

    /**
     * Specifies a child channel option. As the underlying channel as well as network implementation
     * may ignore this value applications should consider it a hint.
     * 指定子 Channel 的选项，底层的 Channel 和网络实现可能会忽略这个值，将其视为提示
     *
     * @since 1.9.0
     */
    public <T> NettyServerBuilder withChildOption(ChannelOption<T> option, T value) {
        this.childChannelOptions.put(option, value);
        return this;
    }

    /**
     * Provides the boss EventGroupLoop to the server.
     * 为 Server 提供 boss EventGroupLoop
     *
     * <p>It's an optional parameter. If the user has not provided one when the server is built, the
     * builder will use the default one which is static.
     * 是一个可选的参数，如果用户没有提供，则会使用默认的静态的
     *
     * <p>You must also provide corresponding {@link io.netty.channel.Channel} type using {@link
     * #channelType(Class)} and {@link #workerEventLoopGroup(EventLoopGroup)}. For example, {@link
     * NioServerSocketChannel} must use {@link io.netty.channel.nio.NioEventLoopGroup} for both boss
     * and worker {@link EventLoopGroup}, otherwise your server won't start.
     *
     * <p>The server won't take ownership of the given EventLoopGroup. It's caller's responsibility
     * to shut it down when it's desired.
     * Server 不会持有这个 EventLoopGroup，调用者需要保证在需要的时候主动关闭
     *
     * <p>Grpc uses non-daemon {@link Thread}s by default and thus a {@link io.grpc.Server} will
     * continue to run even after the main thread has terminated. However, users have to be cautious
     * when providing their own {@link EventLoopGroup}s.
     * gRPC 默认使用非守护进程，所以 Server 会继续运行，即使主线程被关闭，但是用户在提供 EventLoopGroup 时依然需要谨慎
     * <p>
     * For example, Netty's {@link EventLoopGroup}s use daemon threads by default
     * and thus an application with only daemon threads running besides the main thread will exit as
     * soon as the main thread completes.
     * 如 Netty 的 EventLoopGroup 默认使用守护进程，因此在主线程退出后，仅在主线程之外运行且只有守护程序线程的应用程序将在退出
     * <p>
     * A simple solution to this problem is to call {@link io.grpc.Server#awaitTermination()} to
     * keep the main thread alive until the server has terminated.
     * 简单的解决方法是调用 Server#awaitTermination() 保证主线程存活，直到 Server 终止
     */
    public NettyServerBuilder bossEventLoopGroup(EventLoopGroup group) {
        if (group != null) {
            return bossEventLoopGroupPool(new FixedObjectPool<>(group));
        }
        return bossEventLoopGroupPool(DEFAULT_BOSS_EVENT_LOOP_GROUP_POOL);
    }

    NettyServerBuilder bossEventLoopGroupPool(ObjectPool<? extends EventLoopGroup> bossEventLoopGroupPool) {
        this.bossEventLoopGroupPool = checkNotNull(bossEventLoopGroupPool, "bossEventLoopGroupPool");
        return this;
    }

    /**
     * Provides the worker EventGroupLoop to the server.
     * 为 Server 提供 worker EventGroupLoop
     *
     * <p>It's an optional parameter. If the user has not provided one when the server is built, the
     * builder will create one.
     *
     * <p>You must also provide corresponding {@link io.netty.channel.Channel} type using {@link
     * #channelType(Class)} and {@link #bossEventLoopGroup(EventLoopGroup)}. For example, {@link
     * NioServerSocketChannel} must use {@link io.netty.channel.nio.NioEventLoopGroup} for both boss
     * and worker {@link EventLoopGroup}, otherwise your server won't start.
     *
     * <p>The server won't take ownership of the given EventLoopGroup. It's caller's responsibility
     * to shut it down when it's desired.
     *
     * <p>Grpc uses non-daemon {@link Thread}s by default and thus a {@link io.grpc.Server} will
     * continue to run even after the main thread has terminated. However, users have to be cautious
     * when providing their own {@link EventLoopGroup}s.
     * For example, Netty's {@link EventLoopGroup}s use daemon threads by default
     * and thus an application with only daemon threads running besides the main thread will exit as
     * soon as the main thread completes.
     * A simple solution to this problem is to call {@link io.grpc.Server#awaitTermination()} to
     * keep the main thread alive until the server has terminated.
     */
    public NettyServerBuilder workerEventLoopGroup(EventLoopGroup group) {
        if (group != null) {
            return workerEventLoopGroupPool(new FixedObjectPool<>(group));
        }
        return workerEventLoopGroupPool(DEFAULT_WORKER_EVENT_LOOP_GROUP_POOL);
    }

    NettyServerBuilder workerEventLoopGroupPool(ObjectPool<? extends EventLoopGroup> workerEventLoopGroupPool) {
        this.workerEventLoopGroupPool = checkNotNull(workerEventLoopGroupPool, "workerEventLoopGroupPool");
        return this;
    }

    /**
     * Force using heap buffer when custom allocator is enabled.
     * 当自定义的分配器可用时强制使用堆缓冲
     */
    void setForceHeapBuffer(boolean value) {
        forceHeapBuffer = value;
    }

    /**
     * Sets the TLS context to use for encryption. Providing a context enables encryption. It must
     * have been configured with {@link GrpcSslContexts}, but options could have been overridden.
     * 设置用于加密的 TLS 上下文，提供用于加密的上下文，必须通过 GrpcSslContexts 配置，但是选项应当已经被覆盖
     */
    public NettyServerBuilder sslContext(SslContext sslContext) {
        if (sslContext != null) {
            checkArgument(sslContext.isServer(), "Client SSL context can not be used for server");
            GrpcSslContexts.ensureAlpnAndH2Enabled(sslContext.applicationProtocolNegotiator());
        }
        this.sslContext = sslContext;
        return this;
    }

    /**
     * Sets the {@link ProtocolNegotiator} to be used. If non-{@code null}, overrides the value
     * specified in {@link #sslContext(SslContext)}.
     * 设置需要使用的协议谈判，如果是非 null 的，会覆盖 sslContext(SslContext) 中设置的值
     *
     * <p>Default: {@code null}.
     * 默认是 null
     */
    @Internal
    public final NettyServerBuilder protocolNegotiator(@Nullable ProtocolNegotiator protocolNegotiator) {
        this.protocolNegotiator = protocolNegotiator;
        return this;
    }

    @Override
    protected void setTracingEnabled(boolean value) {
        super.setTracingEnabled(value);
    }

    @Override
    protected void setStatsEnabled(boolean value) {
        super.setStatsEnabled(value);
    }

    @Override
    protected void setStatsRecordStartedRpcs(boolean value) {
        super.setStatsRecordStartedRpcs(value);
    }

    @Override
    protected void setStatsRecordRealTimeMetrics(boolean value) {
        super.setStatsRecordRealTimeMetrics(value);
    }

    /**
     * The maximum number of concurrent calls permitted for each incoming connection. Defaults to no
     * limit.
     * 每个连接允许的最大并发调用，默认没有限制
     */
    public NettyServerBuilder maxConcurrentCallsPerConnection(int maxCalls) {
        checkArgument(maxCalls > 0, "max must be positive: %s", maxCalls);
        this.maxConcurrentCallsPerConnection = maxCalls;
        return this;
    }

    /**
     * Sets the initial flow control window in bytes. Setting initial flow control window enables auto
     * flow control tuning using bandwidth-delay product algorithm. To disable auto flow control
     * tuning, use {@link #flowControlWindow(int)}. By default, auto flow control is enabled with
     * initial flow control window size of {@link #DEFAULT_FLOW_CONTROL_WINDOW}.
     * 设置初始流控窗口字节大小，设置初始流量控制窗口可使用带宽延迟乘积算法自动进行流量控制调整，如果要关闭自动流控，
     * 调用 flowControlWindow，默认会使用 DEFAULT_FLOW_CONTROL_WINDOW 作为初始流控窗口大小
     */
    public NettyServerBuilder initialFlowControlWindow(int initialFlowControlWindow) {
        checkArgument(initialFlowControlWindow > 0, "initialFlowControlWindow must be positive");
        this.flowControlWindow = initialFlowControlWindow;
        this.autoFlowControl = true;
        return this;
    }

    /**
     * Sets the flow control window in bytes. Setting flowControlWindow disables auto flow control
     * tuning; use {@link #initialFlowControlWindow(int)} to enable auto flow control tuning. If not
     * called, the default value is {@link #DEFAULT_FLOW_CONTROL_WINDOW}) with auto flow control
     * tuning.
     * 设置流控窗口字节大小，设置 flowControlWindow 会禁用自动流控，可以使用 initialFlowControlWindow 开启，
     * 如果没有调用这个方法，会使用 DEFAULT_FLOW_CONTROL_WINDOW 作为窗口大小并开启自动流控
     */
    public NettyServerBuilder flowControlWindow(int flowControlWindow) {
        checkArgument(flowControlWindow > 0, "flowControlWindow must be positive: %s", flowControlWindow);
        this.flowControlWindow = flowControlWindow;
        this.autoFlowControl = false;
        return this;
    }

    /**
     * Sets the maximum message size allowed to be received on the server. If not called,
     * defaults to 4 MiB. The default provides protection to services who haven't considered the
     * possibility of receiving large messages while trying to be large enough to not be hit in normal
     * usage.
     * 设置 Server 允许接收的最大的消息字节数量，如果没有调用，则默认使用 4M，缺省值为未考虑接收大消息而试图将其发送到
     * 足够大而不会在正常使用中受到打击的服务提供保护
     *
     * @deprecated Call {@link #maxInboundMessageSize} instead. This method will be removed in a
     * future release.
     */
    @Deprecated
    public NettyServerBuilder maxMessageSize(int maxMessageSize) {
        return maxInboundMessageSize(maxMessageSize);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NettyServerBuilder maxInboundMessageSize(int bytes) {
        checkArgument(bytes >= 0, "bytes must be non-negative: %s", bytes);
        this.maxMessageSize = bytes;
        return this;
    }

    /**
     * Sets the maximum size of header list allowed to be received. This is cumulative size of the
     * headers with some overhead, as defined for
     * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
     * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. The default is 8 KiB.
     * 设置允许接收的 Header 的最大字节大小
     *
     * @deprecated Use {@link #maxInboundMetadataSize} instead
     */
    @Deprecated
    public NettyServerBuilder maxHeaderListSize(int maxHeaderListSize) {
        return maxInboundMetadataSize(maxHeaderListSize);
    }

    /**
     * Sets the maximum size of metadata allowed to be received. This is cumulative size of the
     * entries with some overhead, as defined for
     * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
     * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. The default is 8 KiB.
     * 设置允许接收 header 的最大的字节大小
     *
     * @param bytes the maximum size of received metadata
     * @return this
     * @throws IllegalArgumentException if bytes is non-positive
     * @since 1.17.0
     */
    @Override
    public NettyServerBuilder maxInboundMetadataSize(int bytes) {
        checkArgument(bytes > 0, "maxInboundMetadataSize must be positive: %s", bytes);
        this.maxHeaderListSize = bytes;
        return this;
    }

    /**
     * Sets a custom keepalive time, the delay time for sending next keepalive ping. An unreasonably
     * small value might be increased, and {@code Long.MAX_VALUE} nano seconds or an unreasonably
     * large value will disable keepalive.
     * 设置自定义的 keepalive 时间，发送下一个 keepalive ping 的延迟时间，可能会增加不合理的小值，不合理的大值
     * 或 Long.MAX_VALUE 会禁用 keepalive
     *
     * @since 1.3.0
     */
    public NettyServerBuilder keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
        checkArgument(keepAliveTime > 0L, "keepalive time must be positive：%s", keepAliveTime);
        keepAliveTimeInNanos = timeUnit.toNanos(keepAliveTime);
        // 如果小于 10s，则设置为 10s
        keepAliveTimeInNanos = KeepAliveManager.clampKeepAliveTimeInNanos(keepAliveTimeInNanos);
        // 如果超过最大值，则禁用 keepalive
        if (keepAliveTimeInNanos >= AS_LARGE_AS_INFINITE) {
            // Bump keepalive time to infinite. This disables keep alive.
            keepAliveTimeInNanos = SERVER_KEEPALIVE_TIME_NANOS_DISABLED;
        }
        // 如果小于 1ms，则设置为 1ms
        if (keepAliveTimeInNanos < MIN_KEEPALIVE_TIME_NANO) {
            // Bump keepalive time.
            keepAliveTimeInNanos = MIN_KEEPALIVE_TIME_NANO;
        }
        return this;
    }

    /**
     * Sets a custom keepalive timeout, the timeout for keepalive ping requests. An unreasonably small
     * value might be increased.
     * 设置自定义的 keepalive ping 请求的超时时间，不合理的小值将会被变大
     *
     * @since 1.3.0
     */
    public NettyServerBuilder keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
        checkArgument(keepAliveTimeout > 0L, "keepalive timeout must be positive: %s", keepAliveTimeout);
        keepAliveTimeoutInNanos = timeUnit.toNanos(keepAliveTimeout);
        keepAliveTimeoutInNanos = KeepAliveManager.clampKeepAliveTimeoutInNanos(keepAliveTimeoutInNanos);
        if (keepAliveTimeoutInNanos < MIN_KEEPALIVE_TIMEOUT_NANO) {
            // Bump keepalive timeout.
            keepAliveTimeoutInNanos = MIN_KEEPALIVE_TIMEOUT_NANO;
        }
        return this;
    }

    /**
     * Sets a custom max connection idle time, connection being idle for longer than which will be
     * gracefully terminated. Idleness duration is defined since the most recent time the number of
     * outstanding RPCs became zero or the connection establishment. An unreasonably small value might
     * be increased. {@code Long.MAX_VALUE} nano seconds or an unreasonably large value will disable
     * max connection idle.
     * 设置自定义的最大连接空闲时间，超过最大空闲时间的连接将被优雅关闭，空闲时间定义为出站请求变为 0 或者连接建立开始，
     * 不合理的小值将会变大，Long.MAX_VALUE 或者不合理的大值将禁用最大连接空闲时间
     *
     * @since 1.4.0
     */
    public NettyServerBuilder maxConnectionIdle(long maxConnectionIdle, TimeUnit timeUnit) {
        checkArgument(maxConnectionIdle > 0L, "max connection idle must be positive: %s", maxConnectionIdle);
        maxConnectionIdleInNanos = timeUnit.toNanos(maxConnectionIdle);
        if (maxConnectionIdleInNanos >= AS_LARGE_AS_INFINITE) {
            maxConnectionIdleInNanos = MAX_CONNECTION_IDLE_NANOS_DISABLED;
        }
        if (maxConnectionIdleInNanos < MIN_MAX_CONNECTION_IDLE_NANO) {
            maxConnectionIdleInNanos = MIN_MAX_CONNECTION_IDLE_NANO;
        }
        return this;
    }

    /**
     * Sets a custom max connection age, connection lasting longer than which will be gracefully
     * terminated. An unreasonably small value might be increased.  A random jitter of +/-10% will be
     * added to it. {@code Long.MAX_VALUE} nano seconds or an unreasonably large value will disable
     * max connection age.
     * <p>
     * 设置自定义的最大连接时间，超过这个时间的连接将被优雅关闭，不合理的小值将被增大，会添加一个随机 +/-10% 的抖动，
     * Long.MAX_VALUE 或者不合理的大值将会禁用最大连接时间
     *
     * @since 1.3.0
     */
    public NettyServerBuilder maxConnectionAge(long maxConnectionAge, TimeUnit timeUnit) {
        checkArgument(maxConnectionAge > 0L, "max connection age must be positive: %s", maxConnectionAge);
        maxConnectionAgeInNanos = timeUnit.toNanos(maxConnectionAge);
        if (maxConnectionAgeInNanos >= AS_LARGE_AS_INFINITE) {
            maxConnectionAgeInNanos = MAX_CONNECTION_AGE_NANOS_DISABLED;
        }
        if (maxConnectionAgeInNanos < MIN_MAX_CONNECTION_AGE_NANO) {
            maxConnectionAgeInNanos = MIN_MAX_CONNECTION_AGE_NANO;
        }
        return this;
    }

    /**
     * Sets a custom grace time for the graceful connection termination. Once the max connection age
     * is reached, RPCs have the grace time to complete. RPCs that do not complete in time will be
     * cancelled, allowing the connection to terminate. {@code Long.MAX_VALUE} nano seconds or an
     * unreasonably large value are considered infinite.
     * <p>
     * 为正常连接终止设置自定义宽限时间，达到最大连接时间后，将有宽限时间来完成请求，未完成的请求将被取消，连接将会终止，
     * Long.MAX_VALUE 或者不合理的大值将会被认为是无限大
     *
     * @see #maxConnectionAge(long, TimeUnit)
     * @since 1.3.0
     */
    public NettyServerBuilder maxConnectionAgeGrace(long maxConnectionAgeGrace, TimeUnit timeUnit) {
        checkArgument(maxConnectionAgeGrace >= 0L, "max connection age grace must be non-negative: %s", maxConnectionAgeGrace);
        maxConnectionAgeGraceInNanos = timeUnit.toNanos(maxConnectionAgeGrace);
        if (maxConnectionAgeGraceInNanos >= AS_LARGE_AS_INFINITE) {
            maxConnectionAgeGraceInNanos = MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE;
        }
        return this;
    }

    /**
     * Specify the most aggressive keep-alive time clients are permitted to configure. The server will
     * try to detect clients exceeding this rate and when detected will forcefully close the
     * connection. The default is 5 minutes.
     * 指定允许客户端配置的最积极的保持活动时间，服务器将尝试检测超过此比例的客户端，并在检测到该客户端时强行关闭连接，
     * 默认是 5 分钟
     *
     * <p>Even though a default is defined that allows some keep-alives, clients must not use
     * keep-alive without approval from the service owner. Otherwise, they may experience failures in
     * the future if the service becomes more restrictive. When unthrottled, keep-alives can cause a
     * significant amount of traffic and CPU usage, so clients and servers should be conservative in
     * what they use and accept.
     * 即使定义了允许某些保持活动的默认设置，但未经服务所有者批准，客户也不得使用保持活跃，否则，如果服务端变的严格，
     * 连接可能会失败，如果不进行节流，则保持活动状态可能会导致大量的流量和CPU使用率，因此客户端和服务器在使用和接受
     * 的内容上应该比较保守
     *
     * @see #permitKeepAliveWithoutCalls(boolean)
     * @since 1.3.0
     */
    public NettyServerBuilder permitKeepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
        checkArgument(keepAliveTime >= 0, "permit keepalive time must be non-negative: %s", keepAliveTime);
        permitKeepAliveTimeInNanos = timeUnit.toNanos(keepAliveTime);
        return this;
    }

    /**
     * Sets whether to allow clients to send keep-alive HTTP/2 PINGs even if there are no outstanding
     * RPCs on the connection. Defaults to {@code false}.
     * 设置是否允许客户端发送 keepalive 的 HTTP2 ping 请求，即使没有出站的请求，默认是 false
     *
     * @see #permitKeepAliveTime(long, TimeUnit)
     * @since 1.3.0
     */
    public NettyServerBuilder permitKeepAliveWithoutCalls(boolean permit) {
        permitKeepAliveWithoutCalls = permit;
        return this;
    }

    /**
     * 提供 Transport 的特定信息给 Server
     */
    @Override
    @CheckReturnValue
    protected List<NettyServer> buildTransportServers(List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
        assertEventLoopsAndChannelType();

        ProtocolNegotiator negotiator = protocolNegotiator;
        if (negotiator == null) {
            negotiator = sslContext != null ? ProtocolNegotiators.serverTls(sslContext, this.getExecutorPool()) : ProtocolNegotiators.serverPlaintext();
        }

        List<NettyServer> transportServers = new ArrayList<>(listenAddresses.size());
        // 为每一个监听的地址构建一个 NettyServer
        for (SocketAddress listenAddress : listenAddresses) {
            NettyServer transportServer = new NettyServer(listenAddress,
                    channelFactory,
                    channelOptions,
                    childChannelOptions,
                    bossEventLoopGroupPool,
                    workerEventLoopGroupPool,
                    forceHeapBuffer,
                    negotiator,
                    streamTracerFactories,
                    getTransportTracerFactory(),
                    maxConcurrentCallsPerConnection,
                    autoFlowControl,
                    flowControlWindow,
                    maxMessageSize,
                    maxHeaderListSize,
                    keepAliveTimeInNanos,
                    keepAliveTimeoutInNanos,
                    maxConnectionIdleInNanos,
                    maxConnectionAgeInNanos,
                    maxConnectionAgeGraceInNanos,
                    permitKeepAliveWithoutCalls,
                    permitKeepAliveTimeInNanos,
                    getChannelz());
            transportServers.add(transportServer);
        }
        return Collections.unmodifiableList(transportServers);
    }

    @VisibleForTesting
    void assertEventLoopsAndChannelType() {
        boolean allProvided = channelFactory != Utils.DEFAULT_SERVER_CHANNEL_FACTORY
                && bossEventLoopGroupPool != DEFAULT_BOSS_EVENT_LOOP_GROUP_POOL
                && workerEventLoopGroupPool != DEFAULT_WORKER_EVENT_LOOP_GROUP_POOL;
        boolean nonProvided = channelFactory == Utils.DEFAULT_SERVER_CHANNEL_FACTORY
                && bossEventLoopGroupPool == DEFAULT_BOSS_EVENT_LOOP_GROUP_POOL
                && workerEventLoopGroupPool == DEFAULT_WORKER_EVENT_LOOP_GROUP_POOL;
        checkState(allProvided || nonProvided, "All of BossEventLoopGroup, WorkerEventLoopGroup and ChannelType should be provided or " + "neither should be");
    }

    /**
     * 让 Server 使用 TLS
     */
    @Override
    public NettyServerBuilder useTransportSecurity(File certChain, File privateKey) {
        try {
            sslContext = GrpcSslContexts.forServer(certChain, privateKey).build();
        } catch (SSLException e) {
            // This should likely be some other, easier to catch exception.
            throw new RuntimeException(e);
        }
        return this;
    }

    /**
     * 让 Server 使用 TLS
     */
    @Override
    public NettyServerBuilder useTransportSecurity(InputStream certChain, InputStream privateKey) {
        try {
            sslContext = GrpcSslContexts.forServer(certChain, privateKey).build();
        } catch (SSLException e) {
            // This should likely be some other, easier to catch exception.
            throw new RuntimeException(e);
        }
        return this;
    }
}
