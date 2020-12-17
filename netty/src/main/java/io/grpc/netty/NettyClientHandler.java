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
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.Attributes;
import io.grpc.InternalChannelz;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.ClientStreamListener.RpcProgress;
import io.grpc.internal.ClientTransport.PingCallback;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.Http2Ping;
import io.grpc.internal.InUseStateAggregator;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.TransportTracer;
import io.grpc.netty.GrpcHttp2HeadersUtils.GrpcHttp2ClientHeadersDecoder;
import io.grpc.netty.ListeningEncoder.ListeningStreamBufferingEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import io.perfmark.PerfMark;
import io.perfmark.Tag;

import javax.annotation.Nullable;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.netty.handler.codec.http2.DefaultHttp2LocalFlowController.DEFAULT_WINDOW_UPDATE_RATIO;
import static io.netty.util.CharsetUtil.UTF_8;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Client-side Netty handler for GRPC processing. All event handlers are executed entirely within
 * the context of the Netty Channel thread.
 * <p>
 * gRPC 请求客户端处理器,所有的事件处理都在 Netty 的上下文中处理
 */
class NettyClientHandler extends AbstractNettyHandler {

    private static final Logger logger = Logger.getLogger(NettyClientHandler.class.getName());

    /**
     * A message that simply passes through the channel without any real processing. It is useful to
     * check if buffers have been drained and test the health of the channel in a single operation.
     * <p>
     * 传递给 Channel 不需要任何处理的消息，用于在单个操作中检查 Channel 缓冲区是否耗尽和是否健康
     */
    static final Object NOOP_MESSAGE = new Object();

    /**
     * Status used when the transport has exhausted the number of streams.
     * 当 Transport 的流耗尽时的状态
     */
    private static final Status EXHAUSTED_STREAMS_STATUS = Status.UNAVAILABLE.withDescription("Stream IDs have been exhausted");

    private static final long USER_PING_PAYLOAD = 1111;

    private final Http2Connection.PropertyKey streamKey;
    private final ClientTransportLifecycleManager lifecycleManager;
    private final KeepAliveManager keepAliveManager;
    // Returns new unstarted stopwatches
    private final Supplier<Stopwatch> stopwatchFactory;
    private final TransportTracer transportTracer;
    private final Attributes eagAttributes;
    private final String authority;
    private final InUseStateAggregator<Http2Stream> inUseState = new InUseStateAggregator<Http2Stream>() {
        @Override
        protected void handleInUse() {
            lifecycleManager.notifyInUse(true);
        }

        @Override
        protected void handleNotInUse() {
            lifecycleManager.notifyInUse(false);
        }
    };

    private WriteQueue clientWriteQueue;
    private Http2Ping ping;
    private Attributes attributes;
    private InternalChannelz.Security securityInfo;

    static NettyClientHandler newHandler(ClientTransportLifecycleManager lifecycleManager,
                                         @Nullable KeepAliveManager keepAliveManager,
                                         boolean autoFlowControl,
                                         int flowControlWindow,
                                         int maxHeaderListSize,
                                         Supplier<Stopwatch> stopwatchFactory,
                                         Runnable tooManyPingsRunnable,
                                         TransportTracer transportTracer,
                                         Attributes eagAttributes,
                                         String authority) {
        Preconditions.checkArgument(maxHeaderListSize > 0, "maxHeaderListSize must be positive");

        Http2HeadersDecoder headersDecoder = new GrpcHttp2ClientHeadersDecoder(maxHeaderListSize);
        Http2FrameReader frameReader = new DefaultHttp2FrameReader(headersDecoder);
        Http2FrameWriter frameWriter = new DefaultHttp2FrameWriter();
        Http2Connection connection = new DefaultHttp2Connection(false);
        WeightedFairQueueByteDistributor dist = new WeightedFairQueueByteDistributor(connection);
        dist.allocationQuantum(16 * 1024); // Make benchmarks fast again.
        DefaultHttp2RemoteFlowController controller = new DefaultHttp2RemoteFlowController(connection, dist);
        connection.remote().flowController(controller);

        return newHandler(connection,
                frameReader,
                frameWriter,
                lifecycleManager,
                keepAliveManager,
                autoFlowControl,
                flowControlWindow,
                maxHeaderListSize,
                stopwatchFactory,
                tooManyPingsRunnable,
                transportTracer,
                eagAttributes,
                authority);
    }

