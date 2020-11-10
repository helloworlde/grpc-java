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

import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.Decompressor;
import io.grpc.InternalStatus;
import io.grpc.Metadata;
import io.grpc.Status;

import javax.annotation.Nullable;

/**
 * Abstract base class for {@link ServerStream} implementations. Extending classes only need to
 * implement {@link #transportState()} and {@link #abstractServerStreamSink()}. Must only be called
 * from the sending application thread.
 * <p>
 * ServerStream 的抽象实现，扩展类仅需要实现 transportState() 和 abstractServerStreamSink 方法，只能被发送的线程调用
 */
public abstract class AbstractServerStream extends AbstractStream
        implements ServerStream, MessageFramer.Sink {
    /**
     * A sink for outbound operations, separated from the stream simply to avoid name
     * collisions/confusion. Only called from application thread.
     * 用于出站操作的槽，从流中简单分离，避免名称混淆，只有在应用线程中调用
     */
    protected interface Sink {
        /**
         * Sends response headers to the remote end point.
         * 将响应的 Header 发送给远程的端点
         *
         * @param headers the headers to be sent to client.
         *                发送给客户端的header
         */
        void writeHeaders(Metadata headers);

        /**
         * Sends an outbound frame to the remote end point.
         * 将出站的帧发送给远程的端点
         *
         * @param frame       a buffer containing the chunk of data to be sent.
         *                    包含要发送的消息块的缓冲
         * @param flush       {@code true} if more data may not be arriving soon
         *                    是否有其他的消息需要发送
         * @param numMessages the number of messages this frame represents
         *                    这个帧代表的消息数
         */
        void writeFrame(@Nullable WritableBuffer frame, boolean flush, int numMessages);

        /**
         * Sends trailers to the remote end point. This call implies end of stream.
         * 将结尾的元数据发送给远程端点，在流结束的时候调用
         *
         * @param trailers    metadata to be sent to the end point
         *                    需要发送给远程端点的元数据
         * @param headersSent {@code true} if response headers have already been sent.
         *                    响应的 header 是否已经被发送
         * @param status      the status that the call ended with
         *                    响应的状态
         */
        void writeTrailers(Metadata trailers, boolean headersSent, Status status);

        /**
         * Tears down the stream, typically in the event of a timeout. This method may be called
         * multiple times and from any thread.
         * 关闭流，通常是因为超时，这个方法可能被任意线程多次调用
         *
         * <p>This is a clone of {@link ServerStream#cancel(Status)}.
         * 是 ServerStream#cancel 的复制方法
         */
        void cancel(Status status);
    }

    private final MessageFramer framer;
    private final StatsTraceContext statsTraceCtx;
    private boolean outboundClosed;
    private boolean headersSent;

    protected AbstractServerStream(WritableBufferAllocator bufferAllocator,
                                   StatsTraceContext statsTraceCtx) {
        this.statsTraceCtx = Preconditions.checkNotNull(statsTraceCtx, "statsTraceCtx");
        framer = new MessageFramer(this, bufferAllocator, statsTraceCtx);
    }

    @Override
    protected abstract TransportState transportState();

    /**
     * Sink for transport to be called to perform outbound operations. Each stream must have its own
     * unique sink.
     * 用于 Transport 的出站操作的 Sink，每个流必须有其唯一的 Sink
     */
    protected abstract Sink abstractServerStreamSink();

    /**
     * 用于发送消息的 framer
     */
    @Override
    protected final MessageFramer framer() {
        return framer;
    }

    /**
     * 发送 header 给客户端
     */
    @Override
    public final void writeHeaders(Metadata headers) {
        Preconditions.checkNotNull(headers, "headers");

        headersSent = true;
        abstractServerStreamSink().writeHeaders(headers);
    }

    /**
     * 写入帧
     *
     * @param frame       a non-empty buffer to deliver or {@code null} if the framer is being
     *                    closed and there is no data to deliver.
     *                    用于发送的非空的 buffer，如果 framer 已经关闭没有数据需要传输，则是 null
     * @param endOfStream whether the frame is the last one for the GRPC stream
     *                    当前的帧是否是 gRPC 流的最后一帧
     * @param flush       {@code true} if more data may not be arriving soon
     *                    是否不会再发送数据
     * @param numMessages the number of messages that this series of frames represents
     */
    @Override
    public final void deliverFrame(WritableBuffer frame,
                                   boolean endOfStream,
                                   boolean flush,
                                   int numMessages) {
        // Since endOfStream is triggered by the sending of trailers, avoid flush here and just flush
        // after the trailers.
        // 写入帧，如果到达流末尾，则 flush
        abstractServerStreamSink().writeFrame(frame, endOfStream ? false : flush, numMessages);
    }

    /**
     * 关闭读和写的流
     */
    @Override
    public final void close(Status status, Metadata trailers) {
        Preconditions.checkNotNull(status, "status");
        Preconditions.checkNotNull(trailers, "trailers");
        // 如果出站的流还未关闭，则将状态改为关闭
        if (!outboundClosed) {
            outboundClosed = true;
            // 从服务端关闭 framer
            endOfMessages();
            // 将响应状态加入到 header 中
            addStatusToTrailers(trailers, status);
            // Safe to set without synchronization because access is tightly controlled.
            // closedStatus is only set from here, and is read from a place that has happen-after
            // guarantees with respect to here.
            // 安全设置，无需同步，因为访问被严格控制，只有这里设置关闭状态，保证在这里之后读取
            // 给 Transport 设置响应状态
            transportState().setClosedStatus(status);
            // 将响应的 header 信息写入帧中
            abstractServerStreamSink().writeTrailers(trailers, headersSent, status);
        }
    }

    /**
     * 将响应状态写入到结尾的元数据中
     */
    private void addStatusToTrailers(Metadata trailers, Status status) {
        // 取消已有的状态和消息
        trailers.discardAll(InternalStatus.CODE_KEY);
        trailers.discardAll(InternalStatus.MESSAGE_KEY);
        // 根据参数设置状态和消息
        trailers.put(InternalStatus.CODE_KEY, status);
        if (status.getDescription() != null) {
            trailers.put(InternalStatus.MESSAGE_KEY, status.getDescription());
        }
    }

    /**
     * 从服务端取消流
     */
    @Override
    public final void cancel(Status status) {
        abstractServerStreamSink().cancel(status);
    }

    /**
     * 返回流是否 ready
     */
    @Override
    public final boolean isReady() {
        return super.isReady();
    }

    @Override
    public final void setDecompressor(Decompressor decompressor) {
        transportState().setDecompressor(Preconditions.checkNotNull(decompressor, "decompressor"));
    }

    @Override
    public Attributes getAttributes() {
        return Attributes.EMPTY;
    }

    @Override
    public String getAuthority() {
        return null;
    }

    /**
     * 设置流事件监听器
     */
    @Override
    public final void setListener(ServerStreamListener serverStreamListener) {
        transportState().setListener(serverStreamListener);
    }

    @Override
    public StatsTraceContext statsTraceContext() {
        return statsTraceCtx;
    }

    /**
     * This should only called from the transport thread (except for private interactions with
     * {@code AbstractServerStream}).
     * Transport  状态管理
     * 应当在 Transport 线程内调用(与 AbstractServerStream 互相调用除外)
     */
    protected abstract static class TransportState extends AbstractStream.TransportState {
        /**
         * Whether listener.closed() has been called.
         * listener.closed() 是否被调用
         */
        private boolean listenerClosed;
        private ServerStreamListener listener;
        private final StatsTraceContext statsTraceCtx;

        private boolean endOfStream = false;
        private boolean deframerClosed = false;
        private boolean immediateCloseRequested = false;
        private Runnable deframerClosedTask;
        /**
         * The status that the application used to close this stream.
         * 应用关闭流的状态
         */
        @Nullable
        private Status closedStatus;

        protected TransportState(int maxMessageSize,
                                 StatsTraceContext statsTraceCtx,
                                 TransportTracer transportTracer) {
            super(maxMessageSize,
                    statsTraceCtx,
                    Preconditions.checkNotNull(transportTracer, "transportTracer"));
            this.statsTraceCtx = Preconditions.checkNotNull(statsTraceCtx, "statsTraceCtx");
        }

        /**
         * Sets the listener to receive notifications. Must be called in the context of the transport
         * thread.
         * 设置用于监听通知的监听器，只能在 Transport 线程的上下文中调用
         */
        public final void setListener(ServerStreamListener listener) {
            Preconditions.checkState(this.listener == null, "setListener should be called only once");
            this.listener = Preconditions.checkNotNull(listener, "listener");
        }

        /**
         * 当流的 header 传递给流控的任意连接时的事件处理器时调用
         */
        @Override
        public final void onStreamAllocated() {
            // 修改流状态，通知 ready
            super.onStreamAllocated();
            // 统计流开始
            getTransportTracer().reportRemoteStreamStarted();
        }

        /**
         * 当解帧器关闭的时候调用
         */
        @Override
        public void deframerClosed(boolean hasPartialMessage) {
            deframerClosed = true;
            // 是否到达流结尾
            if (endOfStream) {
                // 如果不需要立即关闭，且有未完成的消息，返回错误并抛出异常
                if (!immediateCloseRequested && hasPartialMessage) {
                    // We've received the entire stream and have data available but we don't have
                    // enough to read the next frame ... this is bad.
                    deframeFailed(Status.INTERNAL.withDescription("Encountered end-of-stream mid-frame")
                                                 .asRuntimeException());
                    deframerClosedTask = null;
                    return;
                }
                // 通知半关闭
                listener.halfClosed();
            }
            // 如果有解帧器关闭的任务，则执行
            if (deframerClosedTask != null) {
                deframerClosedTask.run();
                deframerClosedTask = null;
            }
        }

        @Override
        protected ServerStreamListener listener() {
            return listener;
        }

        /**
         * Called in the transport thread to process the content of an inbound DATA frame from the
         * client.
         * 当接收到客户端的入站消息帧时调用
         *
         * @param frame       the inbound HTTP/2 DATA frame. If this buffer is not used immediately, it must
         *                    be retained.
         *                    入战的 HTTP2 消息帧，如果缓冲没有立即被使用，则必须保留
         * @param endOfStream {@code true} if no more data will be received on the stream.
         *                    如果流中没有数据则是 true
         */
        public void inboundDataReceived(ReadableBuffer frame, boolean endOfStream) {
            Preconditions.checkState(!this.endOfStream, "Past end of stream");
            // Deframe the message. If a failure occurs, deframeFailed will be called.
            // 解帧消息，如果解帧失败，则会调用 deframeFailed
            deframe(frame);
            // 如果到达流末尾，则关闭解帧器
            if (endOfStream) {
                this.endOfStream = true;
                closeDeframer(false);
            }
        }

        /**
         * Notifies failure to the listener of the stream. The transport is responsible for notifying
         * the client of the failure independent of this method.
         * 通知监听器流失败，Transport 在这个方法之外负责通知客户端失败
         *
         * <p>Unlike {@link #close(Status, Metadata)}, this method is only called from the
         * transport. The transport should use this method instead of {@code close(Status)} for internal
         * errors to prevent exposing unexpected states and exceptions to the application.
         * 和 close 不同的是，这个方法只有 Transport 调用，当发生内部错误时，Transport 应当使用这个方法，防止
         * 对外暴露异常
         *
         * @param status the error status. Must not be {@link Status#OK}.
         *               错误状态，不能是 OK
         */
        public final void transportReportStatus(final Status status) {
            Preconditions.checkArgument(!status.isOk(), "status must not be OK");
            // 如果解帧器关闭，则关闭监听器
            if (deframerClosed) {
                deframerClosedTask = null;
                closeListener(status);
            } else {
                // 如果解帧器还没有关闭，则创建关闭监听器的任务，并立即关闭解帧器
                deframerClosedTask = new Runnable() {
                    @Override
                    public void run() {
                        closeListener(status);
                    }
                };
                immediateCloseRequested = true;
                closeDeframer(true);
            }
        }

        /**
         * Indicates the stream is considered completely closed and there is no further opportunity for
         * error. It calls the listener's {@code closed()} if it was not already done by {@link
         * #transportReportStatus}.
         * 表示考虑完全关闭流，不会再触发错误，如果 transportReportStatus 还没有完成，会调用监听器的 closed 方法
         */
        public void complete() {
            // 如果解帧器已经关闭了，则关闭监听器
            if (deframerClosed) {
                deframerClosedTask = null;
                closeListener(Status.OK);
            } else {
                // 如果还未关闭，则创建关闭监听器任务，并立即关闭解帧器
                deframerClosedTask = new Runnable() {
                    @Override
                    public void run() {
                        closeListener(Status.OK);
                    }
                };
                immediateCloseRequested = true;
                closeDeframer(true);
            }
        }

        /**
         * Closes the listener if not previously closed and frees resources. {@code newStatus} is a
         * status generated by gRPC. It is <b>not</b> the status the stream closed with.
         * 关闭监听器，释放资源，这里的状态是用于 gRPC 的状态，不是流关闭的状态
         */
        private void closeListener(Status newStatus) {
            // If newStatus is OK, the application must have already called AbstractServerStream.close()
            // and the status passed in there was the actual status of the RPC.
            // If newStatus non-OK, then the RPC ended some other way and the server application did
            // not initiate the termination.
            // 如果 newStatus 是 OK，则应用一定已经调用了 AbstractServerStream.close() 方法，并将真实的状态传递给了
            // 请求，如果不是 OK，则 RPC 以其他方式中止，server 端应用没有初始化终止
            Preconditions.checkState(!newStatus.isOk() || closedStatus != null);
            // 如果监听器没有关闭，则根据状态操作
            if (!listenerClosed) {
                // 如果状态不是OK，则使用指定状态记录关闭流
                if (!newStatus.isOk()) {
                    statsTraceCtx.streamClosed(newStatus);
                    getTransportTracer().reportStreamClosed(false);
                } else {
                    // 否则使用流的关闭状态记录
                    statsTraceCtx.streamClosed(closedStatus);
                    getTransportTracer().reportStreamClosed(closedStatus.isOk());
                }
                listenerClosed = true;
                // 通知流的状态不可以再使用
                onStreamDeallocated();
                // 使用指定状态关闭监听器
                listener().closed(newStatus);
            }
        }

        /**
         * Stores the {@code Status} that the application used to close this stream.
         * 记录应用关闭流的状态
         */
        private void setClosedStatus(Status closeStatus) {
            Preconditions.checkState(closedStatus == null, "closedStatus can only be set once");
            closedStatus = closeStatus;
        }
    }
}
