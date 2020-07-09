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
 * An instance of a call to a remote method. A call will send zero or more
 * request messages to the server and receive zero or more response messages back.
 * 用于调用远程方法的实例，一次调用会向 server 端发送一个或多个请求消息，并接收相应的响应
 *
 * <p>Instances are created
 * by a {@link Channel} and used by stubs to invoke their remote behavior.
 * 实例由 Channel 创建，并通过 stub 执行远程行为
 *
 * <p>More advanced usages may consume this interface directly as opposed to using a stub. Common
 * reasons for doing so would be the need to interact with flow-control or when acting as a generic
 * proxy for arbitrary operations.
 * 与使用 stub 相比，更高级的用法可以直接使用此接口，这样做的常见原因是需要与流控制交互，或者充当任意操作的通用代理
 *
 * <p>{@link #start} must be called prior to calling any other methods, with the exception of
 * {@link #cancel}. Whereas {@link #cancel} must not be followed by any other methods,
 * but can be called more than once, while only the first one has effect.
 * 调用其他方法之前，得先调用 start，cancel 之后不会执行其他方法，多次调用 cancel，只有第一次会生效
 *
 * <p>No generic method for determining message receipt or providing acknowledgement is provided.
 * Applications are expected to utilize normal payload messages for such signals, as a response
 * naturally acknowledges its request.
 * 没有提供确定消息接收或提供确认的通用方法，应用程序应利用此类信号的正常有效信息，响应会承接请求
 *
 * <p>Methods are guaranteed to be non-blocking. Not thread-safe except for {@link #request}, which
 * may be called from any thread.
 * 方法是非阻塞的，除了 request 其他方法是线程不安全的，request 可以被多个线程调用
 *
 * <p>There is no interaction between the states on the {@link Listener Listener} and {@link
 * ClientCall}, i.e., if {@link Listener#onClose Listener.onClose()} is called, it has no bearing on
 * the permitted operations on {@code ClientCall} (but it may impact whether they do anything).
 * Listener，ClientCall 的状态之间没有交互，如当调用 Listener.onClose()，与 ClientCall 之间没有关系，
 * 但这可能会影响他们是否采取行动
 *
 * <p>There is a race between {@link #cancel} and the completion/failure of the RPC in other ways.
 * If {@link #cancel} won the race, {@link Listener#onClose Listener.onClose()} is called with
 * {@link Status#CANCELLED CANCELLED}. Otherwise, {@link Listener#onClose Listener.onClose()} is
 * called with whatever status the RPC was finished. We ensure that at most one is called.
 * 在 cancel 和 RPC 的完成/失败之间存在竞争，如果先调用 cancel 成功， Listener.onClose() 会被置为 CANCELLED，
 * 否则，Listener.onClose() 的状态根据 RPC 返回的结果，最多调用一次
 *
 * <h3>Usage examples</h3>
 * <h4>Simple Unary (1 request, 1 response) RPC</h4>
 * <pre>
 *   call = channel.newCall(unaryMethod, callOptions);
 *   call.start(listener, headers);
 *   call.sendMessage(message);
 *   call.halfClose();
 *   call.request(1);
 *   // wait for listener.onMessage()
 * </pre>
 *
 * <h4>Flow-control in Streaming RPC</h4>
 * Streaming 调用中的流控制
 *
 * <p>The following snippet demonstrates a bi-directional streaming case, where the client sends
 * requests produced by a fictional <code>makeNextRequest()</code> in a flow-control-compliant
 * manner, and notifies gRPC library to receive additional response after one is consumed by
 * a fictional <code>processResponse()</code>.
 * 下面的代码片段演示了一个双向流情况，client 端通过虚构的 makeNextRequest 以流的方式发送请求，
 * 当消费完后通过 processResponse 方法通知 gRPC 库接收下一个响应
 *
 * <p><pre>
 *   call = channel.newCall(bidiStreamingMethod, callOptions);
 *   listener = new ClientCall.Listener&lt;FooResponse&gt;() {
 *     &#64;Override
 *     public void onMessage(FooResponse response) {
 *       processResponse(response);
 *       // Notify gRPC to receive one additional response.
 *       call.request(1);
 *     }
 *
 *     &#64;Override
 *     public void onReady() {
 *       while (call.isReady()) {
 *         FooRequest nextRequest = makeNextRequest();
 *         if (nextRequest == null) {  // No more requests to send
 *           call.halfClose();
 *           return;
 *         }
 *         call.sendMessage(nextRequest);
 *       }
 *     }
 *   }
 *   call.start(listener, headers);
 *   // Notify gRPC to receive one response. Without this line, onMessage() would never be called.
 *   call.request(1);
 * </pre>
 *
 * <p>DO NOT MOCK: Use InProcessServerBuilder and make a test server instead.
 *
 * @param <ReqT>  type of message sent one or more times to the server.
 * @param <RespT> type of message received one or more times from the server.
 */
public abstract class ClientCall<ReqT, RespT> {
    /**
     * Callbacks for receiving metadata, response messages and completion status from the server.
     *
     * <p>Implementations are free to block for extended periods of time. Implementations are not
     * required to be thread-safe.
     */
    public abstract static class Listener<T> {

        /**
         * The response headers have been received. Headers always precede messages.
         *
         * <p>Since {@link Metadata} is not thread-safe, the caller must not access (read or write)
         * {@code headers} after this point.
         *
         * @param headers containing metadata sent by the server at the start of the response.
         */
        public void onHeaders(Metadata headers) {
        }

        /**
         * A response message has been received. May be called zero or more times depending on whether
         * the call response is empty, a single message or a stream of messages.
         *
         * @param message returned by the server
         */
        public void onMessage(T message) {
        }

        /**
         * The ClientCall has been closed. Any additional calls to the {@code ClientCall} will not be
         * processed by the server. No further receiving will occur and no further notifications will be
         * made.
         *
         * <p>Since {@link Metadata} is not thread-safe, the caller must not access (read or write)
         * {@code trailers} after this point.
         *
         * <p>If {@code status} returns false for {@link Status#isOk()}, then the call failed.
         * An additional block of trailer metadata may be received at the end of the call from the
         * server. An empty {@link Metadata} object is passed if no trailers are received.
         *
         * @param status   the result of the remote call.
         * @param trailers metadata provided at call completion.
         */
        public void onClose(Status status, Metadata trailers) {
        }

        /**
         * This indicates that the ClientCall may now be capable of sending additional messages (via
         * {@link #sendMessage}) without requiring excessive buffering internally. This event is
         * just a suggestion and the application is free to ignore it, however doing so may
         * result in excessive buffering within the ClientCall.
         *
         * <p>Because there is a processing delay to deliver this notification, it is possible for
         * concurrent writes to cause {@code isReady() == false} within this callback. Handle "spurious"
         * notifications by checking {@code isReady()}'s current value instead of assuming it is now
         * {@code true}. If {@code isReady() == false} the normal expectations apply, so there would be
         * <em>another</em> {@code onReady()} callback.
         *
         * <p>If the type of a call is either {@link MethodDescriptor.MethodType#UNARY} or
         * {@link MethodDescriptor.MethodType#SERVER_STREAMING}, this callback may not be fired. Calls
         * that send exactly one message should not await this callback.
         */
        public void onReady() {
        }
    }

    /**
     * Start a call, using {@code responseListener} for processing response messages.
     * 开始一次调用，通过 responseListener 处理返回响应
     *
     * <p>It must be called prior to any other method on this class, except for {@link #cancel} which
     * may be called at any time.
     * 必须先于这个类的其他方法调用，除了 cancel 可以在任何时候调用
     *
     * <p>Since {@link Metadata} is not thread-safe, the caller must not access (read or write) {@code
     * headers} after this point.
     * Metadata 不是线程安全的，调用方在调用 start 之后不能再访问 Metadata
     *
     * @param responseListener receives response messages
     *                         接收响应
     * @param headers          which can contain extra call metadata, e.g. authentication credentials.
     *                         包含额外的元数据，如鉴权
     * @throws IllegalStateException if a method (including {@code start()}) on this class has been
     *                               called.
     */
    public abstract void start(Listener<RespT> responseListener, Metadata headers);

    /**
     * Requests up to the given number of messages from the call to be delivered to
     * {@link Listener#onMessage(Object)}. No additional messages will be delivered.
     * 请求数量不超过要传递到 Listener#onMessage 调用的给定消息数量，不会发送附加信息
     *
     * <p>Message delivery is guaranteed to be sequential in the order received. In addition, the
     * listener methods will not be accessed concurrently. While it is not guaranteed that the same
     * thread will always be used, it is guaranteed that only a single thread will access the listener
     * at a time.
     * 消息传递保证按照接收到的顺序顺序进行，listener 的方法不会被并发访问，虽然不能保证始终使用相同的线程，
     * 它保证一次只有一个线程访问监听器
     *
     * <p>If it is desired to bypass inbound flow control, a very large number of messages can be
     * specified (e.g. {@link Integer#MAX_VALUE}).
     * 如果想要绕过入站流量控制，则设置一个非常的大的消息数量，如 Integer#MAX_VALUE
     *
     * <p>If called multiple times, the number of messages able to delivered will be the sum of the
     * calls.
     * 如果多次调用，能够传递的消息数量是调用的总和
     *
     * <p>This method is safe to call from multiple threads without external synchronization.
     * 即使外部调用没有同步控制，多个线程调用依然是线程安全的
     *
     * @param numMessages the requested number of messages to be delivered to the listener. Must be
     *                    non-negative.
     *                    要传递给 listener 的请求消息数量，不能为负数
     * @throws IllegalStateException    if call is not {@code start()}ed
     * @throws IllegalArgumentException if numMessages is negative
     */
    public abstract void request(int numMessages);

    /**
     * Prevent any further processing for this {@code ClientCall}. No further messages may be sent or
     * will be received. The server is informed of cancellations, but may not stop processing the
     * call. Cancellation is permitted even if previously {@link #halfClose}d. Cancelling an already
     * {@code cancel()}ed {@code ClientCall} has no effect.
     *
     * <p>No other methods on this class can be called after this method has been called.
     *
     * <p>It is recommended that at least one of the arguments to be non-{@code null}, to provide
     * useful debug information. Both argument being null may log warnings and result in suboptimal
     * performance. Also note that the provided information will not be sent to the server.
     *
     * @param message if not {@code null}, will appear as the description of the CANCELLED status
     * @param cause   if not {@code null}, will appear as the cause of the CANCELLED status
     */
    public abstract void cancel(@Nullable String message, @Nullable Throwable cause);

    /**
     * Close the call for request message sending. Incoming response messages are unaffected.  This
     * should be called when no more messages will be sent from the client.
     * 关闭请求的消息发送调用，返回的响应不受影响，当客户端不会发送更多消息时调用
     *
     * @throws IllegalStateException if call is already {@code halfClose()}d or {@link #cancel}ed
     */
    public abstract void halfClose();

    /**
     * Send a request message to the server. May be called zero or more times depending on how many
     * messages the server is willing to accept for the operation.
     * 向 server 端发送请求消息，可能被调用零次或更多次，取决于服务器接受多少消息
     *
     * @param message message to be sent to the server.
     * @throws IllegalStateException if call is {@link #halfClose}d or explicitly {@link #cancel}ed
     */
    public abstract void sendMessage(ReqT message);

    /**
     * If {@code true}, indicates that the call is capable of sending additional messages
     * without requiring excessive buffering internally. This event is
     * just a suggestion and the application is free to ignore it, however doing so may
     * result in excessive buffering within the call.
     *
     * <p>If {@code false}, {@link Listener#onReady()} will be called after {@code isReady()}
     * transitions to {@code true}.
     *
     * <p>If the type of the call is either {@link MethodDescriptor.MethodType#UNARY} or
     * {@link MethodDescriptor.MethodType#SERVER_STREAMING}, this method may persistently return
     * false. Calls that send exactly one message should not check this method.
     *
     * <p>This abstract class's implementation always returns {@code true}. Implementations generally
     * override the method.
     */
    public boolean isReady() {
        return true;
    }

    /**
     * Enables per-message compression, if an encoding type has been negotiated.  If no message
     * encoding has been negotiated, this is a no-op. By default per-message compression is enabled,
     * but may not have any effect if compression is not enabled on the call.
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1703")
    public void setMessageCompression(boolean enabled) {
        // noop
    }

    /**
     * Returns additional properties of the call. May only be called after {@link Listener#onHeaders}
     * or {@link Listener#onClose}. If called prematurely, the implementation may throw {@code
     * IllegalStateException} or return arbitrary {@code Attributes}.
     *
     * @return non-{@code null} attributes
     * @throws IllegalStateException (optional) if called before permitted
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2607")
    @Grpc.TransportAttr
    public Attributes getAttributes() {
        return Attributes.EMPTY;
    }
}
