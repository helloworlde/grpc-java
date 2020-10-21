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
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalLogId;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ClientStream;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.FailingClientStream;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.Http2Ping;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.KeepAliveManager.ClientKeepAlivePinger;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportTracer;
import io.grpc.netty.NettyChannelBuilder.LocalSocketPicker;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http2.StreamBufferingEncoder.Http2ChannelClosedException;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static io.grpc.internal.GrpcUtil.KEEPALIVE_TIME_NANOS_DISABLED;
import static io.netty.channel.ChannelOption.ALLOCATOR;
import static io.netty.channel.ChannelOption.SO_KEEPALIVE;

/**
 * A Netty-based {@link ConnectionClientTransport} implementation.
 * 基于 Netty 的 ConnectionClientTransport 实现
 */
class NettyClientTransport implements ConnectionClientTransport {

    /**
     * Get the existing {@link ChannelLogger} key in case a separate, isolated class loader has
     * already created {@link LOGGER_KEY}.
     * 获取 Channel 日志的 key，如果没有则创建一个
     */
    private static final AttributeKey<ChannelLogger> getOrCreateChannelLogger() {
        AttributeKey<ChannelLogger> key = AttributeKey.valueOf("channelLogger");
        if (key == null) {
            key = AttributeKey.newInstance("channelLogger");
        }
        return key;
    }

    static final AttributeKey<ChannelLogger> LOGGER_KEY = getOrCreateChannelLogger();

    private final InternalLogId logId;

    private final Map<ChannelOption<?>, ?> channelOptions;
    private final SocketAddress remoteAddress;
    private final ChannelFactory<? extends Channel> channelFactory;
    private final EventLoopGroup group;
    private final ProtocolNegotiator negotiator;

    private final String authorityString;
    private final AsciiString authority;
    private final AsciiString userAgent;

    private final boolean autoFlowControl;
    private final int flowControlWindow;
    private final int maxMessageSize;
    private final int maxHeaderListSize;

    private KeepAliveManager keepAliveManager;
    private final long keepAliveTimeNanos;
    private final long keepAliveTimeoutNanos;
    private final boolean keepAliveWithoutCalls;

    private final AsciiString negotiationScheme;
    private final Runnable tooManyPingsRunnable;

    private NettyClientHandler handler;

    // We should not send on the channel until negotiation completes. This is a hard requirement
    // by SslHandler but is appropriate for HTTP/1.1 Upgrade as well.
    // 在协商完成之前不应该向 Channel 发送请求，这是 SslHandler 的硬性要求，但也适用于 HTTP/1.1 以及更新的协议
    private Channel channel;

    /**
     * If {@link #start} has been called, non-{@code null} if channel is {@code null}.
     * 如果调用了 start，当 Channel 为 null 时不为 null
     */
    private Status statusExplainingWhyTheChannelIsNull;

    /**
     * Since not thread-safe, may only be used from event loop.
     * 不是线程安全的，仅用于 event loop
     */
    private ClientTransportLifecycleManager lifecycleManager;

    /**
     * Since not thread-safe, may only be used from event loop.
     * 不是线程安全的，仅用于 event loop
     */
    private final TransportTracer transportTracer;
    private final Attributes eagAttributes;
    private final LocalSocketPicker localSocketPicker;
    private final ChannelLogger channelLogger;
    private final boolean useGetForSafeMethods;

    NettyClientTransport(SocketAddress address,
                         ChannelFactory<? extends Channel> channelFactory,
                         Map<ChannelOption<?>, ?> channelOptions,
                         EventLoopGroup group,
                         ProtocolNegotiator negotiator,
                         boolean autoFlowControl,
                         int flowControlWindow,
                         int maxMessageSize,
                         int maxHeaderListSize,
                         long keepAliveTimeNanos,
                         long keepAliveTimeoutNanos,
                         boolean keepAliveWithoutCalls,
                         String authority,
                         @Nullable String userAgent,
                         Runnable tooManyPingsRunnable,
                         TransportTracer transportTracer,
                         Attributes eagAttributes,
                         LocalSocketPicker localSocketPicker,
                         ChannelLogger channelLogger,
                         boolean useGetForSafeMethods) {
        this.negotiator = Preconditions.checkNotNull(negotiator, "negotiator");
        this.negotiationScheme = this.negotiator.scheme();
        this.remoteAddress = Preconditions.checkNotNull(address, "address");
        this.group = Preconditions.checkNotNull(group, "group");
        this.channelFactory = channelFactory;
        this.channelOptions = Preconditions.checkNotNull(channelOptions, "channelOptions");
        this.autoFlowControl = autoFlowControl;
        this.flowControlWindow = flowControlWindow;
        this.maxMessageSize = maxMessageSize;
        this.maxHeaderListSize = maxHeaderListSize;
        this.keepAliveTimeNanos = keepAliveTimeNanos;
        this.keepAliveTimeoutNanos = keepAliveTimeoutNanos;
        this.keepAliveWithoutCalls = keepAliveWithoutCalls;
        this.authorityString = authority;
        this.authority = new AsciiString(authority);
        this.userAgent = new AsciiString(GrpcUtil.getGrpcUserAgent("netty", userAgent));
        this.tooManyPingsRunnable = Preconditions.checkNotNull(tooManyPingsRunnable, "tooManyPingsRunnable");
        this.transportTracer = Preconditions.checkNotNull(transportTracer, "transportTracer");
        this.eagAttributes = Preconditions.checkNotNull(eagAttributes, "eagAttributes");
        this.localSocketPicker = Preconditions.checkNotNull(localSocketPicker, "localSocketPicker");
        this.logId = InternalLogId.allocate(getClass(), remoteAddress.toString());
        this.channelLogger = Preconditions.checkNotNull(channelLogger, "channelLogger");
        this.useGetForSafeMethods = useGetForSafeMethods;
    }

