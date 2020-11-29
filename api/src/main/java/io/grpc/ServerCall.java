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

package io.grpc;

import javax.annotation.Nullable;

/**
 * Encapsulates a single call received from a remote client. Calls may not simply be unary
 * request-response even though this is the most common pattern. Calls may stream any number of
 * requests and responses. This API is generally intended for use by generated handlers,
 * but applications may use it directly if they need to.
 * 封装客户端调用的单个请求，调用可能不是简单的请求响应的一元模式，尽管这是最常见的模式；调用可能是任意数量的
 * 请求和响应流，该 API 通常供生成的处理器使用，但是应用程序可以根据需要直接使用它
 *
 * <p>Headers must be sent before any messages, which must be sent before closing.
 * header 必须在发送任意消息之前发送，消息必须在关闭之前发送
 *
 * <p>No generic method for determining message receipt or providing acknowledgement is provided.
 * Applications are expected to utilize normal messages for such signals, as a response
 * naturally acknowledges its request.
 * 没有提供接收消息或者提供消息的通用犯法，预计应用程序将会将正常消息用于此类信号，作为响应自然会响应其请求
 *
 * <p>Methods are guaranteed to be non-blocking. Implementations are not required to be thread-safe.
 * 方法需要保证是非阻塞的，实现不要求线程安全
 *
 * <p>DO NOT MOCK: Use InProcessTransport and make a fake server instead.
 *
 * @param <ReqT>  parsed type of request message.
 *                请求消息的解析类型
 * @param <RespT> parsed type of response message.
 *                响应消息的解析类型
 */
public abstract class ServerCall<ReqT, RespT> {

    /**
     * Callbacks for consuming incoming RPC messages.
     * 消费 RPC 消息的回调
     *
     * <p>Any contexts are guaranteed to arrive before any messages, which are guaranteed before half
     * close, which is guaranteed before completion.
     * 保证所有上下文在任何消息到达之前，在半关闭之前，在完成之前
     *
     * <p>Implementations are free to block for extended periods of time. Implementations are not
     * required to be thread-safe.
     * 实现即使阻塞也没有关系，不要求实现是线程安全的
     */
    // TODO(ejona86): We need to decide what to do in the case of server closing with non-cancellation
    // before client half closes. It may be that we treat such a case as an error. If we permit such
    // a case then we either get to generate a half close or purposefully omit it.
    public abstract static class Listener<ReqT> {
        /**
         * A request message has been received. For streaming calls, there may be zero or more request
         * messages.
         * 已经接受到消息，对于流式调用，可能没有或者有多个消息
         *
         * @param message a received request message.
         *                接收到的消息
         */
        public void onMessage(ReqT message) {
        }

        /**
         * The client completed all message sending. However, the call may still be cancelled.
         * 客户端完成了所有的消息发送，然而，调用依然可能会被取消
         */
        public void onHalfClose() {
        }

        /**
         * The call was cancelled and the server is encouraged to abort processing to save resources,
         * since the client will not process any further messages. Cancellations can be caused by
         * timeouts, explicit cancellation by the client, network errors, etc.
         * 客户端取消了调用，因为客户端不会再处理任何消息，建议服务端取消处理，取消可能是超时、
         * 客户端明确取消，网络错误等造成的
         *
         * <p>There will be no further callbacks for the call.
         * 这个回调之后不会再有回调
         */
        public void onCancel() {
        }

        /**
         * The call is considered complete and {@link #onCancel} is guaranteed not to be called.
         * However, the client is not guaranteed to have received all messages.
         * 认为请求是完成的，不建议再调用 onCancel, 但是不能保证客户端已经接收到了所有消息
         *
         * <p>There will be no further callbacks for the call.
         * 在这个调用之后不会再有回调
         */
        public void onComplete() {
        }

