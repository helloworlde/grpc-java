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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Attributes;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.DecompressorRegistry;
import io.grpc.InternalDecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.perfmark.PerfMark;
import io.perfmark.Tag;

import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.internal.GrpcUtil.ACCEPT_ENCODING_SPLITTER;
import static io.grpc.internal.GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.MESSAGE_ENCODING_KEY;

/**
 * 调用处理器
 *
 * @param <ReqT>  解析的请求类型
 * @param <RespT> 解析的响应类型
 */
final class ServerCallImpl<ReqT, RespT> extends ServerCall<ReqT, RespT> {

    private static final Logger log = Logger.getLogger(ServerCallImpl.class.getName());

    @VisibleForTesting
    static final String TOO_MANY_RESPONSES = "Too many responses";

    @VisibleForTesting
    static final String MISSING_RESPONSE = "Completed without a response";

    // 服务端流
    private final ServerStream stream;
    // 方法描述
    private final MethodDescriptor<ReqT, RespT> method;
    private final Tag tag;
    // 可取消的调用上下文
    private final Context.CancellableContext context;
    private final byte[] messageAcceptEncoding;
    // 解压缩器注册器
    private final DecompressorRegistry decompressorRegistry;
    // 压缩器注册器
    private final CompressorRegistry compressorRegistry;
    // 调用追踪
    private CallTracer serverCallTracer;

    // state
    // 调用取消
    private volatile boolean cancelled;
    // 发送 header
    private boolean sendHeadersCalled;
    // 已经关闭
    private boolean closeCalled;
    // 压缩器
    private Compressor compressor;
    // 已经发送消息
    private boolean messageSent;

    ServerCallImpl(ServerStream stream,
                   MethodDescriptor<ReqT, RespT> method,
                   Metadata inboundHeaders,
                   Context.CancellableContext context,
                   DecompressorRegistry decompressorRegistry,
                   CompressorRegistry compressorRegistry,
                   CallTracer serverCallTracer,
                   Tag tag) {
        this.stream = stream;
        this.method = method;
        this.context = context;
        this.messageAcceptEncoding = inboundHeaders.get(MESSAGE_ACCEPT_ENCODING_KEY);
        this.decompressorRegistry = decompressorRegistry;
        this.compressorRegistry = compressorRegistry;
        this.serverCallTracer = serverCallTracer;
        this.serverCallTracer.reportCallStarted();
        this.tag = tag;
    }

    /**
     * 在请求中将最多给定数量的消息投递给 Listener#onMessage
     *
     * @param numMessages the requested number of messages to be delivered to the listener.
     *                    投递给监听器的消息数量
     */
    @Override
    public void request(int numMessages) {
        PerfMark.startTask("ServerCall.request", tag);
        try {
            stream.request(numMessages);
        } finally {
            PerfMark.stopTask("ServerCall.request", tag);
        }
    }

    /**
     * 发送响应 header
     *
     * @param headers metadata to send prior to any response body.
     *                在发送响应之前发送的 header
     */
    @Override
    public void sendHeaders(Metadata headers) {
        PerfMark.startTask("ServerCall.sendHeaders", tag);
        try {
            sendHeadersInternal(headers);
        } finally {
            PerfMark.stopTask("ServerCall.sendHeaders", tag);
        }
    }

    private void sendHeadersInternal(Metadata headers) {
        // 检查是否已经发送过 header 或是否已经关闭了调用
        checkState(!sendHeadersCalled, "sendHeaders has already been called");
        checkState(!closeCalled, "call is closed");

        // 丢弃编码的 key
        headers.discardAll(MESSAGE_ENCODING_KEY);
        // 如果压缩器为 null，则设置为 NONE
        if (compressor == null) {
            compressor = Codec.Identity.NONE;
        } else {
            if (messageAcceptEncoding != null) {
                // TODO(carl-mastrangelo): remove the string allocation.
                if (!GrpcUtil.iterableContains(ACCEPT_ENCODING_SPLITTER.split(new String(messageAcceptEncoding, GrpcUtil.US_ASCII)), compressor.getMessageEncoding())) {
                    // resort to using no compression.
                    compressor = Codec.Identity.NONE;
                }
            } else {
                compressor = Codec.Identity.NONE;
            }
        }

        // Always put compressor, even if it's identity.
        // 设置压缩器类型
        headers.put(MESSAGE_ENCODING_KEY, compressor.getMessageEncoding());

        // 为流设置压缩器
        stream.setCompressor(compressor);

        // 丢弃消息编码的 key
        headers.discardAll(MESSAGE_ACCEPT_ENCODING_KEY);
        byte[] advertisedEncodings = InternalDecompressorRegistry.getRawAdvertisedMessageEncodings(decompressorRegistry);
        if (advertisedEncodings.length != 0) {
            headers.put(MESSAGE_ACCEPT_ENCODING_KEY, advertisedEncodings);
        }

        // Don't check if sendMessage has been called, since it requires that sendHeaders was already
        // called.
        // 将调用 header 状态改为 true
        sendHeadersCalled = true;
        stream.writeHeaders(headers);
    }