    @VisibleForTesting
    static NettyClientHandler newHandler(final Http2Connection connection,
                                         Http2FrameReader frameReader,
                                         Http2FrameWriter frameWriter,
                                         ClientTransportLifecycleManager lifecycleManager,
                                         KeepAliveManager keepAliveManager,
                                         boolean autoFlowControl,
                                         int flowControlWindow,
                                         int maxHeaderListSize,
                                         Supplier<Stopwatch> stopwatchFactory,
                                         Runnable tooManyPingsRunnable,
                                         TransportTracer transportTracer,
                                         Attributes eagAttributes,
                                         String authority) {
        Preconditions.checkNotNull(connection, "connection");
        Preconditions.checkNotNull(frameReader, "frameReader");
        Preconditions.checkNotNull(lifecycleManager, "lifecycleManager");
        Preconditions.checkArgument(flowControlWindow > 0, "flowControlWindow must be positive");
        Preconditions.checkArgument(maxHeaderListSize > 0, "maxHeaderListSize must be positive");
        Preconditions.checkNotNull(stopwatchFactory, "stopwatchFactory");
        Preconditions.checkNotNull(tooManyPingsRunnable, "tooManyPingsRunnable");
        Preconditions.checkNotNull(eagAttributes, "eagAttributes");
        Preconditions.checkNotNull(authority, "authority");

        Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, NettyClientHandler.class);
        frameReader = new Http2InboundFrameLogger(frameReader, frameLogger);
        frameWriter = new Http2OutboundFrameLogger(frameWriter, frameLogger);

        StreamBufferingEncoder encoder = new ListeningStreamBufferingEncoder(new DefaultHttp2ConnectionEncoder(connection, frameWriter));

        // Create the local flow controller configured to auto-refill the connection window.
        connection.local().flowController(new DefaultHttp2LocalFlowController(connection, DEFAULT_WINDOW_UPDATE_RATIO, true));

        Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(connection, encoder, frameReader);

        transportTracer.setFlowControlWindowReader(new TransportTracer.FlowControlReader() {
            final Http2FlowController local = connection.local().flowController();
            final Http2FlowController remote = connection.remote().flowController();

            @Override
            public TransportTracer.FlowControlWindows read() {
                return new TransportTracer.FlowControlWindows(local.windowSize(connection.connectionStream()),
                        remote.windowSize(connection.connectionStream()));
            }
        });

        Http2Settings settings = new Http2Settings();
        settings.pushEnabled(false);
        settings.initialWindowSize(flowControlWindow);
        settings.maxConcurrentStreams(0);
        settings.maxHeaderListSize(maxHeaderListSize);