        /**
         * This indicates that the call may now be capable of sending additional messages (via
         * {@link #sendMessage}) without requiring excessive buffering internally. This event is
         * just a suggestion and the application is free to ignore it, however doing so may
         * result in excessive buffering within the call.
         * 表明请求可以发送附加的信息，而不需要额外的缓冲，这只是一个建议，应用可以忽略，但是这样做可能会
         * 导致调用中的缓冲过多
         *
         * <p>Because there is a processing delay to deliver this notification, it is possible for
         * concurrent writes to cause {@code isReady() == false} within this callback. Handle "spurious"
         * notifications by checking {@code isReady()}'s current value instead of assuming it is now
         * {@code true}. If {@code isReady() == false} the normal expectations apply, so there would be
         * <em>another</em> {@code onReady()} callback.
         * 因为投递通知有处理延迟，可能会并发写入导致 isReady 是 false 没有执行这个回调，需要通过调用 isReady 检查
         * 当前的状态，而不是认为是 true，如果 isReady 是 false，则会有另外一个回调
         */
        public void onReady() {
        }
    }

    /**
     * Requests up to the given number of messages from the call to be delivered to
     * {@link Listener#onMessage(Object)}. Once {@code numMessages} have been delivered
     * no further request messages will be delivered until more messages are requested by
     * calling this method again.
     * 在请求中将最多给定数量的消息投递给 Listener#onMessage，一旦传递了指定数量的消息，不会再传递
     * 更多的消息，除非再次调用这个方法
     *
     * <p>Servers use this mechanism to provide back-pressure to the client for flow-control.
     * 服务器使用此机制提供背压用于流控
     *
     * <p>This method is safe to call from multiple threads without external synchronization.
     * 这个方法可以被多个线程调用而不需要额外的同步控制
     *
     * @param numMessages the requested number of messages to be delivered to the listener.
     *                    要投递给监听器的要发送的消息的数量
     */
    public abstract void request(int numMessages);

    /**
     * Send response header metadata prior to sending a response message. This method may
     * only be called once and cannot be called after calls to {@link #sendMessage} or {@link #close}.
     * 在发送响应消息之前发送 header，这个方法只能调用一次，在调用了 sendMessage 或 close 方法之后不能再调用
     *
     * <p>Since {@link Metadata} is not thread-safe, the caller must not access (read or write) {@code
     * headers} after this point.
     * 因为 Metadata 不是线程安全的，调用者在此只能不能再操作读或写
     *
     * @param headers metadata to send prior to any response body.
     *                在发送任何响应之前发送给客户端的元数据
     * @throws IllegalStateException if {@code close} has been called, a message has been sent, or
     *                               headers have already been sent
     *                               如果调用了关闭，发送了消息，或者 header 已经被发送了
     */
    public abstract void sendHeaders(Metadata headers);

    /**
     * Send a response message. Messages are the primary form of communication associated with
     * RPCs. Multiple response messages may exist for streaming calls.
     * 发送一个响应消息，消息是与 RPC 相关的主要通信格式，流调用可能存在多个响应消息
     *
     * @param message response message.
     *                响应消息
     * @throws IllegalStateException if headers not sent or call is {@link #close}d
     *                               如果 header 没有发送或者调用已经关闭
     */
    public abstract void sendMessage(RespT message);

    /**
     * If {@code true}, indicates that the call is capable of sending additional messages
     * without requiring excessive buffering internally. This event is
     * just a suggestion and the application is free to ignore it, however doing so may
     * result in excessive buffering within the call.
     * 如果是 true，表明调用已经可以发送附加消息而不需要额外的缓冲，这个事件仅仅是个建议，应用可以忽略，但是这样
     * 可能会导致消耗额外的缓冲
     *
     * <p>If {@code false}, {@link Listener#onReady()} will be called after {@code isReady()}
     * transitions to {@code true}.
     * 如果是 false，Listener#onReady 会在 isReady 变为 true 之后调用
     *
     * <p>This abstract class's implementation always returns {@code true}. Implementations generally
     * override the method.
     * 这个抽象类的实现总是会返回 true，实现通常重写这个方法
     */
    public boolean isReady() {
        return true;
    }

