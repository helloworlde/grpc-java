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

import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.AbstractServerStream;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportTracer;
import io.grpc.internal.WritableBuffer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Stream;
import io.perfmark.Link;
import io.perfmark.PerfMark;
import io.perfmark.Tag;

import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Server stream for a Netty HTTP2 transport. Must only be called from the sending application
 * thread.
 * 基于 Netty 的 HTTP2 的用于 Transport 的 Server 端流
 * 只能被应用发送的线程调用
 */
class NettyServerStream extends AbstractServerStream {

    private static final Logger log = Logger.getLogger(NettyServerStream.class.getName());

    private final Sink sink = new Sink();
    private final TransportState state;
    private final WriteQueue writeQueue;
    private final Attributes attributes;
    private final String authority;
    private final TransportTracer transportTracer;
    private final int streamId;

    public NettyServerStream(Channel channel,
                             TransportState state,
                             Attributes transportAttrs,
                             String authority,
                             StatsTraceContext statsTraceCtx,
                             TransportTracer transportTracer) {
        super(new NettyWritableBufferAllocator(channel.alloc()), statsTraceCtx);
        this.state = checkNotNull(state, "transportState");
        this.writeQueue = state.handler.getWriteQueue();
        this.attributes = checkNotNull(transportAttrs);
        this.authority = authority;
        this.transportTracer = checkNotNull(transportTracer, "transportTracer");
        // Read the id early to avoid reading transportState later.
        this.streamId = transportState().id();
    }

    @Override
    protected TransportState transportState() {
        return state;
    }

    @Override
    protected Sink abstractServerStreamSink() {
        return sink;
    }

    @Override
    public Attributes getAttributes() {
        return attributes;
    }

    @Override
    public String getAuthority() {
        return authority;
    }

    private class Sink implements AbstractServerStream.Sink {
        /**
         * 发送 Header 信息给客户端
         */
        @Override
        public void writeHeaders(Metadata headers) {
            PerfMark.startTask("NettyServerStream$Sink.writeHeaders");
            try {
                // 将写入 header 的指令添加到队列中
                writeQueue.enqueue(
                        SendResponseHeadersCommand.createHeaders(transportState(), Utils.convertServerHeaders(headers)),
                        true);
            } finally {
                PerfMark.stopTask("NettyServerStream$Sink.writeHeaders");
            }
        }

        private void writeFrameInternal(WritableBuffer frame, boolean flush, final int numMessages) {
            Preconditions.checkArgument(numMessages >= 0);
            // 如果帧为空，则 flush 队列
            if (frame == null) {
                writeQueue.scheduleFlush();
                return;
            }
            ByteBuf bytebuf = ((NettyWritableBuffer) frame).bytebuf().touch();
            final int numBytes = bytebuf.readableBytes();
            // Add the bytes to outbound flow control.
            // 将发送的字节数量发送给出站流控
            onSendingBytes(numBytes);
            // 将发送帧的指令添加到队列中
            writeQueue.enqueue(new SendGrpcFrameCommand(transportState(), bytebuf, false), flush)
                      .addListener(new ChannelFutureListener() {
                          @Override
                          public void operationComplete(ChannelFuture future) throws Exception {
                              // Remove the bytes from outbound flow control, optionally notifying
                              // the client that they can send more bytes.
                              // 从流控中移除已经发送的字节，通知客户端可以发送更多的字节
                              transportState().onSentBytes(numBytes);
                              if (future.isSuccess()) {
                                  transportTracer.reportMessageSent(numMessages);
                              }
                          }
                      });
        }

        /**
         * 写入消息帧
         *
         * @param frame       a buffer containing the chunk of data to be sent.
         *                    包含要发送的消息块的缓冲
         * @param flush       {@code true} if more data may not be arriving soon
         *                    是否有其他的消息需要发送
         * @param numMessages the number of messages this frame represents
         *                    帧代表的消息数量
         */
        @Override
        public void writeFrame(WritableBuffer frame, boolean flush, final int numMessages) {
            PerfMark.startTask("NettyServerStream$Sink.writeFrame");
            try {
                writeFrameInternal(frame, flush, numMessages);
            } finally {
                PerfMark.stopTask("NettyServerStream$Sink.writeFrame");
            }
        }

