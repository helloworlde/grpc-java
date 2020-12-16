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

package io.grpc.netty;

import com.google.common.base.Preconditions;
import com.google.common.io.BaseEncoding;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.InternalKnownTransport;
import io.grpc.InternalMethodDescriptor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.AbstractClientStream;
import io.grpc.internal.Http2ClientStreamTransportState;
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
import io.netty.util.AsciiString;
import io.perfmark.PerfMark;
import io.perfmark.Tag;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.netty.buffer.Unpooled.EMPTY_BUFFER;

/**
 * Client stream for a Netty transport. Must only be called from the sending application
 * thread.
 * 用于 Netty Transport 的客户端流，只能被应用程序发送线程调用
 */
class NettyClientStream extends AbstractClientStream {
    private static final InternalMethodDescriptor methodDescriptorAccessor =
            new InternalMethodDescriptor(NettyClientTransport.class.getName().contains("grpc.netty.shaded") ? InternalKnownTransport.NETTY_SHADED : InternalKnownTransport.NETTY);

    // 用于出站操作的槽
    private final Sink sink = new Sink();
    // Transport状态
    private final TransportState state;
    // 写队列
    private final WriteQueue writeQueue;
    // 方法描述
    private final MethodDescriptor<?, ?> method;
    // 服务端名称
    private AsciiString authority;
    // 协议
    private final AsciiString scheme;
    // UA
    private final AsciiString userAgent;

    NettyClientStream(TransportState state,
                      MethodDescriptor<?, ?> method,
                      Metadata headers,
                      Channel channel,
                      AsciiString authority,
                      AsciiString scheme,
                      AsciiString userAgent,
                      StatsTraceContext statsTraceCtx,
                      TransportTracer transportTracer,
                      CallOptions callOptions,
                      boolean useGetForSafeMethods) {
        // 调用抽象类的构造器，会创建 Framer，并设置 header
        super(new NettyWritableBufferAllocator(channel.alloc()),
                statsTraceCtx,
                transportTracer,
                headers,
                callOptions,
                useGetForSafeMethods && method.isSafe());
        this.state = checkNotNull(state, "transportState");
        this.writeQueue = state.handler.getWriteQueue();
        this.method = checkNotNull(method, "method");
        this.authority = checkNotNull(authority, "authority");
        this.scheme = checkNotNull(scheme, "scheme");
        this.userAgent = userAgent;
    }

    @Override
    protected TransportState transportState() {
        return state;
    }

    @Override
    protected Sink abstractClientStreamSink() {
        return sink;
    }

    @Override
    public void setAuthority(String authority) {
        this.authority = AsciiString.of(checkNotNull(authority, "authority"));
    }

    @Override
    public Attributes getAttributes() {
        return state.handler.getAttributes();
    }


    /**
     * 用于出站操作的槽，只用于分离流，避免名称混乱，只在应用线程内调用
     */
    private class Sink implements AbstractClientStream.Sink {

        @Override
        public void writeHeaders(Metadata headers, byte[] requestPayload) {
            PerfMark.startTask("NettyClientStream$Sink.writeHeaders");
            try {
                // 写入 header 到远程端点
                writeHeadersInternal(headers, requestPayload);
            } finally {
                PerfMark.stopTask("NettyClientStream$Sink.writeHeaders");
            }
        }

