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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.Deadline;
import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.ClientStreamListener.RpcProgress;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.internal.GrpcUtil.CONTENT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.MESSAGE_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.TIMEOUT_KEY;
import static java.lang.Math.max;

/**
 * The abstract base class for {@link ClientStream} implementations. Extending classes only need to
 * implement {@link #transportState()} and {@link #abstractClientStreamSink()}. Must only be called
 * from the sending application thread.
 * ClientStream 实现的抽象基类，扩展类仅需要实现 transportState 和 abstractClientStreamSink，必须仅从发送
 * 应用程序中调用
 */
public abstract class AbstractClientStream extends AbstractStream implements ClientStream, MessageFramer.Sink {

    private static final Logger log = Logger.getLogger(AbstractClientStream.class.getName());

    /**
     * A sink for outbound operations, separated from the stream simply to avoid name
     * collisions/confusion. Only called from application thread.
     * 用于出站操作的槽，只用于分离流，避免名称混乱，只在应用线程内调用
     */
    protected interface Sink {
        /**
         * Sends the request headers to the remote end point.
         * 将请求头发送给远程端点
         *
         * @param metadata the metadata to be sent
         *                 发送的元数据
         * @param payload  the payload needs to be sent in the headers if not null. Should only be used
         *                 when sending an unary GET request
         *                 当 header 不为空时需要发送的数据，仅用于发送一元的 GET 请求
         */
        void writeHeaders(Metadata metadata, @Nullable byte[] payload);

        /**
         * Sends an outbound frame to the remote end point.
         * 将出站帧发送给远程端点
         *
         * @param frame       a buffer containing the chunk of data to be sent, or {@code null} if {@code
         *                    endOfStream} with no data to send
         *                    包含大块的要发送的数据的缓冲，如果是在流的末尾，没有数据发送的时候是 null
         * @param endOfStream {@code true} if this is the last frame; {@code flush} is guaranteed to be
         *                    {@code true} if this is {@code true}
         *                    如果是最后一帧，则是 true；如果是 true，则会调用 flush
         * @param flush       {@code true} if more data may not be arriving soon
         *                    如果后续数据不能很快到达，则是 true
         * @Param numMessages the number of messages this series of frames represents, must be >= 0.
         * 这组帧代表的消息数量，必须大于等于0
         */
        void writeFrame(@Nullable WritableBuffer frame,
                        boolean endOfStream,
                        boolean flush,
                        int numMessages);

        /**
         * Tears down the stream, typically in the event of a timeout. This method may be called
         * multiple times and from any thread.
         * 关闭流，通常是在超时的情况下，这个方法可能会被不同的线程多次调用
         *
         * <p>This is a clone of {@link ClientStream#cancel(Status)};
         * {@link AbstractClientStream#cancel} delegates to this method.
         * 这是  ClientStream#cancel(Status) 方法的克隆，代理了 AbstractClientStream#cancel 方法
         */
        void cancel(Status status);
    }

    private final TransportTracer transportTracer;
    private final Framer framer;
    private boolean shouldBeCountedForInUse;
    private boolean useGet;
    private Metadata headers;
    /**
     * Whether cancel() has been called. This is not strictly necessary, but removes the delay between
     * cancel() being called and isReady() beginning to return false, since cancel is commonly
     * processed asynchronously.
     * cancel 是否调用了，不是严格需要的，但是消除了 cancel 和 isReady 之间的延迟，尽管 cancel 通常是异步处理的
     */
    private volatile boolean cancelled;

    protected AbstractClientStream(WritableBufferAllocator bufferAllocator,
                                   StatsTraceContext statsTraceCtx,
                                   TransportTracer transportTracer,
                                   Metadata headers,
                                   CallOptions callOptions,
                                   boolean useGet) {
        checkNotNull(headers, "headers");

        this.transportTracer = checkNotNull(transportTracer, "transportTracer");
        this.shouldBeCountedForInUse = GrpcUtil.shouldBeCountedForInUse(callOptions);
        this.useGet = useGet;
        // 如果不是 GET 方法，则使用 MessageFramer，并设置 header
        if (!useGet) {
            framer = new MessageFramer(this, bufferAllocator, statsTraceCtx);
            this.headers = headers;
        } else {
            // 如果是 GET 方法则用 GetFramer
            framer = new GetFramer(headers, statsTraceCtx);
        }
    }