        /**
         * 写入结尾元数据
         *
         * @param trailers    metadata to be sent to the end point
         *                    需要发送给远程端点的元数据
         * @param headersSent {@code true} if response headers have already been sent.
         *                    响应的 header 是否已经被发送
         * @param status      the status that the call ended with
         */
        @Override
        public void writeTrailers(Metadata trailers, boolean headersSent, Status status) {
            PerfMark.startTask("NettyServerStream$Sink.writeTrailers");
            try {
                Http2Headers http2Trailers = Utils.convertTrailers(trailers, headersSent);
                // 将发送 header 的指令写入到队列中
                writeQueue.enqueue(SendResponseHeadersCommand.createTrailers(transportState(), http2Trailers, status), true);
            } finally {
                PerfMark.stopTask("NettyServerStream$Sink.writeTrailers");
            }
        }

        /**
         * 取消流
         */
        @Override
        public void cancel(Status status) {
            PerfMark.startTask("NettyServerStream$Sink.cancel");
            try {
                // 将取消的指令添加到队列中
                writeQueue.enqueue(new CancelServerStreamCommand(transportState(), status), true);
            } finally {
                PerfMark.startTask("NettyServerStream$Sink.cancel");
            }
        }
    }

    /**
     * This should only called from the transport thread.
     * 持有流 ID 的 TransportState
     */
    public static class TransportState extends AbstractServerStream.TransportState implements StreamIdHolder {

        private final Http2Stream http2Stream;
        private final NettyServerHandler handler;
        private final EventLoop eventLoop;
        private final Tag tag;

        public TransportState(NettyServerHandler handler,
                              EventLoop eventLoop,
                              Http2Stream http2Stream,
                              int maxMessageSize,
                              StatsTraceContext statsTraceCtx,
                              TransportTracer transportTracer,
                              String methodName) {
            super(maxMessageSize, statsTraceCtx, transportTracer);
            this.http2Stream = checkNotNull(http2Stream, "http2Stream");
            this.handler = checkNotNull(handler, "handler");
            this.eventLoop = eventLoop;
            this.tag = PerfMark.createTag(methodName, http2Stream.id());
        }

        /**
         * 在 Transport 的线程上执行任务
         */
        @Override
        public void runOnTransportThread(final Runnable r) {
            // 如果是在 EventLoop 中
            if (eventLoop.inEventLoop()) {
                r.run();
            } else {
                final Link link = PerfMark.linkOut();
                // 如果不在 EventLoop 中则提交给 EventLoop 执行
                eventLoop.execute(new Runnable() {
                    @Override
                    public void run() {
                        PerfMark.startTask("NettyServerStream$TransportState.runOnTransportThread", tag);
                        PerfMark.linkIn(link);
                        try {
                            r.run();
                        } finally {
                            PerfMark.stopTask("NettyServerStream$TransportState.runOnTransportThread", tag);
                        }
                    }
                });
            }
        }

        /**
         * 当指定数量的字节被读取的时候调用
         */
        @Override
        public void bytesRead(int processedBytes) {
            // 返还指定数量的字节给流控
            handler.returnProcessedBytes(http2Stream, processedBytes);
            // 清空写入队列
            handler.getWriteQueue().scheduleFlush();
        }

        /**
         * 解帧失败的时候调用，设置 Transport 状态并写入取消指令到队列中
         */
        @Override
        public void deframeFailed(Throwable cause) {
            log.log(Level.WARNING, "Exception processing message", cause);
            Status status = Status.fromThrowable(cause);
            transportReportStatus(status);
            handler.getWriteQueue().enqueue(new CancelServerStreamCommand(this, status), true);
        }

        /**
         * 当有字节入站的时候调用
         */
        void inboundDataReceived(ByteBuf frame, boolean endOfStream) {
            super.inboundDataReceived(new NettyReadableBuffer(frame.retain()), endOfStream);
        }

        @Override
        public int id() {
            return http2Stream.id();
        }

        @Override
        public Tag tag() {
            return tag;
        }
    }

    @Override
    public int streamId() {
        return streamId;
    }
}