    /**
     * 发送 ping
     *
     * @param callback ping 结果回调
     * @param executor 执行的线程池
     */
    @Override
    public void ping(final PingCallback callback, final Executor executor) {
        // 如果 Channel 是空的，则执行失败回调
        if (channel == null) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    callback.onFailure(statusExplainingWhyTheChannelIsNull.asException());
                }
            });
            return;
        }
        // The promise and listener always succeed in NettyClientHandler. So this listener handles the
        // error case, when the channel is closed and the NettyClientHandler no longer in the pipeline.
        // 在 NettyClientHandler 中这个回调永远成功，所以这个监听器处理关闭和不存在的场景
        ChannelFutureListener failureListener = new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                // 如果没有成功，则根据返回结果生成失败状态
                if (!future.isSuccess()) {
                    Status s = statusFromFailedFuture(future);
                    // 执行失败回调
                    Http2Ping.notifyFailed(callback, executor, s.asException());
                }
            }
        };
        // Write the command requesting the ping
        // 将 ping 请求添加到队列中
        handler.getWriteQueue()
               .enqueue(new SendPingCommand(callback, executor), true)
               .addListener(failureListener);
    }

    /**
     * 创建流
     *
     * @param method      调用的方法
     * @param headers     请求的Header
     * @param callOptions 调用的选项
     * @return 客户端流
     */
    @Override
    public ClientStream newStream(MethodDescriptor<?, ?> method,
                                  Metadata headers,
                                  CallOptions callOptions) {
        Preconditions.checkNotNull(method, "method");
        Preconditions.checkNotNull(headers, "headers");

        // 如果 channel 是空的，则返回失败的 ClientStream
        if (channel == null) {
            return new FailingClientStream(statusExplainingWhyTheChannelIsNull);
        }

        StatsTraceContext statsTraceCtx = StatsTraceContext.newClientContext(callOptions, getAttributes(), headers);

        // 创建 NettyClientStream
        return new NettyClientStream(
                new NettyClientStream.TransportState(handler,
                        channel.eventLoop(),
                        maxMessageSize,
                        statsTraceCtx,
                        transportTracer,
                        method.getFullMethodName()) {
                    @Override
                    protected Status statusFromFailedFuture(ChannelFuture f) {
                        return NettyClientTransport.this.statusFromFailedFuture(f);
                    }
                },
                method,
                headers,
                channel,
                authority,
                negotiationScheme,
                userAgent,
                statsTraceCtx,
                transportTracer,
                callOptions,
                useGetForSafeMethods);
    }

    /**
     * 启动 Transport
     *
     * @param transportListener 监听器
     * @return
     */
    @SuppressWarnings("unchecked")
    @Override
    public Runnable start(Listener transportListener) {
        // 创建 ClientTransportLifecycleManager
        lifecycleManager = new ClientTransportLifecycleManager(Preconditions.checkNotNull(transportListener, "listener"));

        EventLoop eventLoop = group.next();

        // 如果 keepAlive 有效，则创建 KeepAliveManager
        if (keepAliveTimeNanos != KEEPALIVE_TIME_NANOS_DISABLED) {
            keepAliveManager = new KeepAliveManager(new ClientKeepAlivePinger(this),
                    eventLoop,
                    keepAliveTimeNanos,
                    keepAliveTimeoutNanos,
                    keepAliveWithoutCalls);
        }

        // 创建 Netty Handler
        handler = NettyClientHandler.newHandler(lifecycleManager,
                keepAliveManager,
                autoFlowControl,
                flowControlWindow,
                maxHeaderListSize,
                GrpcUtil.STOPWATCH_SUPPLIER,
                tooManyPingsRunnable,
                transportTracer,
                eagAttributes,
                authorityString);

        ChannelHandler negotiationHandler = negotiator.newHandler(handler);

        // 创建 Bootstrap 并设置属性
        Bootstrap b = new Bootstrap();
        b.option(ALLOCATOR, Utils.getByteBufAllocator(false));
        b.attr(LOGGER_KEY, channelLogger);
        b.group(eventLoop);
        b.channelFactory(channelFactory);
        // For non-socket based channel, the option will be ignored.
        b.option(SO_KEEPALIVE, true);
        // For non-epoll based channel, the option will be ignored.
        if (keepAliveTimeNanos != KEEPALIVE_TIME_NANOS_DISABLED) {
            ChannelOption<Integer> tcpUserTimeout = Utils.maybeGetTcpUserTimeoutOption();
            if (tcpUserTimeout != null) {
                b.option(tcpUserTimeout, (int) TimeUnit.NANOSECONDS.toMillis(keepAliveTimeoutNanos));
            }
        }
        // 可用的 channel
        for (Map.Entry<ChannelOption<?>, ?> entry : channelOptions.entrySet()) {
            // Every entry in the map is obtained from
            // NettyChannelBuilder#withOption(ChannelOption<T> option, T value)
            // so it is safe to pass the key-value pair to b.option().
            b.option((ChannelOption<Object>) entry.getKey(), entry.getValue());
        }

        ChannelHandler bufferingHandler = new WriteBufferingAndExceptionHandler(negotiationHandler);

        /**
         * We don't use a ChannelInitializer in the client bootstrap because its "initChannel" method
         * is executed in the event loop and we need this handler to be in the pipeline immediately so
         * that it may begin buffering writes.
         *
         * 我们不在客户端引导程序中使用ChannelInitializer，因为其 initChannel 方法在事件循环中执行，
         * 并且我们需要此处理程序立即放入管道中，以便可以开始缓冲写操作
         */
        b.handler(bufferingHandler);
        ChannelFuture regFuture = b.register();
        if (regFuture.isDone() && !regFuture.isSuccess()) {
            channel = null;
            // Initialization has failed badly. All new streams should be made to fail.
            Throwable t = regFuture.cause();
            if (t == null) {
                t = new IllegalStateException("Channel is null, but future doesn't have a cause");
            }
            statusExplainingWhyTheChannelIsNull = Utils.statusFromThrowable(t);
            // Use a Runnable since lifecycleManager calls transportListener
            return new Runnable() {
                @Override
                public void run() {
                    // NOTICE: we not are calling lifecycleManager from the event loop. But there isn't really
                    // an event loop in this case, so nothing should be accessing the lifecycleManager. We
                    // could use GlobalEventExecutor (which is what regFuture would use for notifying
                    // listeners in this case), but avoiding on-demand thread creation in an error case seems
                    // a good idea and is probably clearer threading.
                    lifecycleManager.notifyTerminated(statusExplainingWhyTheChannelIsNull);
                }
            };
        }
        channel = regFuture.channel();
        // Start the write queue as soon as the channel is constructed
        // 当 Channel 创建后尽可能快的开始写入队列
        handler.startWriteQueue(channel);
        // This write will have no effect, yet it will only complete once the negotiationHandler
        // flushes any pending writes. We need it to be staged *before* the `connect` so that
        // the channel can't have been closed yet, removing all handlers. This write will sit in the
        // AbstractBufferingHandler's buffer, and will either be flushed on a successful connection,
        // or failed if the connection fails.
        // 这个写入没有任何作用，仅用于 negotiationHandler 完成一次刷新等待的写入，需要在 connect 之前执行，所以
        // channel 在移除 Handler 之前还不能被关闭，这次写入将会缓存在 AbstractBufferingHandler 中，当连接成功
        // 或失败时将会被 flush 掉
        channel.writeAndFlush(NettyClientHandler.NOOP_MESSAGE)
               .addListener(new ChannelFutureListener() {
                   @Override
                   public void operationComplete(ChannelFuture future) throws Exception {
                       if (!future.isSuccess()) {
                           // Need to notify of this failure, because NettyClientHandler may not have been added to
                           // the pipeline before the error occurred.
                           lifecycleManager.notifyTerminated(Utils.statusFromThrowable(future.cause()));
                       }
                   }
               });
        // Start the connection operation to the server.
        // 建立连接
        SocketAddress localAddress = localSocketPicker.createSocketAddress(remoteAddress, eagAttributes);
        if (localAddress != null) {
            channel.connect(remoteAddress, localAddress);
        } else {
            channel.connect(remoteAddress);
        }

        // 开始 keepAlive 监听
        if (keepAliveManager != null) {
            keepAliveManager.onTransportStarted();
        }

        return null;
    }

    /**
     * 关闭 Transport
     *
     * @param reason 关闭的状态
     */
    @Override
    public void shutdown(Status reason) {
        // start() could have failed
        if (channel == null) {
            return;
        }
        // Notifying of termination is automatically done when the channel closes.
        // 发送关闭指令
        if (channel.isOpen()) {
            handler.getWriteQueue()
                   .enqueue(new GracefulCloseCommand(reason), true);
        }
    }

    /**
     * 立即关闭 Transport
     *
     * @param reason 关闭状态
     */
    @Override
    public void shutdownNow(final Status reason) {
        // Notifying of termination is automatically done when the channel closes.
        if (channel != null && channel.isOpen()) {
            handler.getWriteQueue()
                   .enqueue(new Runnable() {
                       @Override
                       public void run() {
                           lifecycleManager.notifyShutdown(reason);
                           // Call close() directly since negotiation may not have completed, such that a write would
                           // be queued.
                           channel.close();
                           channel.write(new ForcefulCloseCommand(reason));
                       }
                   }, true);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("logId", logId.getId())
                          .add("remoteAddress", remoteAddress)
                          .add("channel", channel)
                          .toString();
    }

    @Override
    public InternalLogId getLogId() {
        return logId;
    }

    @Override
    public Attributes getAttributes() {
        return handler.getAttributes();
    }

    /**
     * 获取统计数据
     *
     * @return 统计数据
     */
    @Override
    public ListenableFuture<SocketStats> getStats() {
        final SettableFuture<SocketStats> result = SettableFuture.create();
        if (channel.eventLoop().inEventLoop()) {
            // This is necessary, otherwise we will block forever if we get the future from inside
            // the event loop.
            result.set(getStatsHelper(channel));
            return result;
        }
        channel.eventLoop()
               .submit(new Runnable() {
                   @Override
                   public void run() {
                       result.set(getStatsHelper(channel));
                   }
               })
               .addListener(new GenericFutureListener<Future<Object>>() {
                   @Override
                   public void operationComplete(Future<Object> future) throws Exception {
                       if (!future.isSuccess()) {
                           result.setException(future.cause());
                       }
                   }
               });
        return result;
    }

    private SocketStats getStatsHelper(Channel ch) {
        assert ch.eventLoop().inEventLoop();
        return new SocketStats(transportTracer.getStats(),
                channel.localAddress(),
                channel.remoteAddress(),
                Utils.getSocketOptions(ch),
                handler == null ? null : handler.getSecurityInfo());
    }

    @VisibleForTesting
    Channel channel() {
        return channel;
    }

    @VisibleForTesting
    KeepAliveManager keepAliveManager() {
        return keepAliveManager;
    }

    /**
     * Convert ChannelFuture.cause() to a Status, taking into account that all handlers are removed
     * from the pipeline when the channel is closed. Since handlers are removed, you may get an
     * unhelpful exception like ClosedChannelException.
     * 将 ChannelFuture.cause() 转为 Status，考虑到当 Channel 关闭的时候，所有的 handlers 都被移除了，
     * 移除后，可能获取到没有实际意义的 ClosedChannelException
     *
     * <p>This method must only be called on the event loop.
     * 这个方法必须在 event loop 中调用
     */
    private Status statusFromFailedFuture(ChannelFuture f) {
        Throwable t = f.cause();

        // 如果是 ClosedChannelException 或  Http2ChannelClosedException 异常
        if (t instanceof ClosedChannelException
                // Exception thrown by the StreamBufferingEncoder if the channel is closed while there
                // are still streams buffered. This exception is not helpful. Replace it by the real
                // cause of the shutdown (if available).
                || t instanceof Http2ChannelClosedException) {
            // 获取关闭状态
            Status shutdownStatus = lifecycleManager.getShutdownStatus();
            // 如果关闭状态为 null，则返回未知原因
            if (shutdownStatus == null) {
                return Status.UNKNOWN.withDescription("Channel closed but for unknown reason")
                                     .withCause(new ClosedChannelException().initCause(t));
            }
            // 有关闭状态则返回关闭状态
            return shutdownStatus;
        }
        // 将异常转换为状态
        return Utils.statusFromThrowable(t);
    }
}