    /**
     * 设置请求过期时间
     *
     * @param deadline 过期时间
     */
    @Override
    public void setDeadline(Deadline deadline) {
        // 取消所有的过期时间
        headers.discardAll(TIMEOUT_KEY);
        // 计算过期时间，设置到 Header 里
        long effectiveTimeout = max(0, deadline.timeRemaining(TimeUnit.NANOSECONDS));
        headers.put(TIMEOUT_KEY, effectiveTimeout);
    }

    /**
     * 设置最大出站字节数
     */
    @Override
    public void setMaxOutboundMessageSize(int maxSize) {
        framer.setMaxOutboundMessageSize(maxSize);
    }

    /**
     * 设置最大入站字节数
     */
    @Override
    public void setMaxInboundMessageSize(int maxSize) {
        transportState().setMaxInboundMessageSize(maxSize);
    }

    /**
     * 开启整个流的解压，允许客户端流使用 GzipInflatingBuffer 解压传入的 GZIP 压缩流
     */
    @Override
    public final void setFullStreamDecompression(boolean fullStreamDecompression) {
        transportState().setFullStreamDecompression(fullStreamDecompression);
    }

    /**
     * 设置注册表以查找framer的解压器
     */
    @Override
    public final void setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
        transportState().setDecompressorRegistry(decompressorRegistry);
    }

    /**
     * 记录这个流对应的 Transport 的状态
     * {@inheritDoc}
     */
    @Override
    protected abstract TransportState transportState();

    /**
     * 开始一个流，
     *
     * @param listener non-{@code null} listener of stream events
     */
    @Override
    public final void start(ClientStreamListener listener) {
        transportState().setListener(listener);
        // 如果不是 GET 请求，则发送 Header
        if (!useGet) {
            abstractClientStreamSink().writeHeaders(headers, null);
            headers = null;
        }
    }

    /**
     * Sink for transport to be called to perform outbound operations. Each stream must have its own
     * unique sink.
     * 用于 Transport 出站操作的槽，每个流必须有自己唯一的槽
     */
    protected abstract Sink abstractClientStreamSink();

    /**
     * 用于发送消息的 framer
     */
    @Override
    protected final Framer framer() {
        return framer;
    }

    /**
     * Returns true if this stream should be counted when determining the in-use state of the
     * transport.
     * 当统计使用中状态时返回流是否应该被统计
     */
    public final boolean shouldBeCountedForInUse() {
        return shouldBeCountedForInUse;
    }

    /**
     * 将 framer 传递给 Transport
     *
     * @param frame       a non-empty buffer to deliver or {@code null} if the framer is being
     *                    closed and there is no data to deliver.
     *                    用于传递的非空的 buffer，如果 framer 已经被关闭，或者没有数据传递，则是 null
     * @param endOfStream whether the frame is the last one for the GRPC stream
     *                    是否是 gRPC 流的最后一帧
     * @param flush       {@code true} if more data may not be arriving soon
     *                    如果没有更多的数据，则使用 flush
     * @param numMessages the number of messages that this series of frames represents
     *                    代表当前帧代表的消息的数量
     */
    @Override
    public final void deliverFrame(WritableBuffer frame,
                                   boolean endOfStream,
                                   boolean flush,
                                   int numMessages) {
        Preconditions.checkArgument(frame != null || endOfStream, "null frame before EOS");
        // 通过 netty 写入
        abstractClientStreamSink().writeFrame(frame, endOfStream, flush, numMessages);
    }

    /**
     * 客户端关闭流
     */
    @Override
    public final void halfClose() {
        if (!transportState().isOutboundClosed()) {
            transportState().setOutboundClosed();
            // 输出已经到达消息结尾
            endOfMessages();
        }
    }

    /**
     * 取消流
     *
     * @param reason must be non-OK
     */
    @Override
    public final void cancel(Status reason) {
        Preconditions.checkArgument(!reason.isOk(), "Should not cancel with OK status");
        cancelled = true;
        abstractClientStreamSink().cancel(reason);
    }

    /**
     * 返回流是否 ready
     */
    @Override
    public final boolean isReady() {
        return super.isReady() && !cancelled;
    }

    /**
     * 设置超时观察器
     */
    @Override
    public final void appendTimeoutInsight(InsightBuilder insight) {
        Attributes attrs = getAttributes();
        insight.appendKeyValue("remote_addr", attrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
    }

    /**
     * 获取 Transport 跟踪器
     */
    protected TransportTracer getTransportTracer() {
        return transportTracer;
    }

    /**
     * This should only called from the transport thread.
     * Transport 中的流状态，仅应当被 Transport 线程调用
     */
    protected abstract static class TransportState extends AbstractStream.TransportState {
        private final StatsTraceContext statsTraceCtx;
        /**
         * Whether listener.closed() has been called.
         * listener.closed() 是否被调用
         */
        private boolean listenerClosed;
        private ClientStreamListener listener;
        private boolean fullStreamDecompression;
        // 解压缩器注册中心
        private DecompressorRegistry decompressorRegistry = DecompressorRegistry.getDefaultInstance();

        private boolean deframerClosed = false;
        private Runnable deframerClosedTask;

        /**
         * Whether the client has half-closed the stream.
         * 客户端是否半关闭流
         */
        private volatile boolean outboundClosed;

        /**
         * Whether the stream is closed from the transport's perspective. This can differ from {@link
         * #listenerClosed} because there may still be messages buffered to deliver to the application.
         * 从 Transport 角度看流是否关闭，和 listenerClosed 的结果可能不同，因为依然有需要投递的缓冲消息
         */
        private boolean statusReported;
        /**
         * True if the status reported (set via {@link #transportReportStatus}) is OK.
         * 如果 transportReportStatus 是 OK 则是 true
         */
        private boolean statusReportedIsOk;

        protected TransportState(int maxMessageSize,
                                 StatsTraceContext statsTraceCtx,
                                 TransportTracer transportTracer) {
            super(maxMessageSize, statsTraceCtx, transportTracer);
            this.statsTraceCtx = checkNotNull(statsTraceCtx, "statsTraceCtx");
        }

        /**
         * 整个流是否都压缩
         */
        private void setFullStreamDecompression(boolean fullStreamDecompression) {
            this.fullStreamDecompression = fullStreamDecompression;
        }

        /**
         * 设置流解压缩注册器
         */
        private void setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
            checkState(this.listener == null, "Already called start");
            this.decompressorRegistry = checkNotNull(decompressorRegistry, "decompressorRegistry");
        }

        /**
         * 设置客户端流监听器
         */
        @VisibleForTesting
        public final void setListener(ClientStreamListener listener) {
            checkState(this.listener == null, "Already called setListener");
            this.listener = checkNotNull(listener, "listener");
        }

        /**
         * 当 deframer 关闭时调用
         *
         * @param hasPartialMessage whether the deframer contained an incomplete message at closing.
         *                          Deframer 关闭时是否有未完成的流
         */
        @Override
        public void deframerClosed(boolean hasPartialMessage) {
            checkState(statusReported, "status should have been reported on deframer closed");
            deframerClosed = true;

            // 如果 Transport 状态是 OK，且还有未完成的流，则返回错误的状态
            if (statusReportedIsOk && hasPartialMessage) {
                transportReportStatus(Status.INTERNAL.withDescription("Encountered end-of-stream mid-frame"), true, new Metadata());
            }

            if (deframerClosedTask != null) {
                deframerClosedTask.run();
                deframerClosedTask = null;
            }
        }

        @Override
        protected final ClientStreamListener listener() {
            return listener;
        }

        private final void setOutboundClosed() {
            outboundClosed = true;
        }

        protected final boolean isOutboundClosed() {
            return outboundClosed;
        }

        /**
         * Called by transport implementations when they receive headers.
         *
         * @param headers the parsed headers
         */
        protected void inboundHeadersReceived(Metadata headers) {
            checkState(!statusReported, "Received headers on closed stream");
            statsTraceCtx.clientInboundHeaders();

            boolean compressedStream = false;
            String streamEncoding = headers.get(CONTENT_ENCODING_KEY);
            if (fullStreamDecompression && streamEncoding != null) {
                if (streamEncoding.equalsIgnoreCase("gzip")) {
                    setFullStreamDecompressor(new GzipInflatingBuffer());
                    compressedStream = true;
                } else if (!streamEncoding.equalsIgnoreCase("identity")) {
                    deframeFailed(
                            Status.INTERNAL
                                    .withDescription(
                                            String.format("Can't find full stream decompressor for %s", streamEncoding))
                                    .asRuntimeException());
                    return;
                }
            }

            String messageEncoding = headers.get(MESSAGE_ENCODING_KEY);
            if (messageEncoding != null) {
                Decompressor decompressor = decompressorRegistry.lookupDecompressor(messageEncoding);
                if (decompressor == null) {
                    deframeFailed(
                            Status.INTERNAL
                                    .withDescription(String.format("Can't find decompressor for %s", messageEncoding))
                                    .asRuntimeException());
                    return;
                } else if (decompressor != Codec.Identity.NONE) {
                    if (compressedStream) {
                        deframeFailed(
                                Status.INTERNAL
                                        .withDescription(
                                                String.format("Full stream and gRPC message encoding cannot both be set"))
                                        .asRuntimeException());
                        return;
                    }
                    setDecompressor(decompressor);
                }
            }

            listener().headersRead(headers);
        }

        /**
         * Processes the contents of a received data frame from the server.
         *
         * @param frame the received data frame. Its ownership is transferred to this method.
         */
        protected void inboundDataReceived(ReadableBuffer frame) {
            checkNotNull(frame, "frame");
            boolean needToCloseFrame = true;
            try {
                if (statusReported) {
                    log.log(Level.INFO, "Received data on closed stream");
                    return;
                }

                needToCloseFrame = false;
                deframe(frame);
            } finally {
                if (needToCloseFrame) {
                    frame.close();
                }
            }
        }

        /**
         * Processes the trailers and status from the server.
         *
         * @param trailers the received trailers
         * @param status   the status extracted from the trailers
         */
        protected void inboundTrailersReceived(Metadata trailers, Status status) {
            checkNotNull(status, "status");
            checkNotNull(trailers, "trailers");
            if (statusReported) {
                log.log(Level.INFO, "Received trailers on closed stream:\n {1}\n {2}",
                        new Object[]{status, trailers});
                return;
            }
            statsTraceCtx.clientInboundTrailers(trailers);
            transportReportStatus(status, false, trailers);
        }

        /**
         * Report stream closure with status to the application layer if not already reported. This
         * method must be called from the transport thread.
         * 如果在流关闭时状态还没有上报到应用层，则上报，这个方法必须由 Transport 的线程调用
         *
         * @param status       the new status to set
         *                     需要设置的新的状态
         * @param stopDelivery if {@code true}, interrupts any further delivery of inbound messages that
         *                     may already be queued up in the deframer. If {@code false}, the listener will be
         *                     notified immediately after all currently completed messages in the deframer have been
         *                     delivered to the application.
         *                     如果是 true，打断后续的已经在 Deframer 队列中的入站消息；如果是 false，当所有的 Deframer 中的消息
         *                     都投递到应用后会由监听器通知
         * @param trailers     new instance of {@code Trailers}, either empty or those returned by the
         *                     server
         *                     元数据的实例，由服务端返回，或者是空的
         */
        public final void transportReportStatus(final Status status,
                                                boolean stopDelivery,
                                                final Metadata trailers) {
            transportReportStatus(status, RpcProgress.PROCESSED, stopDelivery, trailers);
        }

        /**
         * Report stream closure with status to the application layer if not already reported. This
         * method must be called from the transport thread.
         * 如果在流关闭时状态还没有上报到应用层，则上报，这个方法必须由 Transport 的线程调用
         *
         * @param status       the new status to set
         *                     需要设置的新的状态
         * @param rpcProgress  RPC progress that the
         *                     {@link ClientStreamListener#closed(Status, RpcProgress, Metadata)}
         *                     will receive
         *                     ClientStreamListener#closed 方法将接收到的请求的状态
         * @param stopDelivery if {@code true}, interrupts any further delivery of inbound messages that
         *                     may already be queued up in the deframer and overrides any previously queued status.
         *                     If {@code false}, the listener will be notified immediately after all currently
         *                     completed messages in the deframer have been delivered to the application.
         *                     如果是 true，打断后续的已经在 Deframer 队列中的入站消息；如果是 false，当所有的 Deframer 中的消息
         *                     都投递到应用后会由监听器通知
         * @param trailers     new instance of {@code Trailers}, either empty or those returned by the
         *                     server
         *                     元数据的实例，由服务端返回，或者是空的
         */
        public final void transportReportStatus(final Status status,
                                                final RpcProgress rpcProgress,
                                                boolean stopDelivery,
                                                final Metadata trailers) {
            checkNotNull(status, "status");
            checkNotNull(trailers, "trailers");

            // If stopDelivery, we continue in case previous invocation is waiting for stall
            // 如果是停止投递，如果之前的调用在等待，则继续
            if (statusReported && !stopDelivery) {
                return;
            }
            statusReported = true;
            statusReportedIsOk = status.isOk();
            // 通知当流的状态不可以再使用
            onStreamDeallocated();

            // 如果解帧器已经关闭，则关闭监听器
            if (deframerClosed) {
                deframerClosedTask = null;
                closeListener(status, rpcProgress, trailers);
            } else {
                // 如果解帧器没有关闭，则创建关闭监听器任务
                deframerClosedTask = new Runnable() {
                    @Override
                    public void run() {
                        closeListener(status, rpcProgress, trailers);
                    }
                };
                // 关闭 Deframer
                closeDeframer(stopDelivery);
            }
        }

        /**
         * Closes the listener if not previously closed.
         * 如果没有关闭则关闭监听器
         *
         * @throws IllegalStateException if the call has not yet been started.
         */
        private void closeListener(Status status, RpcProgress rpcProgress, Metadata trailers) {
            // 如果没有关闭，则更新关闭状态
            if (!listenerClosed) {
                listenerClosed = true;
                // 关闭统计
                statsTraceCtx.streamClosed(status);
                // 关监听器
                listener().closed(status, rpcProgress, trailers);
                // 如果 Transport 的统计没有关闭，则上报 OK 状态
                if (getTransportTracer() != null) {
                    getTransportTracer().reportStreamClosed(status.isOk());
                }
            }
        }
    }

    /**
     * 用于 GET 请求的 Framer
     */
    private class GetFramer implements Framer {
        private Metadata headers;
        private boolean closed;
        private final StatsTraceContext statsTraceCtx;
        private byte[] payload;

        /***
         * 通过 Header 和 Trace 上下文构建 Framer
         */
        public GetFramer(Metadata headers, StatsTraceContext statsTraceCtx) {
            this.headers = checkNotNull(headers, "headers");
            this.statsTraceCtx = checkNotNull(statsTraceCtx, "statsTraceCtx");
        }

        /**
         * 写入负载
         *
         * @param message contains the message to be written out. It will be completely consumed.
         *                需要写入的消息
         */
        @SuppressWarnings("BetaApi") // ByteStreams is not Beta in v27
        @Override
        public void writePayload(InputStream message) {
            checkState(payload == null, "writePayload should not be called multiple times");
            try {
                // 将流转换为字节
                payload = ByteStreams.toByteArray(message);
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
            // 记录统计数据
            statsTraceCtx.outboundMessage(0);
            statsTraceCtx.outboundMessageSent(0, payload.length, payload.length);
            statsTraceCtx.outboundUncompressedSize(payload.length);
            // NB(zhangkun83): this is not accurate, because the underlying transport will probably encode
            // it using e.g., base64.  However, we are not supposed to know such detail here.
            // 统计并不准确，因为 Transport 可能会编码，然而并不应该关心这里的细节
            //
            // We don't want to move this line to where the encoding happens either, because we'd better
            // contain the message stats reporting in Framer as suggested in StatsTraceContext.
            // Scattering the reporting sites increases the risk of mis-counting or double-counting.
            // 也不想将这个统计移动到编码的地方，因为更想统计中包含消息的状态，将统计分散在各处会增加遗漏和多次统计的风险
            //
            // Because the payload is usually very small, people shouldn't care about the size difference
            // caused by encoding.
            // 因为负载通常很小，不应该关心编码导致的统计大小误差
            statsTraceCtx.outboundWireSize(payload.length);
        }

        @Override
        public void flush() {
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        /**
         * Closes, with flush.
         * 关闭并清空缓冲的流
         */
        @Override
        public void close() {
            closed = true;
            checkState(payload != null, "Lack of request message. GET request is only supported for unary requests");
            abstractClientStreamSink().writeHeaders(headers, payload);
            payload = null;
            headers = null;
        }

        /**
         * Closes, without flush.
         * 关闭但是不清空缓冲
         */
        @Override
        public void dispose() {
            closed = true;
            payload = null;
            headers = null;
        }

        // Compression is not supported for GET encoding.
        @Override
        public Framer setMessageCompression(boolean enable) {
            return this;
        }

        @Override
        public Framer setCompressor(Compressor compressor) {
            return this;
        }

        // TODO(zsurocking): support this
        @Override
        public void setMaxOutboundMessageSize(int maxSize) {
        }
    }
}