    /**
     * 发送消息
     *
     * @param message response message.
     *                要发送的消息
     */
    @Override
    public void sendMessage(RespT message) {
        PerfMark.startTask("ServerCall.sendMessage", tag);
        try {
            sendMessageInternal(message);
        } finally {
            PerfMark.stopTask("ServerCall.sendMessage", tag);
        }
    }

    private void sendMessageInternal(RespT message) {
        // 检查是否已经发送了 header，和调用是否已经被关闭
        checkState(sendHeadersCalled, "sendHeaders has not been called");
        checkState(!closeCalled, "call is closed");

        // 如果是 UNARY 或者 CLIENT_STREAMING 类型的消息，且已经发送过消息了，则不允许再发送，返回错误状态
        if (method.getType().serverSendsOneMessage() && messageSent) {
            internalClose(Status.INTERNAL.withDescription(TOO_MANY_RESPONSES));
            return;
        }

        // 将发送消息状态改为 true
        messageSent = true;
        try {
            // 将消息序列化为流，写入消息，清空流
            InputStream resp = method.streamResponse(message);
            stream.writeMessage(resp);
            stream.flush();
        } catch (RuntimeException e) {
            close(Status.fromThrowable(e), new Metadata());
        } catch (Error e) {
            close(Status.CANCELLED.withDescription("Server sendMessage() failed with Error"), new Metadata());
            throw e;
        }
    }

    /**
     * 设置是否开启压缩
     */
    @Override
    public void setMessageCompression(boolean enable) {
        stream.setMessageCompression(enable);
    }

    /**
     * 设置压缩器
     */
    @Override
    public void setCompression(String compressorName) {
        // Added here to give a better error message.
        // 在发送 header 之前调用
        checkState(!sendHeadersCalled, "sendHeaders has been called");

        // 根据压缩器名查找压缩器
        compressor = compressorRegistry.lookupCompressor(compressorName);
        // 检查压缩器存在
        checkArgument(compressor != null, "Unable to find compressor by name %s", compressorName);
    }

    /**
     * 返回流是否 ready
     */
    @Override
    public boolean isReady() {
        return stream.isReady();
    }

    /**
     * 处理请求完成事件
     *
     * @param status   状态
     * @param trailers 元数据
     */
    @Override
    public void close(Status status, Metadata trailers) {
        PerfMark.startTask("ServerCall.close", tag);
        try {
            closeInternal(status, trailers);
        } finally {
            PerfMark.stopTask("ServerCall.close", tag);
        }
    }

    private void closeInternal(Status status, Metadata trailers) {
        // 检查是否已经关闭
        checkState(!closeCalled, "call already closed");
        try {
            // 将关闭状态改为 true
            closeCalled = true;

            // 检查状态如果是 OK，且方法类型是 Server 端只能发送一次，且没有发送消息，则返回错误
            if (status.isOk() && method.getType().serverSendsOneMessage() && !messageSent) {
                internalClose(Status.INTERNAL.withDescription(MISSING_RESPONSE));
                return;
            }

            // 关闭流
            stream.close(status, trailers);
        } finally {
            // 统计结果
            serverCallTracer.reportCallEnded(status.isOk());
        }
    }

    /**
     * 返回流是否取消了
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * 使用调用监听器创建流监听器
     *
     * @param listener 调用监听器
     * @return 流监听器
     */
    ServerStreamListener newServerStreamListener(ServerCall.Listener<ReqT> listener) {
        return new ServerStreamListenerImpl<>(this, listener, context);
    }

    /**
     * 获取请求属性
     */
    @Override
    public Attributes getAttributes() {
        return stream.getAttributes();
    }

    /**
     * 获取请求服务端名称
     */
    @Override
    public String getAuthority() {
        return stream.getAuthority();
    }