    /**
     * Close the call with the provided status. No further sending or receiving will occur. If {@link
     * Status#isOk} is {@code false}, then the call is said to have failed.
     * 根据提供的状态关闭调用，不会再发送或者接收请求，如果不是 OK 状态，这个调用就会失败
     *
     * <p>If no errors or cancellations are known to have occurred, then a {@link Listener#onComplete}
     * notification should be expected, independent of {@code status}. Otherwise {@link
     * Listener#onCancel} has been or will be called.
     * 如果没有已知的取消或者错误，那么期望有 Listener#onClomplete 通知，独立于状态，否则 Listener#onCancel 会被调用
     *
     * <p>Since {@link Metadata} is not thread-safe, the caller must not access (read or write) {@code
     * trailers} after this point.
     * 因为 Metadata 不是线程安全的，所以调用者在此之后不能再读或者写 trailers
     *
     * @throws IllegalStateException if call is already {@code close}d
     *                               如果调用已经被关闭
     */
    public abstract void close(Status status, Metadata trailers);

    /**
     * Returns {@code true} when the call is cancelled and the server is encouraged to abort
     * processing to save resources, since the client will not be processing any further methods.
     * Cancellations can be caused by timeouts, explicit cancel by client, network errors, and
     * similar.
     * 如果请求被取消了则返回 true，服务端应当丢弃处理以节省资源，因为客户端不会再处理任何方法，取消的原因可能是超时
     * 客户端取消，网络错误等
     *
     * <p>This method may safely be called concurrently from multiple threads.
     * 这个方法被多个线程并发调用是安全的
     */
    public abstract boolean isCancelled();

    /**
     * Enables per-message compression, if an encoding type has been negotiated.  If no message
     * encoding has been negotiated, this is a no-op. By default per-message compression is enabled,
     * but may not have any effect if compression is not enabled on the call.
     * 如果已协商编码类型，则使用每个消息的压缩；这里不会操作，默认每个消息的压缩是开启的，但是如果没有在调用上启用
     * 则没有任何作用
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
    public void setMessageCompression(boolean enabled) {
        // noop
    }

    /**
     * Sets the compression algorithm for this call.  If the server does not support the compression
     * algorithm, the call will fail.  This method may only be called before {@link #sendHeaders}.
     * The compressor to use will be looked up in the {@link CompressorRegistry}.  Default gRPC
     * servers support the "gzip" compressor.
     * 设置调用的压缩算法，如果 Server 端不支持压缩，这次调用将会失败，这个方法在 sendHeaders 之前调用，使用的
     * 压缩器会从 CompressorRegistry 中查找，默认的 gRPC 服务支持 gzip 压缩
     *
     * <p>It is safe to call this even if the client does not support the compression format chosen.
     * The implementation will handle negotiation with the client and may fall back to no compression.
     * 即使客户端不支持选择的压缩格式，也是安全的，实现应当处理和客户端的谈判，可能会回退到没有压缩
     *
     * @param compressor the name of the compressor to use.
     *                   使用的压缩器的名称
     * @throws IllegalArgumentException if the compressor name can not be found.
     *                                  如果没有发现名称对应的压缩器
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
    public void setCompression(String compressor) {
        // noop
    }

    /**
     * Returns properties of a single call.
     * 获取单个请求的属性
     *
     * <p>Attributes originate from the transport and can be altered by {@link ServerTransportFilter}.
     * 属性来源于 Transport，可能会被  ServerTransportFilter 修改
     *
     * @return non-{@code null} Attributes container
     * 非 null 的属性容器
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1779")
    @Grpc.TransportAttr
    public Attributes getAttributes() {
        return Attributes.EMPTY;
    }

    /**
     * Gets the authority this call is addressed to.
     * 获取调用请求的服务名称
     *
     * @return the authority string. {@code null} if not available.
     * 请求的服务名称，如果没有则为 null
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2924")
    @Nullable
    public String getAuthority() {
        return null;
    }

    /**
     * The {@link MethodDescriptor} for the call.
     * 调用的方法描述
     */
    public abstract MethodDescriptor<ReqT, RespT> getMethodDescriptor();
}