        /**
         * 执行写入 header 信息到远程端点
         *
         * @param headers        请求头信息
         * @param requestPayload 请求负载
         */
        private void writeHeadersInternal(Metadata headers, byte[] requestPayload) {
            // Convert the headers into Netty HTTP/2 headers.
            // 将 Header 转为 Netty 的 HTTP2 的 header
            // 根据方法名获取路径
            AsciiString defaultPath = (AsciiString) methodDescriptorAccessor.geRawMethodName(method);
            // 如果路径为 null，则设置路径为方法全名
            if (defaultPath == null) {
                defaultPath = new AsciiString("/" + method.getFullMethodName());
                methodDescriptorAccessor.setRawMethodName(method, defaultPath);
            }
            // 如果有 payload，则使用 GET 方法
            boolean get = (requestPayload != null);
            AsciiString httpMethod;
            // 如果是 GET 方法，则将负载加到请求路径中，并设置请求方法
            if (get) {
                // Forge the query string
                // TODO(ericgribkoff) Add the key back to the query string
                // 将负载通过 base64 编码后添加到请求路径中
                defaultPath = new AsciiString(defaultPath + "?" + BaseEncoding.base64().encode(requestPayload));
                httpMethod = Utils.HTTP_GET_METHOD;
            } else {
                httpMethod = Utils.HTTP_METHOD;
            }
            // 将 Header 转为 Netty 的 HTTP2 header
            Http2Headers http2Headers = Utils.convertClientHeaders(headers, scheme, defaultPath, authority, httpMethod, userAgent);

            // 创建 ChannelFuture 的监听器
            ChannelFutureListener failureListener = new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    // 如果 Future 状态不是成功的
                    if (!future.isSuccess()) {
                        // Stream creation failed. Close the stream if not already closed.
                        // When the channel is shutdown, the lifecycle manager has a better view of the failure,
                        // especially before negotiation completes (because the negotiator commonly doesn't
                        // receive the execeptionCaught because NettyClientHandler does not propagate it).
                        // 流创建失败时，如果流没有关闭，则关闭流；当 Channel 关闭时， Lifecycle Manager 更了解失败，
                        // 尤其是在判断完成之前
                        // 获取关闭状态
                        Status s = transportState().handler.getLifecycleManager().getShutdownStatus();
                        // 如果关闭状态是 null，则从失败的 Future 中获取失败状态
                        if (s == null) {
                            s = transportState().statusFromFailedFuture(future);
                        }
                        // 上报 Transport 的状态
                        transportState().transportReportStatus(s, true, new Metadata());
                    }
                }
            };
            // Write the command requesting the creation of the stream.
            // 写入创建流的请求的指令，并添加失败的 Future 监听器
            writeQueue.enqueue(
                    new CreateStreamCommand(http2Headers, transportState(), shouldBeCountedForInUse(), get),
                    !method.getType().clientSendsOneMessage() || get)
                      .addListener(failureListener);
        }

        /**
         * 将出站帧发送给远程端点
         */
        private void writeFrameInternal(WritableBuffer frame, boolean endOfStream, boolean flush, final int numMessages) {
            Preconditions.checkArgument(numMessages >= 0);

            // 将 frame 转换为 ByteBuf
            ByteBuf bytebuf = frame == null ? EMPTY_BUFFER : ((NettyWritableBuffer) frame).bytebuf().touch();
            // 统计 ByteBuf 的可读字节数
            final int numBytes = bytebuf.readableBytes();
            // 如果字节数大于 0
            if (numBytes > 0) {
                // Add the bytes to outbound flow control.
                // 将要出站的字节数添加到流控中
                onSendingBytes(numBytes);
                // 将发送 gRPC 帧命令添加到写队列中
                writeQueue.enqueue(new SendGrpcFrameCommand(transportState(), bytebuf, endOfStream), flush)
                          .addListener(new ChannelFutureListener() {
                              @Override
                              public void operationComplete(ChannelFuture future) throws Exception {
                                  // If the future succeeds when http2stream is null, the stream has been cancelled
                                  // before it began and Netty is purging pending writes from the flow-controller.
                                  // 如果 future 成功，且 Transport 中的流不为 null
                                  if (future.isSuccess() && transportState().http2Stream() != null) {
                                      // Remove the bytes from outbound flow control, optionally notifying
                                      // the client that they can send more bytes.
                                      // 添加发送的字节数及统计
                                      transportState().onSentBytes(numBytes);
                                      NettyClientStream.this.getTransportTracer().reportMessageSent(numMessages);
                                  }
                              }
                          });
            } else {
                // The frame is empty and will not impact outbound flow control. Just send it.
                // 如果发送的字节为空，则不会影响流控，仅仅发送
                writeQueue.enqueue(new SendGrpcFrameCommand(transportState(), bytebuf, endOfStream), flush);
            }
        }

        /**
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
         * @param numMessages 要写入的消息数量
         */
        @Override
        public void writeFrame(WritableBuffer frame, boolean endOfStream, boolean flush, int numMessages) {
            PerfMark.startTask("NettyClientStream$Sink.writeFrame");
            try {
                writeFrameInternal(frame, endOfStream, flush, numMessages);
            } finally {
                PerfMark.stopTask("NettyClientStream$Sink.writeFrame");
            }
        }

        /**
         * 使用指定状态取消流
         */
        @Override
        public void cancel(Status status) {
            PerfMark.startTask("NettyClientStream$Sink.cancel");
            try {
                // 将取消流的指令添加到队列中
                writeQueue.enqueue(new CancelClientStreamCommand(transportState(), status), true);
            } finally {
                PerfMark.stopTask("NettyClientStream$Sink.cancel");
            }
        }
    }

    /**
     * This should only called from the transport thread.
     * 仅应该由 Transport 的线程调用
     */
    public abstract static class TransportState extends Http2ClientStreamTransportState implements StreamIdHolder {
        private static final int NON_EXISTENT_ID = -1;

        private final String methodName;
        private final NettyClientHandler handler;
        private final EventLoop eventLoop;
        private int id;
        private Http2Stream http2Stream;
        private Tag tag;

        public TransportState(NettyClientHandler handler,
                              EventLoop eventLoop,
                              int maxMessageSize,
                              StatsTraceContext statsTraceCtx,
                              TransportTracer transportTracer,
                              String methodName) {
            super(maxMessageSize, statsTraceCtx, transportTracer);
            this.methodName = checkNotNull(methodName, "methodName");
            this.handler = checkNotNull(handler, "handler");
            this.eventLoop = checkNotNull(eventLoop, "eventLoop");
            tag = PerfMark.createTag(methodName);
        }

        @Override
        public int id() {
            // id should be positive
            return id;
        }

        /**
         * 设置 ID
         *
         * @param id
         */
        public void setId(int id) {
            checkArgument(id > 0, "id must be positive %s", id);
            checkState(this.id == 0, "id has been previously set: %s", this.id);
            this.id = id;
            this.tag = PerfMark.createTag(methodName, id);
        }

        /**
         * Marks the stream state as if it had never existed.  This can happen if the stream is
         * cancelled after it is created, but before it has been started.
         * 如果流不存在，则标记其状态，这种情况发生在流创建后未启动就被关闭的场景中
         */
        void setNonExistent() {
            checkState(this.id == 0, "Id has been previously set: %s", this.id);
            this.id = NON_EXISTENT_ID;
        }

        /**
         * 流是否未存在过
         */
        boolean isNonExistent() {
            return this.id == NON_EXISTENT_ID;
        }

        /**
         * Sets the underlying Netty {@link Http2Stream} for this stream. This must be called in the
         * context of the transport thread.
         * 为这个流设置 Netty 底层的 Http2Stream，必须在 Transport 的上下文线程中调用
         */
        public void setHttp2Stream(Http2Stream http2Stream) {
            checkNotNull(http2Stream, "http2Stream");
            checkState(this.http2Stream == null, "Can only set http2Stream once");
            this.http2Stream = http2Stream;

            // Now that the stream has actually been initialized, call the listener's onReady callback if
            // appropriate.
            // 流已经初始化，在适当的时候通知监听器 ready
            onStreamAllocated();
            // 更新统计，流已经开始
            getTransportTracer().reportLocalStreamStarted();
        }

        /**
         * Gets the underlying Netty {@link Http2Stream} for this stream.
         * 返回这个流的基于 Netty 的 Http2Stream
         */
        @Nullable
        public Http2Stream http2Stream() {
            return http2Stream;
        }

        /**
         * Intended to be overridden by NettyClientTransport, which has more information about failures.
         * May only be called from event loop.
         * 因为 NettyClientTransport 有更多的失败细节，所以用 NettyClientTransport 覆盖，可能只会被 event loop 调用
         */
        protected abstract Status statusFromFailedFuture(ChannelFuture f);

        /**
         * 当 HTTP2 处理失败的时候调用，应当通知 Transport 取消流，并调用 transportReportStatus()
         */
        @Override
        protected void http2ProcessingFailed(Status status, boolean stopDelivery, Metadata trailers) {
            // 更新 Transport 的状态
            transportReportStatus(status, stopDelivery, trailers);
            // 将取消请求的指令添加到队列中
            handler.getWriteQueue().enqueue(new CancelClientStreamCommand(this, status), true);
        }

        /**
         * 在 Transport 的线程上执行 task
         *
         * @param r 任务
         */
        @Override
        public void runOnTransportThread(final Runnable r) {
            // 如果在 event loop 中，则直接执行
            if (eventLoop.inEventLoop()) {
                r.run();
            } else {
                // 否则通过 event loop 执行
                eventLoop.execute(r);
            }
        }

        /**
         * 当给定数量的字节被 deframer 从输入源中读取的时候调用， 通常表明有可以读取更多的数据
         */
        @Override
        public void bytesRead(int processedBytes) {
            // 返回字节数量
            handler.returnProcessedBytes(http2Stream, processedBytes);
            // 调度一个 Flush 任务
            handler.getWriteQueue().scheduleFlush();
        }

        /**
         * 当解帧失败的时候调用
         *
         * @param cause the actual failure 失败的原因
         */
        @Override
        public void deframeFailed(Throwable cause) {
            http2ProcessingFailed(Status.fromThrowable(cause), true, new Metadata());
        }

        /**
         * 当 header 被接收的时候调用
         *
         * @param headers     请求头
         * @param endOfStream 是否到达流末尾
         */
        void transportHeadersReceived(Http2Headers headers, boolean endOfStream) {
            // 如果已经到达流末尾，
            if (endOfStream) {
                // 如果出站还没有关闭
                if (!isOutboundClosed()) {
                    // 添加取消流指令
                    handler.getWriteQueue().enqueue(new CancelClientStreamCommand(this, null), true);
                }
                // 处理服务端返回的 header
                transportTrailersReceived(Utils.convertTrailers(headers));
            } else {
                // 处理接收到的 header
                transportHeadersReceived(Utils.convertHeaders(headers));
            }
        }

        /**
         * 当 Transport 收到数据时调用
         *
         * @param frame       帧数据
         * @param endOfStream 是否到达流末尾
         */
        void transportDataReceived(ByteBuf frame, boolean endOfStream) {
            // 处理帧
            transportDataReceived(new NettyReadableBuffer(frame.retain()), endOfStream);
        }

        @Override
        public final Tag tag() {
            return tag;
        }
    }
}