    /**
     * 获取方法描述
     */
    @Override
    public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
        return method;
    }

    /**
     * Close the {@link ServerStream} because an internal error occurred. Allow the application to
     * run until completion, but silently ignore interactions with the {@link ServerStream} from now
     * on.
     * 因为内部错误关闭 ServerStream，允许程序运行直到完成，但是从现在开始忽略与 ServerStream 的交流
     */
    private void internalClose(Status internalError) {
        log.log(Level.WARNING, "Cancelling the stream with status {0}", new Object[]{internalError});
        // 取消流
        stream.cancel(internalError);
        // 统计
        serverCallTracer.reportCallEnded(internalError.isOk()); // error so always false
    }

    /**
     * All of these callbacks are assumed to called on an application thread, and the caller is
     * responsible for handling thrown exceptions.
     * 服务端流事件监听器
     * 所有的回调都在应用程序线程调用，调用者需要处理异常
     */
    @VisibleForTesting
    static final class ServerStreamListenerImpl<ReqT> implements ServerStreamListener {

        // 调用实现
        private final ServerCallImpl<ReqT, ?> call;

        // 调用监听器
        private final ServerCall.Listener<ReqT> listener;

        // 可取消的上下文
        private final Context.CancellableContext context;

        public ServerStreamListenerImpl(ServerCallImpl<ReqT, ?> call,
                                        ServerCall.Listener<ReqT> listener,
                                        Context.CancellableContext context) {

            this.call = checkNotNull(call, "call");
            this.listener = checkNotNull(listener, "listener must not be null");
            this.context = checkNotNull(context, "context");
            // Wire ourselves up so that if the context is cancelled, our flag call.cancelled also
            // reflects the new state. Use a DirectExecutor so that it happens in the same thread
            // as the caller of {@link Context#cancel}.
            // 添加 Context 取消事件监听器
            this.context.addListener(new Context.CancellationListener() {
                /**
                 * 取消事件，当取消后将调用的 cancelled 状态改为 true
                 * @param context the newly cancelled context.
                 */
                @Override
                public void cancelled(Context context) {
                    ServerStreamListenerImpl.this.call.cancelled = true;
                }
            }, MoreExecutors.directExecutor());
        }

        /**
         * 当从远程端点接收到消息时调用
         *
         * @param producer supplier of deframed messages.
         *                 帧消息提供者
         */
        @Override
        public void messagesAvailable(MessageProducer producer) {
            PerfMark.startTask("ServerStreamListener.messagesAvailable", call.tag);
            try {
                messagesAvailableInternal(producer);
            } finally {
                PerfMark.stopTask("ServerStreamListener.messagesAvailable", call.tag);
            }
        }

        @SuppressWarnings("Finally") // The code avoids suppressing the exception thrown from try
        private void messagesAvailableInternal(final MessageProducer producer) {
            // 如果调用已经取消了，则关闭生产者
            if (call.cancelled) {
                GrpcUtil.closeQuietly(producer);
                return;
            }

            InputStream message;
            try {
                // 从生产者中获取消息，
                while ((message = producer.next()) != null) {
                    try {
                        // 将流解析为请求对象，发送给监听器
                        listener.onMessage(call.method.parseRequest(message));
                    } catch (Throwable t) {
                        GrpcUtil.closeQuietly(message);
                        throw t;
                    }
                    message.close();
                }
            } catch (Throwable t) {
                GrpcUtil.closeQuietly(producer);
                Throwables.throwIfUnchecked(t);
                throw new RuntimeException(t);
            }
        }

        /**
         * 请求半关闭事件
         */
        @Override
        public void halfClosed() {
            PerfMark.startTask("ServerStreamListener.halfClosed", call.tag);
            try {
                // 如果调用取消了则直接返回
                if (call.cancelled) {
                    return;
                }

                // 通知监听器半关闭
                listener.onHalfClose();
            } finally {
                PerfMark.stopTask("ServerStreamListener.halfClosed", call.tag);
            }
        }

        /**
         * 调用关闭
         *
         * @param status details about the remote closure
         *               关闭状态
         */
        @Override
        public void closed(Status status) {
            PerfMark.startTask("ServerStreamListener.closed", call.tag);
            try {
                closedInternal(status);
            } finally {
                PerfMark.stopTask("ServerStreamListener.closed", call.tag);
            }
        }

        private void closedInternal(Status status) {
            try {
                // 如果状态是 OK，通知监听器完成
                if (status.isOk()) {
                    listener.onComplete();
                } else {
                    // 否则将状态改为取消，通知监听器取消
                    call.cancelled = true;
                    listener.onCancel();
                }
            } finally {
                // Cancel context after delivering RPC closure notification to allow the application to
                // clean up and update any state based on whether onComplete or onCancel was called.
                // 取消上下文
                context.cancel(null);
            }
        }

        /**
         * 调用 Ready
         */
        @Override
        public void onReady() {
            PerfMark.startTask("ServerStreamListener.onReady", call.tag);
            try {
                // 如果调用取消了则直接返回
                if (call.cancelled) {
                    return;
                }
                // 通知监听器 ready
                listener.onReady();
            } finally {
                PerfMark.stopTask("ServerCall.closed", call.tag);
            }
        }
    }
}
