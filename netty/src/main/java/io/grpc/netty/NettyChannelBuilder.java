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
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.Internal;
import io.grpc.internal.AbstractManagedChannelImplBuilder;
import io.grpc.internal.AtomicBackoff;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import io.grpc.internal.TransportTracer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.internal.GrpcUtil.DEFAULT_KEEPALIVE_TIMEOUT_NANOS;
import static io.grpc.internal.GrpcUtil.KEEPALIVE_TIME_NANOS_DISABLED;

/**
 * A builder to help simplify construction of channels using the Netty transport.
 * 使用 Netty Transport 的帮助简化构建 Channel 的构建器
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1784")
@CanIgnoreReturnValue
public final class NettyChannelBuilder extends AbstractManagedChannelImplBuilder<NettyChannelBuilder> {

    // 1MiB.
    public static final int DEFAULT_FLOW_CONTROL_WINDOW = 1024 * 1024;

    private static final long AS_LARGE_AS_INFINITE = TimeUnit.DAYS.toNanos(1000L);

    private static final ChannelFactory<? extends Channel> DEFAULT_CHANNEL_FACTORY = new ReflectiveChannelFactory<>(Utils.DEFAULT_CLIENT_CHANNEL_TYPE);

    private static final ObjectPool<? extends EventLoopGroup> DEFAULT_EVENT_LOOP_GROUP_POOL = SharedResourcePool.forResource(Utils.DEFAULT_WORKER_EVENT_LOOP_GROUP);

    private final Map<ChannelOption<?>, Object> channelOptions = new HashMap<>();

    private NegotiationType negotiationType = NegotiationType.TLS;
    private OverrideAuthorityChecker authorityChecker;
    private ChannelFactory<? extends Channel> channelFactory = DEFAULT_CHANNEL_FACTORY;
    private ObjectPool<? extends EventLoopGroup> eventLoopGroupPool = DEFAULT_EVENT_LOOP_GROUP_POOL;
    private SslContext sslContext;
    private boolean autoFlowControl = true;
    private int flowControlWindow = DEFAULT_FLOW_CONTROL_WINDOW;
    private int maxHeaderListSize = GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE;
    private long keepAliveTimeNanos = KEEPALIVE_TIME_NANOS_DISABLED;
    private long keepAliveTimeoutNanos = DEFAULT_KEEPALIVE_TIMEOUT_NANOS;
    private boolean keepAliveWithoutCalls;
    private ProtocolNegotiatorFactory protocolNegotiatorFactory;
    private LocalSocketPicker localSocketPicker;

    /**
     * If true, indicates that the transport may use the GET method for RPCs, and may include the
     * request body in the query params.
     * 如果是 true，则表示 Transport 可能使用 GET 方法执行请求，并且会将请求参数放在 URL 中
     */
    private final boolean useGetForSafeMethods = false;

    /**
     * Creates a new builder with the given server address. This factory method is primarily intended
     * for using Netty Channel types other than SocketChannel. {@link #forAddress(String, int)} should
     * generally be preferred over this method, since that API permits delaying DNS lookups and
     * noticing changes to DNS. If an unresolved InetSocketAddress is passed in, then it will remain
     * unresolved.
     * 使用给定的 server 地址构建， 这个工厂主要使用 Netty Channel 而不是 SocketChannel，forAddress(String, int)
     * 通常比此方法更可取，因为这个 API 允许延迟解析和监听 DNS 变化，如果有未解析的地址传入，则会将地址标记为未解析
     */
    @CheckReturnValue
    public static NettyChannelBuilder forAddress(SocketAddress serverAddress) {
        return new NettyChannelBuilder(serverAddress);
    }

    /**
     * Creates a new builder with the given host and port.
     * 根据给定的主机名和端口构建
     */
    @CheckReturnValue
    public static NettyChannelBuilder forAddress(String host, int port) {
        return new NettyChannelBuilder(host, port);
    }

    /**
     * Creates a new builder with the given target string that will be resolved by
     * {@link io.grpc.NameResolver}.
     * 使用给定的目标名称构建，会被 NameResolver 解析
     */
    @CheckReturnValue
    public static NettyChannelBuilder forTarget(String target) {
        return new NettyChannelBuilder(target);
    }

    @CheckReturnValue
    NettyChannelBuilder(String host, int port) {
        this(GrpcUtil.authorityFromHostAndPort(host, port));
    }

    @CheckReturnValue
    NettyChannelBuilder(String target) {
        super(target);
    }

    /**
     * 使用 Socket 地址构建
     */
    @CheckReturnValue
    NettyChannelBuilder(SocketAddress address) {
        super(address, getAuthorityFromAddress(address));
    }

    /**
     * 获取地址的名称
     */
    @CheckReturnValue
    private static String getAuthorityFromAddress(SocketAddress address) {
        // 如果是 InetSocketAddress 则先解析为 URI 再获取
        if (address instanceof InetSocketAddress) {
            InetSocketAddress inetAddress = (InetSocketAddress) address;
            return GrpcUtil.authorityFromHostAndPort(inetAddress.getHostString(), inetAddress.getPort());
        } else {
            // 如果不是则直接变成 String
            return address.toString();
        }
    }

    /**
     * Specifies the channel type to use, by default we use {@code EpollSocketChannel} if available,
     * otherwise using {@link NioSocketChannel}.
     * 设置使用的 Channel 的类型，如果 EpollSocketChannel 可用则默认使用，否则使用 NioSocketChannel
     *
     * <p>You either use this or {@link #channelFactory(io.netty.channel.ChannelFactory)} if your
     * {@link Channel} implementation has no no-args constructor.
     * 如果 Channel 的实现有无参的构造函数，也可以使用这个或者 channelFactory(io.netty.channel.ChannelFactory)
     *
     * <p>It's an optional parameter. If the user has not provided an Channel type or ChannelFactory
     * when the channel is built, the builder will use the default one which is static.
     * 这是个可选的参数，如果在构建 Channel 时没有提供 Channel 类型，会使用默认的
     *
     * <p>You must also provide corresponding {@link #eventLoopGroup(EventLoopGroup)}. For example,
     * {@link NioSocketChannel} must use {@link io.netty.channel.nio.NioEventLoopGroup}, otherwise
     * your application won't start.
     * <p>
     * 必须提供相应的 eventLoopGroup，如 NioSocketChannel 必须使用 NioEventLoopGroup，否则会无法启动
     */

    public NettyChannelBuilder channelType(Class<? extends Channel> channelType) {
        checkNotNull(channelType, "channelType");
        return channelFactory(new ReflectiveChannelFactory<>(channelType));
    }

    /**
     * Specifies the {@link ChannelFactory} to create {@link Channel} instances. This method is
     * usually only used if the specific {@code Channel} requires complex logic which requires
     * additional information to create the {@code Channel}. Otherwise, recommend to use {@link
     * #channelType(Class)}.
     * 指定创建 Channel 实例的 ChannelFactory，这个方法通常仅用于创建 Channel 是有复杂的信息，除此之外，
     * 建议使用 channelType
     *
     * <p>It's an optional parameter. If the user has not provided an Channel type or ChannelFactory
     * when the channel is built, the builder will use the default one which is static.
     * 这是一个可选的参数，如果构建 Channel 时用户没有提供 Channel 类型或者 ChannelFactory，构建器会使用默认的
     *
     * <p>You must also provide corresponding {@link #eventLoopGroup(EventLoopGroup)}. For example,
     * {@link NioSocketChannel} based {@link ChannelFactory} must use {@link
     * io.netty.channel.nio.NioEventLoopGroup}, otherwise your application won't start.
     * 必须提供相应的 eventLoopGroup，如 NioSocketChannel 必须使用 NioEventLoopGroup，否则会无法启动
     */
    public NettyChannelBuilder channelFactory(ChannelFactory<? extends Channel> channelFactory) {
        this.channelFactory = checkNotNull(channelFactory, "channelFactory");
        return this;
    }

    /**
     * Specifies a channel option. As the underlying channel as well as network implementation may
     * ignore this value applications should consider it a hint.
     * 设置 Channel 的选项，由于基础渠道以及网络实施可能忽略此值，因此应用程序应将其视为提示
     */
    public <T> NettyChannelBuilder withOption(ChannelOption<T> option, T value) {
        channelOptions.put(option, value);
        return this;
    }

    /**
     * Sets the negotiation type for the HTTP/2 connection.
     * 设置 HTTP 2 连接的协商器类型
     *
     * <p>Default: <code>TLS</code>
     * 默认是 TLS
     */
    public NettyChannelBuilder negotiationType(NegotiationType type) {
        negotiationType = type;
        return this;
    }

    /**
     * Provides an EventGroupLoop to be used by the netty transport.
     * 提供用于 Netty Transport 的 EventGroupLoop
     *
     * <p>It's an optional parameter. If the user has not provided an EventGroupLoop when the channel
     * is built, the builder will use the default one which is static.
     * 是一个可选的参数，如果用户在构建 Channel 时没有提供则会使用默认的
     *
     * <p>You must also provide corresponding {@link #channelType(Class)} or {@link
     * #channelFactory(ChannelFactory)} corresponding to the given {@code EventLoopGroup}. For
     * example, {@link io.netty.channel.nio.NioEventLoopGroup} requires {@link NioSocketChannel}
     * 这是一个可选的参数，如果构建 Channel 时用户没有提供 Channel 类型或者 ChannelFactory，构建器会使用默认的
     *
     * <p>The channel won't take ownership of the given EventLoopGroup. It's caller's responsibility
     * to shut it down when it's desired.
     * 必须提供相应的 eventLoopGroup，如 NioSocketChannel 必须使用 NioEventLoopGroup，否则会无法启动
     */
    public NettyChannelBuilder eventLoopGroup(@Nullable EventLoopGroup eventLoopGroup) {
        // 如果提供了 EventLoopGroup 则使用提供的构建 eventLoopGroupPool，否则使用默认的
        if (eventLoopGroup != null) {
            return eventLoopGroupPool(new FixedObjectPool<>(eventLoopGroup));
        }
        return eventLoopGroupPool(DEFAULT_EVENT_LOOP_GROUP_POOL);
    }

    NettyChannelBuilder eventLoopGroupPool(ObjectPool<? extends EventLoopGroup> eventLoopGroupPool) {
        this.eventLoopGroupPool = checkNotNull(eventLoopGroupPool, "eventLoopGroupPool");
        return this;
    }

    /**
     * SSL/TLS context to use instead of the system default. It must have been configured with {@link
     * GrpcSslContexts}, but options could have been overridden.
     * 指定 SSL/TLS 上下文代替系统默认的，必须通过 GrpcSslContexts 配置，但是选项是可以被覆盖的
     */
    public NettyChannelBuilder sslContext(SslContext sslContext) {
        if (sslContext != null) {
            checkArgument(sslContext.isClient(), "Server SSL context can not be used for client channel");
            GrpcSslContexts.ensureAlpnAndH2Enabled(sslContext.applicationProtocolNegotiator());
        }
        this.sslContext = sslContext;
        return this;
    }

    /**
     * Sets the initial flow control window in bytes. Setting initial flow control window enables auto
     * flow control tuning using bandwidth-delay product algorithm. To disable auto flow control
     * tuning, use {@link #flowControlWindow(int)}. By default, auto flow control is enabled with
     * initial flow control window size of {@link #DEFAULT_FLOW_CONTROL_WINDOW}.
     * <p>
     * 设置初始流控窗口的字节大小，设置了流控创建后会开启使用带宽延迟策略的自动流控，如果想要关闭自动流控，使用 flowControlWindow，
     * 默认情况下，流控使用 DEFAULT_FLOW_CONTROL_WINDOW 初始化并默认开启
     */
    public NettyChannelBuilder initialFlowControlWindow(int initialFlowControlWindow) {
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
     * 设置流控的窗口字节大小，会自动关闭流控，通过 initialFlowControlWindow 开启自动流控，如果没有调用，则会使用
     * DEFAULT_FLOW_CONTROL_WINDOW 自动开启流控
     */
    public NettyChannelBuilder flowControlWindow(int flowControlWindow) {
        checkArgument(flowControlWindow > 0, "flowControlWindow must be positive");
        this.flowControlWindow = flowControlWindow;
        this.autoFlowControl = false;
        return this;
    }

    /**
     * Sets the maximum size of header list allowed to be received. This is cumulative size of the
     * headers with some overhead, as defined for
     * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
     * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. The default is 8 KiB.
     * 设置允许接收的最大的 header 集合的大小，这是 header 头的累积值，默认是 8kb
     *
     * @deprecated Use {@link #maxInboundMetadataSize} instead
     */
    @Deprecated
    public NettyChannelBuilder maxHeaderListSize(int maxHeaderListSize) {
        return maxInboundMetadataSize(maxHeaderListSize);
    }

    /**
     * Sets the maximum size of metadata allowed to be received. This is cumulative size of the
     * entries with some overhead, as defined for
     * <a href="http://httpwg.org/specs/rfc7540.html#rfc.section.6.5.2">
     * HTTP/2's SETTINGS_MAX_HEADER_LIST_SIZE</a>. The default is 8 KiB.
     * 设置最大允许接收的 Metadata 的大小，这是 header 头的累积值，默认是 8kb
     *
     * @param bytes the maximum size of received metadata
     * @return this
     * @throws IllegalArgumentException if bytes is non-positive
     * @since 1.17.0
     */
    @Override
    public NettyChannelBuilder maxInboundMetadataSize(int bytes) {
        checkArgument(bytes > 0, "maxInboundMetadataSize must be > 0");
        this.maxHeaderListSize = bytes;
        return this;
    }

    /**
     * Equivalent to using {@link #negotiationType(NegotiationType)} with {@code PLAINTEXT}.
     * 等同于使用 negotiationType(NegotiationType) 方法设置  PLAINTEXT
     */
    @Override
    public NettyChannelBuilder usePlaintext() {
        negotiationType(NegotiationType.PLAINTEXT);
        return this;
    }

    /**
     * Equivalent to using {@link #negotiationType(NegotiationType)} with {@code TLS}.
     * 等同于使用 negotiationType(NegotiationType) 方法设置 TLS
     */
    @Override
    public NettyChannelBuilder useTransportSecurity() {
        negotiationType(NegotiationType.TLS);
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public NettyChannelBuilder keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
        checkArgument(keepAliveTime > 0L, "keepalive time must be positive");
        keepAliveTimeNanos = timeUnit.toNanos(keepAliveTime);
        keepAliveTimeNanos = KeepAliveManager.clampKeepAliveTimeInNanos(keepAliveTimeNanos);
        if (keepAliveTimeNanos >= AS_LARGE_AS_INFINITE) {
            // Bump keepalive time to infinite. This disables keepalive.
            keepAliveTimeNanos = KEEPALIVE_TIME_NANOS_DISABLED;
        }
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public NettyChannelBuilder keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
        checkArgument(keepAliveTimeout > 0L, "keepalive timeout must be positive");
        keepAliveTimeoutNanos = timeUnit.toNanos(keepAliveTimeout);
        keepAliveTimeoutNanos = KeepAliveManager.clampKeepAliveTimeoutInNanos(keepAliveTimeoutNanos);
        return this;
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.3.0
     */
    @Override
    public NettyChannelBuilder keepAliveWithoutCalls(boolean enable) {
        keepAliveWithoutCalls = enable;
        return this;
    }


    /**
     * If non-{@code null}, attempts to create connections bound to a local port.
     * 如果不是 null，尝试使用本地的端口创建连接
     */
    public NettyChannelBuilder localSocketPicker(@Nullable LocalSocketPicker localSocketPicker) {
        this.localSocketPicker = localSocketPicker;
        return this;
    }

    /**
     * This class is meant to be overriden with a custom implementation of
     * {@link #createSocketAddress}.  The default implementation is a no-op.
     * 这个类意味着可以使用自定义的实现重写 createSocketAddress，默认模样实现
     *
     * @since 1.16.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4917")
    public static class LocalSocketPicker {

        /**
         * Called by gRPC to pick local socket to bind to.  This may be called multiple times.
         * Subclasses are expected to override this method.
         * 由 gRPC 选择绑定本地的 socket 时调用，可能会被调用多次，希望子类重写这个方法
         *
         * @param remoteAddress the remote address to connect to.
         *                      需要连接的远程地址
         * @param attrs         the Attributes present on the {@link io.grpc.EquivalentAddressGroup} associated
         *                      with the address.
         *                      与地址关联的 EquivalentAddressGroup 的属性
         * @return a {@link SocketAddress} suitable for binding, or else {@code null}.
         * 返回绑定的 SocketAddress ，或者 null
         * @since 1.16.0
         */
        @Nullable
        public SocketAddress createSocketAddress(SocketAddress remoteAddress,
                                                 @EquivalentAddressGroup.Attr Attributes attrs) {
            return null;
        }
    }

    /**
     * 构建 Transport 工厂
     */
    @Override
    @CheckReturnValue
    @Internal
    protected ClientTransportFactory buildTransportFactory() {
        // 检查 EventLoop 和 ChannelType 要么都提供，要么都不提供
        assertEventLoopAndChannelType();

        // 用于提供协议协商控制的 Netty 处理器
        // 如果有工厂则通过工厂创建，如果没有工厂则创建 SSL 协议的处理器
        ProtocolNegotiator negotiator;
        if (protocolNegotiatorFactory != null) {
            negotiator = protocolNegotiatorFactory.buildProtocolNegotiator();
        } else {
            SslContext localSslContext = sslContext;
            if (negotiationType == NegotiationType.TLS && localSslContext == null) {
                try {
                    localSslContext = GrpcSslContexts.forClient().build();
                } catch (SSLException ex) {
                    throw new RuntimeException(ex);
                }
            }
            negotiator = createProtocolNegotiatorByType(negotiationType, localSslContext, this.getOffloadExecutorPool());
        }

        return new NettyTransportFactory(negotiator,
                channelFactory,
                channelOptions,
                eventLoopGroupPool,
                autoFlowControl,
                flowControlWindow,
                maxInboundMessageSize(),
                maxHeaderListSize,
                keepAliveTimeNanos,
                keepAliveTimeoutNanos,
                keepAliveWithoutCalls,
                transportTracerFactory,
                localSocketPicker,
                useGetForSafeMethods);
    }

    /**
     * 检查 EventLoop 和 ChannelType
     * 要么都提供，要么都不提供
     */
    @VisibleForTesting
    void assertEventLoopAndChannelType() {
        boolean bothProvided = channelFactory != DEFAULT_CHANNEL_FACTORY
                && eventLoopGroupPool != DEFAULT_EVENT_LOOP_GROUP_POOL;
        boolean nonProvided = channelFactory == DEFAULT_CHANNEL_FACTORY
                && eventLoopGroupPool == DEFAULT_EVENT_LOOP_GROUP_POOL;
        checkState(bothProvided || nonProvided,
                "Both EventLoopGroup and ChannelType should be provided or neither should be");
    }

    /**
     * 获取默认的端口
     * 普通的协议端口是 80
     * SSL 协议的端口是 443
     */
    @Override
    @CheckReturnValue
    protected int getDefaultPort() {
        switch (negotiationType) {
            case PLAINTEXT:
            case PLAINTEXT_UPGRADE:
                return GrpcUtil.DEFAULT_PORT_PLAINTEXT;
            case TLS:
                return GrpcUtil.DEFAULT_PORT_SSL;
            default:
                throw new AssertionError(negotiationType + " not handled");
        }
    }

    void overrideAuthorityChecker(@Nullable OverrideAuthorityChecker authorityChecker) {
        this.authorityChecker = authorityChecker;
    }

    /**
     * 返回协商类型返回协议谈判器
     */
    @VisibleForTesting
    @CheckReturnValue
    static ProtocolNegotiator createProtocolNegotiatorByType(NegotiationType negotiationType,
                                                             SslContext sslContext,
                                                             ObjectPool<? extends Executor> executorPool) {
        switch (negotiationType) {
            case PLAINTEXT:
                return ProtocolNegotiators.plaintext();
            case PLAINTEXT_UPGRADE:
                return ProtocolNegotiators.plaintextUpgrade();
            case TLS:
                return ProtocolNegotiators.tls(sslContext, executorPool);
            default:
                throw new IllegalArgumentException("Unsupported negotiationType: " + negotiationType);
        }
    }

    @CheckReturnValue
    interface OverrideAuthorityChecker {
        String checkAuthority(String authority);
    }

    @Override
    @CheckReturnValue
    @Internal
    protected String checkAuthority(String authority) {
        if (authorityChecker != null) {
            return authorityChecker.checkAuthority(authority);
        }
        return super.checkAuthority(authority);
    }

    void protocolNegotiatorFactory(ProtocolNegotiatorFactory protocolNegotiatorFactory) {
        this.protocolNegotiatorFactory = checkNotNull(protocolNegotiatorFactory, "protocolNegotiatorFactory");
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

    @VisibleForTesting
    NettyChannelBuilder setTransportTracerFactory(TransportTracer.Factory transportTracerFactory) {
        this.transportTracerFactory = transportTracerFactory;
        return this;
    }

    interface ProtocolNegotiatorFactory {
        /**
         * Returns a ProtocolNegotatior instance configured for this Builder. This method is called
         * during {@code ManagedChannelBuilder#build()}.
         * 返回构建器配置的协议谈判器，这个方法在 ManagedChannelBuilder#build() 中被调用
         */
        ProtocolNegotiator buildProtocolNegotiator();
    }

    /**
     * Creates Netty transports. Exposed for internal use, as it should be private.
     * 创建 Netty Transport，用于内部使用，所以是 private 的
     */
    @CheckReturnValue
    private static final class NettyTransportFactory implements ClientTransportFactory {

        private final ProtocolNegotiator protocolNegotiator;
        private final ChannelFactory<? extends Channel> channelFactory;
        private final Map<ChannelOption<?>, ?> channelOptions;
        private final ObjectPool<? extends EventLoopGroup> groupPool;
        private final EventLoopGroup group;
        private final boolean autoFlowControl;
        private final int flowControlWindow;
        private final int maxMessageSize;
        private final int maxHeaderListSize;
        private final AtomicBackoff keepAliveTimeNanos;
        private final long keepAliveTimeoutNanos;
        private final boolean keepAliveWithoutCalls;
        private final TransportTracer.Factory transportTracerFactory;
        private final LocalSocketPicker localSocketPicker;
        private final boolean useGetForSafeMethods;

        private boolean closed;

        NettyTransportFactory(ProtocolNegotiator protocolNegotiator,
                              ChannelFactory<? extends Channel> channelFactory,
                              Map<ChannelOption<?>, ?> channelOptions,
                              ObjectPool<? extends EventLoopGroup> groupPool,
                              boolean autoFlowControl,
                              int flowControlWindow,
                              int maxMessageSize,
                              int maxHeaderListSize,
                              long keepAliveTimeNanos,
                              long keepAliveTimeoutNanos,
                              boolean keepAliveWithoutCalls,
                              TransportTracer.Factory transportTracerFactory,
                              LocalSocketPicker localSocketPicker,
                              boolean useGetForSafeMethods) {
            this.protocolNegotiator = checkNotNull(protocolNegotiator, "protocolNegotiator");
            this.channelFactory = channelFactory;
            this.channelOptions = new HashMap<ChannelOption<?>, Object>(channelOptions);
            this.groupPool = groupPool;
            this.group = groupPool.getObject();
            this.autoFlowControl = autoFlowControl;
            this.flowControlWindow = flowControlWindow;
            this.maxMessageSize = maxMessageSize;
            this.maxHeaderListSize = maxHeaderListSize;
            this.keepAliveTimeNanos = new AtomicBackoff("keepalive time nanos", keepAliveTimeNanos);
            this.keepAliveTimeoutNanos = keepAliveTimeoutNanos;
            this.keepAliveWithoutCalls = keepAliveWithoutCalls;
            this.transportTracerFactory = transportTracerFactory;
            this.localSocketPicker = localSocketPicker != null ? localSocketPicker : new LocalSocketPicker();
            this.useGetForSafeMethods = useGetForSafeMethods;
        }

        /**
         * 构建新的 Transport
         *
         * @param serverAddress the address that the transport is connected to
         *                      连接的地址
         * @param options       additional configuration
         *                      附加的配置
         * @param channelLogger logger for the transport.
         * @return
         */
        @Override
        public ConnectionClientTransport newClientTransport(SocketAddress serverAddress,
                                                            ClientTransportOptions options,
                                                            ChannelLogger channelLogger) {
            checkState(!closed, "The transport factory is closed.");

            ProtocolNegotiator localNegotiator = protocolNegotiator;
            // 获取地址
            HttpConnectProxiedSocketAddress proxiedAddr = options.getHttpConnectProxiedSocketAddress();
            // 如果是代理的地址，则配置代理信息
            if (proxiedAddr != null) {
                serverAddress = proxiedAddr.getTargetAddress();
                localNegotiator = ProtocolNegotiators.httpProxy(proxiedAddr.getProxyAddress(),
                        proxiedAddr.getUsername(),
                        proxiedAddr.getPassword(),
                        protocolNegotiator);
            }

            // 存活时间
            final AtomicBackoff.State keepAliveTimeNanosState = keepAliveTimeNanos.getState();
            Runnable tooManyPingsRunnable = new Runnable() {
                @Override
                public void run() {
                    keepAliveTimeNanosState.backoff();
                }
            };

            // TODO(carl-mastrangelo): Pass channelLogger in.
            // 构建 Netty 的 Transport
            NettyClientTransport transport = new NettyClientTransport(serverAddress,
                    channelFactory,
                    channelOptions,
                    group,
                    localNegotiator,
                    autoFlowControl,
                    flowControlWindow,
                    maxMessageSize,
                    maxHeaderListSize,
                    keepAliveTimeNanosState.get(),
                    keepAliveTimeoutNanos,
                    keepAliveWithoutCalls,
                    options.getAuthority(),
                    options.getUserAgent(),
                    tooManyPingsRunnable,
                    transportTracerFactory.create(),
                    options.getEagAttributes(),
                    localSocketPicker,
                    channelLogger,
                    useGetForSafeMethods);
            return transport;
        }

        @Override
        public ScheduledExecutorService getScheduledExecutorService() {
            return group;
        }

        /**
         * 关闭 Transport
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;

            protocolNegotiator.close();
            groupPool.returnObject(group);
        }
    }
}