        return new NettyClientHandler(decoder,
                encoder,
                settings,
                lifecycleManager,
                keepAliveManager,
                stopwatchFactory,
                tooManyPingsRunnable,
                transportTracer,
                eagAttributes,
                authority,
                autoFlowControl);
    }

    private NettyClientHandler(Http2ConnectionDecoder decoder,
                               Http2ConnectionEncoder encoder,
                               Http2Settings settings,
                               ClientTransportLifecycleManager lifecycleManager,
                               KeepAliveManager keepAliveManager,
                               Supplier<Stopwatch> stopwatchFactory,
                               final Runnable tooManyPingsRunnable,
                               TransportTracer transportTracer,
                               Attributes eagAttributes,
                               String authority,
                               boolean autoFlowControl) {
        super(/* channelUnused= */ null, decoder, encoder, settings, autoFlowControl);
        this.lifecycleManager = lifecycleManager;
        this.keepAliveManager = keepAliveManager;
        this.stopwatchFactory = stopwatchFactory;
        this.transportTracer = Preconditions.checkNotNull(transportTracer);
        this.eagAttributes = eagAttributes;
        this.authority = authority;
        this.attributes = Attributes.newBuilder()
                                    .set(GrpcAttributes.ATTR_CLIENT_EAG_ATTRS, eagAttributes).build();

        // Set the frame listener on the decoder.
        // 创建帧监听器
        decoder().frameListener(new FrameListener());

        Http2Connection connection = encoder.connection();
        streamKey = connection.newKey();

        // 连接监听器
        connection.addListener(new Http2ConnectionAdapter() {
            /**
             * 当接收到 GOAWAY 时
             */
            @Override
            public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
                byte[] debugDataBytes = ByteBufUtil.getBytes(debugData);
                // 使用获取到的状态处理 GOAWAY，将已知流之后的所有流都关闭
                goingAway(statusFromGoAway(errorCode, debugDataBytes));
                if (errorCode == Http2Error.ENHANCE_YOUR_CALM.code()) {
                    String data = new String(debugDataBytes, UTF_8);
                    logger.log(Level.WARNING, "Received GOAWAY with ENHANCE_YOUR_CALM. Debug data: {0}", data);
                    if ("too_many_pings".equals(data)) {
                        tooManyPingsRunnable.run();
                    }
                }
            }

            /**
             * 当流状态变为活跃时调用
             */
            @Override
            public void onStreamActive(Http2Stream stream) {
                if (connection().numActiveStreams() == 1 && NettyClientHandler.this.keepAliveManager != null) {
                    NettyClientHandler.this.keepAliveManager.onTransportActive();
                }
            }

            /**
             * 流关闭时调用
             */
            @Override
            public void onStreamClosed(Http2Stream stream) {
                // Although streams with CALL_OPTIONS_RPC_OWNED_BY_BALANCER are not marked as "in-use" in
                // the first place, we don't propagate that option here, and it's safe to reset the in-use
                // state for them, which will be a cheap no-op.
                // 更新使用状态
                inUseState.updateObjectInUse(stream, false);
                // 如果没有活跃的流，则 Transport 进入 IDLE 模式
                if (connection().numActiveStreams() == 0 && NettyClientHandler.this.keepAliveManager != null) {
                    NettyClientHandler.this.keepAliveManager.onTransportIdle();
                }
            }
        });
    }

    /**
     * The protocol negotiation attributes, available once the protocol negotiation completes;
     * otherwise returns {@code Attributes.EMPTY}.
     * 协议谈判属性，当协议谈判完成时可用，除此之外，返回 Attributes.EMPTY
     */
    Attributes getAttributes() {
        return attributes;
    }

    /**
     * Handler for commands sent from the stream.
     * 从流发送的命令处理
     */
    @Override
    public void write(ChannelHandlerContext ctx,
                      Object msg,
                      ChannelPromise promise) throws Exception {
        if (msg instanceof CreateStreamCommand) {
            // 创建流
            createStream((CreateStreamCommand) msg, promise);
        } else if (msg instanceof SendGrpcFrameCommand) {
            // 发送 gRPC 帧
            sendGrpcFrame(ctx, (SendGrpcFrameCommand) msg, promise);
        } else if (msg instanceof CancelClientStreamCommand) {
            // 取消流
            cancelStream(ctx, (CancelClientStreamCommand) msg, promise);
        } else if (msg instanceof SendPingCommand) {
            // 发送 ping 帧
            sendPingFrame(ctx, (SendPingCommand) msg, promise);
        } else if (msg instanceof GracefulCloseCommand) {
            // 优雅关闭
            gracefulClose(ctx, (GracefulCloseCommand) msg, promise);
        } else if (msg instanceof ForcefulCloseCommand) {
            // 强制关闭
            forcefulClose(ctx, (ForcefulCloseCommand) msg, promise);
        } else if (msg == NOOP_MESSAGE) {
            // 无操作，则写入空缓冲
            ctx.write(Unpooled.EMPTY_BUFFER, promise);
        } else {
            throw new AssertionError("Write called for unexpected type: " + msg.getClass().getName());
        }
    }

    void startWriteQueue(Channel channel) {
        clientWriteQueue = new WriteQueue(channel);
    }

    WriteQueue getWriteQueue() {
        return clientWriteQueue;
    }

    ClientTransportLifecycleManager getLifecycleManager() {
        return lifecycleManager;
    }

    /**
     * Returns the given processed bytes back to inbound flow control.
     */
    void returnProcessedBytes(Http2Stream stream, int bytes) {
        try {
            decoder().flowController().consumeBytes(stream, bytes);
        } catch (Http2Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 处理 Header 读取
     */
    private void onHeadersRead(int streamId, Http2Headers headers, boolean endStream) {
        // Stream 1 is reserved for the Upgrade response, so we should ignore its headers here:
        // streamId 为 1 代表升级的响应，应当忽略
        if (streamId != Http2CodecUtil.HTTP_UPGRADE_STREAM_ID) {
            // 获取流
            NettyClientStream.TransportState stream = clientStream(requireHttp2Stream(streamId));
            PerfMark.event("NettyClientHandler.onHeadersRead", stream.tag());
            // 处理 header
            stream.transportHeadersReceived(headers, endStream);
        }

        if (keepAliveManager != null) {
            keepAliveManager.onDataReceived();
        }
    }

    /**
     * Handler for an inbound HTTP/2 DATA frame.
     * 处理入站的 HTTP/2 数据帧
     */
    private void onDataRead(int streamId, ByteBuf data, int padding, boolean endOfStream) {
        flowControlPing().onDataRead(data.readableBytes(), padding);
        // 根据流 ID 获取 Netty 流
        NettyClientStream.TransportState stream = clientStream(requireHttp2Stream(streamId));
        PerfMark.event("NettyClientHandler.onDataRead", stream.tag());
        stream.transportDataReceived(data, endOfStream);
        if (keepAliveManager != null) {
            keepAliveManager.onDataReceived();
        }
    }

    /**
     * Handler for an inbound HTTP/2 RST_STREAM frame, terminating a stream.
     * 当接收到 RST_STREAM 时调用，终止流
     */
    private void onRstStreamRead(int streamId, long errorCode) {
        // 根据流 ID 获取流
        NettyClientStream.TransportState stream = clientStream(connection().stream(streamId));
        if (stream != null) {
            PerfMark.event("NettyClientHandler.onRstStreamRead", stream.tag());
            // 根据 errorCode 获取状态
            Status status = GrpcUtil.Http2Error.statusForCode((int) errorCode)
                                               .augmentDescription("Received Rst Stream");
            // 修改流状态，上报给应用层
            stream.transportReportStatus(status,
                    errorCode == Http2Error.REFUSED_STREAM.code() ? RpcProgress.REFUSED : RpcProgress.PROCESSED,
                    false /*stop delivery*/,
                    new Metadata());
            if (keepAliveManager != null) {
                keepAliveManager.onDataReceived();
            }
        }
    }

    /**
     * 关闭
     */
    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        logger.fine("Network channel being closed by the application.");
        // 如果 Channel 活跃，则使用 UNAVAILABLE 状态关闭
        if (ctx.channel().isActive()) { // Ignore notification that the socket was closed
            lifecycleManager.notifyShutdown(Status.UNAVAILABLE.withDescription("Transport closed for unknown reason"));
        }
        super.close(ctx, promise);
    }

    /**
     * Handler for the Channel shutting down.
     * 关闭 Channel 时的处理器
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            logger.fine("Network channel is closed");
            // 使用 UNAVAILABLE 状态通知关闭
            Status status = Status.UNAVAILABLE.withDescription("Network closed for unknown reason");
            lifecycleManager.notifyShutdown(status);
            try {
                // 取消 ping
                cancelPing(lifecycleManager.getShutdownThrowable());
                // Report status to the application layer for any open streams
                // 对所有打开的流通知状态
                connection().forEachActiveStream(new Http2StreamVisitor() {
                    @Override
                    public boolean visit(Http2Stream stream) throws Http2Exception {
                        NettyClientStream.TransportState clientStream = clientStream(stream);
                        if (clientStream != null) {
                            clientStream.transportReportStatus(lifecycleManager.getShutdownStatus(), false, new Metadata());
                        }
                        return true;
                    }
                });
            } finally {
                // 通知 Transport 终止
                lifecycleManager.notifyTerminated(status);
            }
        } finally {
            // Close any open streams
            super.channelInactive(ctx);
            if (keepAliveManager != null) {
                // 通知 keep alive 管理器 Transport 终止
                keepAliveManager.onTransportTermination();
            }
        }
    }

    /**
     * 处理协议谈判完成
     */
    @Override
    public void handleProtocolNegotiationCompleted(Attributes attributes,
                                                   InternalChannelz.Security securityInfo) {
        this.attributes = this.attributes.toBuilder().setAll(attributes).build();
        this.securityInfo = securityInfo;
        super.handleProtocolNegotiationCompleted(attributes, securityInfo);
        writeBufferingAndRemove(ctx().channel());
    }

    /**
     * 写入缓冲并移除
     */
    static void writeBufferingAndRemove(Channel channel) {
        checkNotNull(channel, "channel");
        ChannelHandlerContext handlerCtx = channel.pipeline().context(WriteBufferingAndExceptionHandler.class);
        if (handlerCtx == null) {
            return;
        }
        ((WriteBufferingAndExceptionHandler) handlerCtx.handler()).writeBufferedAndRemove(handlerCtx);
    }

    @Override
    public Attributes getEagAttributes() {
        return eagAttributes;
    }

    @Override
    public String getAuthority() {
        return authority;
    }

    InternalChannelz.Security getSecurityInfo() {
        return securityInfo;
    }

    /**
     * 连接失败
     */
    @Override
    protected void onConnectionError(ChannelHandlerContext ctx,
                                     boolean outbound,
                                     Throwable cause,
                                     Http2Exception http2Ex) {
        logger.log(Level.FINE, "Caught a connection error", cause);
        lifecycleManager.notifyShutdown(Utils.statusFromThrowable(cause));
        // Parent class will shut down the Channel
        super.onConnectionError(ctx, outbound, cause, http2Ex);
    }

    /**
     * 流错误事件
     */
    @Override
    protected void onStreamError(ChannelHandlerContext ctx,
                                 boolean outbound,
                                 Throwable cause,
                                 Http2Exception.StreamException http2Ex) {
        // Close the stream with a status that contains the cause.
        NettyClientStream.TransportState stream = clientStream(connection().stream(http2Ex.streamId()));
        if (stream != null) {
            // 上报流的状态
            stream.transportReportStatus(Utils.statusFromThrowable(cause), false, new Metadata());
        } else {
            logger.log(Level.FINE, "Stream error for unknown stream " + http2Ex.streamId(), cause);
        }

        // Delegate to the base class to send a RST_STREAM.
        // 发送 RST_STREAM
        super.onStreamError(ctx, outbound, cause, http2Ex);
    }

    /**
     * 检查优雅关闭是否完成
     */
    @Override
    protected boolean isGracefulShutdownComplete() {
        // Only allow graceful shutdown to complete after all pending streams have completed.
        return super.isGracefulShutdownComplete() && ((StreamBufferingEncoder) encoder()).numBufferedStreams() == 0;
    }

    /**
     * Attempts to create a new stream from the given command. If there are too many active streams,
     * the creation request is queued.
     * 尝试使用所给的指令创建一个新的流，如果有很多活跃的流，则创建请求被放入队列等待
     */
    private void createStream(CreateStreamCommand command, ChannelPromise promise) throws Exception {
        // 如果在关闭中，则标记为不存在，并拒绝请求
        if (lifecycleManager.getShutdownThrowable() != null) {
            command.stream().setNonExistent();
            // The connection is going away (it is really the GOAWAY case),
            // just terminate the stream now.
            command.stream()
                   .transportReportStatus(lifecycleManager.getShutdownStatus(), RpcProgress.REFUSED, true, new Metadata());
            promise.setFailure(lifecycleManager.getShutdownThrowable());
            return;
        }

        // Get the stream ID for the new stream.
        int streamId;
        try {
            // 获取 ID
            streamId = incrementAndGetNextStreamId();
        } catch (StatusException e) {
            command.stream().setNonExistent();
            // Stream IDs have been exhausted for this connection. Fail the promise immediately.
            promise.setFailure(e);

            // Initiate a graceful shutdown if we haven't already.
            if (!connection().goAwaySent()) {
                logger.fine("Stream IDs have been exhausted for this connection. "
                        + "Initiating graceful shutdown of the connection.");
                lifecycleManager.notifyShutdown(e.getStatus());
                close(ctx(), ctx().newPromise());
            }
            return;
        }

        // 获取流
        NettyClientStream.TransportState stream = command.stream();
        // 获取 header
        Http2Headers headers = command.headers();
        // 为流设置 ID
        stream.setId(streamId);

        PerfMark.startTask("NettyClientHandler.createStream", stream.tag());
        PerfMark.linkIn(command.getLink());
        try {
            // 创建可跟踪的流，并发送流 header
            createStreamTraced(streamId,
                    stream,
                    headers,
                    command.isGet(),
                    command.shouldBeCountedForInUse(),
                    promise);
        } finally {
            PerfMark.stopTask("NettyClientHandler.createStream", stream.tag());
        }
    }

    /**
     * 创建可跟踪的流，并发送流 header
     */
    private void createStreamTraced(final int streamId,
                                    final NettyClientStream.TransportState stream,
                                    final Http2Headers headers,
                                    boolean isGet,
                                    final boolean shouldBeCountedForInUse,
                                    final ChannelPromise promise) {
        // Create an intermediate promise so that we can intercept the failure reported back to the
        // application.
        // 创建一个中间的 promise，用于拦截失败并返回给应用层
        ChannelPromise tempPromise = ctx().newPromise();
        // 将 header 帧加入等待队列，并设置监听器
        encoder().writeHeaders(ctx(), streamId, headers, 0, isGet, tempPromise)
                 .addListener(new ChannelFutureListener() {
                     @Override
                     public void operationComplete(ChannelFuture future) throws Exception {
                         // 如果操作成功
                         if (future.isSuccess()) {
                             // The http2Stream will be null in case a stream buffered in the encoder
                             // was canceled via RST_STREAM.
                             // 根据流 ID 获取流
                             Http2Stream http2Stream = connection().stream(streamId);
                             if (http2Stream != null) {
                                 // 发送 header
                                 stream.getStatsTraceContext().clientOutboundHeaders();
                                 http2Stream.setProperty(streamKey, stream);

                                 // This delays the in-use state until the I/O completes, which technically may
                                 // be later than we would like.
                                 // 更新使用中状态
                                 if (shouldBeCountedForInUse) {
                                     inUseState.updateObjectInUse(http2Stream, true);
                                 }

                                 // Attach the client stream to the HTTP/2 stream object as user data.
                                 stream.setHttp2Stream(http2Stream);
                             }
                             // Otherwise, the stream has been cancelled and Netty is sending a
                             // RST_STREAM frame which causes it to purge pending writes from the
                             // flow-controller and delete the http2Stream. The stream listener has already
                             // been notified of cancellation so there is nothing to do.

                             // Just forward on the success status to the original promise.
                             promise.setSuccess();
                         } else {
                             // 如果操作失败则关闭流
                             final Throwable cause = future.cause();
                             if (cause instanceof StreamBufferingEncoder.Http2GoAwayException) {
                                 StreamBufferingEncoder.Http2GoAwayException e = (StreamBufferingEncoder.Http2GoAwayException) cause;
                                 lifecycleManager.notifyShutdown(statusFromGoAway(e.errorCode(), e.debugData()));
                                 promise.setFailure(lifecycleManager.getShutdownThrowable());
                             } else {
                                 promise.setFailure(cause);
                             }
                         }
                     }
                 });
    }

    /**
     * Cancels this stream.
     * 取消流
     */
    private void cancelStream(ChannelHandlerContext ctx,
                              CancelClientStreamCommand cmd,
                              ChannelPromise promise) {
        NettyClientStream.TransportState stream = cmd.stream();
        PerfMark.startTask("NettyClientHandler.cancelStream", stream.tag());
        PerfMark.linkIn(cmd.getLink());
        try {
            Status reason = cmd.reason();
            if (reason != null) {
                // 通知流取消，关闭监听器
                stream.transportReportStatus(reason, true, new Metadata());
            }
            // 如果流存在，则向 server 端发送 RST_STREAM
            if (!cmd.stream().isNonExistent()) {
                encoder().writeRstStream(ctx, stream.id(), Http2Error.CANCEL.code(), promise);
            } else {
                promise.setSuccess();
            }
        } finally {
            PerfMark.stopTask("NettyClientHandler.cancelStream", stream.tag());
        }
    }

    /**
     * Sends the given GRPC frame for the stream.
     * 发送流的 gRPC 帧
     */
    private void sendGrpcFrame(ChannelHandlerContext ctx,
                               SendGrpcFrameCommand cmd,
                               ChannelPromise promise) {
        PerfMark.startTask("NettyClientHandler.sendGrpcFrame", cmd.stream().tag());
        PerfMark.linkIn(cmd.getLink());
        try {
            // Call the base class to write the HTTP/2 DATA frame.
            // Note: no need to flush since this is handled by the outbound flow controller.
            // 写入数据
            encoder().writeData(ctx, cmd.stream().id(), cmd.content(), 0, cmd.endStream(), promise);
        } finally {
            PerfMark.stopTask("NettyClientHandler.sendGrpcFrame", cmd.stream().tag());
        }
    }

    /**
     * 发送 ping
     */
    private void sendPingFrame(ChannelHandlerContext ctx,
                               SendPingCommand msg,
                               ChannelPromise promise) {
        PerfMark.startTask("NettyClientHandler.sendPingFrame");
        PerfMark.linkIn(msg.getLink());
        try {
            sendPingFrameTraced(ctx, msg, promise);
        } finally {
            PerfMark.stopTask("NettyClientHandler.sendPingFrame");
        }
    }

    /**
     * Sends a PING frame. If a ping operation is already outstanding, the callback in the message is
     * registered to be called when the existing operation completes, and no new frame is sent.
     * 发送 PING 帧，如果已经有 ping 操作出站，现有操作完成后，将注册消息中的回调以进行调用，发送新的帧
     */
    private void sendPingFrameTraced(ChannelHandlerContext ctx,
                                     SendPingCommand msg,
                                     ChannelPromise promise) {
        // Don't check lifecycleManager.getShutdownStatus() since we want to allow pings after shutdown
        // but before termination. After termination, messages will no longer arrive because the
        // pipeline clears all handlers on channel close.

        PingCallback callback = msg.callback();
        Executor executor = msg.executor();
        // we only allow one outstanding ping at a time, so just add the callback to
        // any outstanding operation
        // 同一时间只允许有一个出站的回调，将回调加入出站的操作中
        if (ping != null) {
            promise.setSuccess();
            ping.addCallback(callback, executor);
            return;
        }

        // Use a new promise to prevent calling the callback twice on write failure: here and in
        // NettyClientTransport.ping(). It may appear strange, but it will behave the same as if
        // ping != null above.
        promise.setSuccess();
        promise = ctx().newPromise();
        // set outstanding operation
        long data = USER_PING_PAYLOAD;
        Stopwatch stopwatch = stopwatchFactory.get();
        stopwatch.start();
        // 创建 HTTP ping
        ping = new Http2Ping(data, stopwatch);
        ping.addCallback(callback, executor);
        // and then write the ping
        // 写入 PING
        encoder().writePing(ctx, false, USER_PING_PAYLOAD, promise);
        ctx.flush();
        final Http2Ping finalPing = ping;
        // 添加监听器
        promise.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                // 如果成功则上报 keep alive
                if (future.isSuccess()) {
                    transportTracer.reportKeepAliveSent();
                } else {
                    // 失败则将 ping 状态改为失败
                    Throwable cause = future.cause();
                    if (cause instanceof ClosedChannelException) {
                        cause = lifecycleManager.getShutdownThrowable();
                        if (cause == null) {
                            cause = Status.UNKNOWN.withDescription("Ping failed but for unknown reason.")
                                                  .withCause(future.cause()).asException();
                        }
                    }
                    finalPing.failed(cause);
                    if (ping == finalPing) {
                        ping = null;
                    }
                }
            }
        });
    }

    /**
     * 优雅关闭流
     */
    private void gracefulClose(ChannelHandlerContext ctx,
                               GracefulCloseCommand msg,
                               ChannelPromise promise) throws Exception {
        lifecycleManager.notifyShutdown(msg.getStatus());
        // Explicitly flush to create any buffered streams before sending GOAWAY.
        // TODO(ejona): determine if the need to flush is a bug in Netty
        // 清空缓冲区
        flush(ctx);
        // 关闭流
        close(ctx, promise);
    }

    /**
     * 强制关闭流
     */
    private void forcefulClose(final ChannelHandlerContext ctx,
                               final ForcefulCloseCommand msg,
                               ChannelPromise promise) throws Exception {
        // close() already called by NettyClientTransport, so just need to clean up streams
        // 已经在 NettyClientTransport 中调用了 close()，所以仅需要清理流
        connection().forEachActiveStream(new Http2StreamVisitor() {
            @Override
            public boolean visit(Http2Stream stream) throws Http2Exception {
                NettyClientStream.TransportState clientStream = clientStream(stream);
                Tag tag = clientStream != null ? clientStream.tag() : PerfMark.createTag();
                PerfMark.startTask("NettyClientHandler.forcefulClose", tag);
                PerfMark.linkIn(msg.getLink());
                try {
                    // 如果流存在，修改状态并关闭
                    if (clientStream != null) {
                        clientStream.transportReportStatus(msg.getStatus(), true, new Metadata());
                        // 重置流
                        resetStream(ctx, stream.id(), Http2Error.CANCEL.code(), ctx.newPromise());
                    }
                    // 关闭
                    stream.close();
                    return true;
                } finally {
                    PerfMark.stopTask("NettyClientHandler.forcefulClose", tag);
                }
            }
        });
        promise.setSuccess();
    }

    /**
     * Handler for a GOAWAY being received. Fails any streams created after the
     * last known stream. May only be called during a read.
     * <p>
     * GOAWAY 处理器，将已知的流之后的所有流都失败，可能只有在读取期间会被调用
     */
    private void goingAway(Status status) {
        // 通知优雅关闭
        lifecycleManager.notifyGracefulShutdown(status);
        // Try to allocate as many in-flight streams as possible, to reduce race window of
        // https://github.com/grpc/grpc-java/issues/2562 . To be of any help, the server has to
        // gracefully shut down the connection with two GOAWAYs. gRPC servers generally send a PING
        // after the first GOAWAY, so they can very precisely detect when the GOAWAY has been
        // processed and thus this processing must be in-line before processing additional reads.

        // This can cause reentrancy, but should be minor since it is normal to handle writes in
        // response to a read. Also, the call stack is rather shallow at this point
        // 清空等待的流
        clientWriteQueue.drainNow();
        // 通知关闭
        lifecycleManager.notifyShutdown(status);
        // 获取状态
        final Status goAwayStatus = lifecycleManager.getShutdownStatus();
        // 获取已知的最后一个流 ID
        final int lastKnownStream = connection().local().lastStreamKnownByPeer();
        try {
            connection().forEachActiveStream(new Http2StreamVisitor() {
                /**
                 * 将已知的流之后的所有流都关闭
                 */
                @Override
                public boolean visit(Http2Stream stream) throws Http2Exception {
                    if (stream.id() > lastKnownStream) {
                        NettyClientStream.TransportState clientStream = clientStream(stream);
                        if (clientStream != null) {
                            clientStream.transportReportStatus(goAwayStatus, RpcProgress.REFUSED, false, new Metadata());
                        }
                        stream.close();
                    }
                    return true;
                }
            });
        } catch (Http2Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void cancelPing(Throwable t) {
        if (ping != null) {
            ping.failed(t);
            ping = null;
        }
    }

    /**
     * 根据错误码获取状态
     */
    private Status statusFromGoAway(long errorCode, byte[] debugData) {
        Status status = GrpcUtil.Http2Error.statusForCode((int) errorCode)
                                           .augmentDescription("Received Goaway");
        if (debugData != null && debugData.length > 0) {
            // If a debug message was provided, use it.
            String msg = new String(debugData, UTF_8);
            status = status.augmentDescription(msg);
        }
        return status;
    }

    /**
     * Gets the client stream associated to the given HTTP/2 stream object.
     */
    private NettyClientStream.TransportState clientStream(Http2Stream stream) {
        return stream == null ? null : (NettyClientStream.TransportState) stream.getProperty(streamKey);
    }

    /**
     * 获取下一个流的 ID
     */
    private int incrementAndGetNextStreamId() throws StatusException {
        int nextStreamId = connection().local().incrementAndGetNextStreamId();
        if (nextStreamId < 0) {
            logger.fine("Stream IDs have been exhausted for this connection. "
                    + "Initiating graceful shutdown of the connection.");
            throw EXHAUSTED_STREAMS_STATUS.asException();
        }
        return nextStreamId;
    }

    /**
     * 根据流 ID 获取流
     */
    private Http2Stream requireHttp2Stream(int streamId) {
        Http2Stream stream = connection().stream(streamId);
        if (stream == null) {
            // This should never happen.
            throw new AssertionError("Stream does not exist: " + streamId);
        }
        return stream;
    }

    /**
     * 帧监听器
     */
    private class FrameListener extends Http2FrameAdapter {
        private boolean firstSettings = true;

        /**
         * Settings 帧读取时调用
         */
        @Override
        public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
            if (firstSettings) {
                firstSettings = false;
                lifecycleManager.notifyReady();
            }
        }

        /**
         * 数据帧读取时调用
         */
        @Override
        public int onDataRead(ChannelHandlerContext ctx,
                              int streamId,
                              ByteBuf data,
                              int padding,
                              boolean endOfStream) throws Http2Exception {
            NettyClientHandler.this.onDataRead(streamId, data, padding, endOfStream);
            return padding;
        }

        /**
         * header 帧读取时调用
         */
        @Override
        public void onHeadersRead(ChannelHandlerContext ctx,
                                  int streamId,
                                  Http2Headers headers,
                                  int streamDependency,
                                  short weight,
                                  boolean exclusive,
                                  int padding,
                                  boolean endStream) throws Http2Exception {
            NettyClientHandler.this.onHeadersRead(streamId, headers, endStream);
        }

        /**
         * 当接收到 RST_STREAM 时调用
         */
        @Override
        public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode)
                throws Http2Exception {
            NettyClientHandler.this.onRstStreamRead(streamId, errorCode);
        }

        /**
         * 当 ack 被接收到时调用
         */
        @Override
        public void onPingAckRead(ChannelHandlerContext ctx, long ackPayload) throws Http2Exception {
            Http2Ping p = ping;
            if (ackPayload == flowControlPing().payload()) {
                // 更新窗口
                flowControlPing().updateWindow();
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, String.format("Window: %d", decoder().flowController().initialWindowSize(connection().connectionStream())));
                }
            } else if (p != null) {
                if (p.payload() == ackPayload) {
                    p.complete();
                    ping = null;
                } else {
                    logger.log(Level.WARNING, String.format("Received unexpected ping ack. Expecting %d, got %d", p.payload(), ackPayload));
                }
            } else {
                logger.warning("Received unexpected ping ack. No ping outstanding");
            }
            if (keepAliveManager != null) {
                keepAliveManager.onDataReceived();
            }
        }

        /**
         * 当 ping 被读取时调用
         */
        @Override
        public void onPingRead(ChannelHandlerContext ctx, long data) throws Http2Exception {
            if (keepAliveManager != null) {
                keepAliveManager.onDataReceived();
            }
        }
    }
}
