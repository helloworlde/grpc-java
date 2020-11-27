/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.stub;

import io.grpc.ExperimentalApi;

/**
 * A refinement of StreamObserver provided by the GRPC runtime to the application (the client or
 * the server) that allows for more complex interactions with call behavior.
 * gRPC 提供给应用的更细化的 StreamObserver，允许更复杂的相互调用行为
 *
 * <p>In any call there are logically four {@link StreamObserver} implementations:
 * <ul>
 *   <li>'inbound', client-side - which the GRPC runtime calls when it receives messages from
 *   the server. This is implemented by the client application and passed into a service method
 *   on a stub object.
 *   </li>
 *   <li>'outbound', client-side - which the GRPC runtime provides to the client application and the
 *   client uses this {@code StreamObserver} to send messages to the server.
 *   </li>
 *   <li>'inbound', server-side - which the GRPC runtime calls when it receives messages from
 *   the client. This is implemented by the server application and returned from service
 *   implementations of client-side streaming and bidirectional streaming methods.
 *   </li>
 *   <li>'outbound', server-side - which the GRPC runtime provides to the server application and
 *   the server uses this {@code StreamObserver} to send messages (responses) to the client.
 *   </li>
 * </ul>
 * <p>
 * 任意类型的调用有四个实现：
 * 1. 客户端的入站，从 Server 端接收到消息时调用，由客户端应用实现，并传递给 Stub 对象的服务方法
 * 2. 客户端的出站，由 gRPC 库实现，提供给客户端程序使用 StreamObserver 发送消息给 Server
 * 3. 服务端的入站，当服务端接收到客户端的消息时调用，由服务端应用实现，并从客户端流和双向流方法的实现返回
 * 4. 服务端的出站，由 gRPC 库提供 StreamObserver 用于服务端发送消息给客户端
 *
 * <p>Implementations of this class represent the 'outbound' message streams. The client-side
 * one is {@link ClientCallStreamObserver} and the service-side one is
 * {@link ServerCallStreamObserver}.
 * 这个类的实现代表出站的消息流，客户端的实现是 ClientCallStreamObserver，服务端的实现是 ServerCallStreamObserver
 *
 * <p>Like {@code StreamObserver}, implementations are not required to be thread-safe; if multiple
 * threads will be writing to an instance concurrently, the application must synchronize its calls.
 * 和 StreamObserver 一样，实现不要求是线程安全的，如果有多个线程并发写入，应用应当确保同步调用
 *
 * <p>DO NOT MOCK: The API is too complex to reliably mock. Use InProcessChannelBuilder to create
 * "real" RPCs suitable for testing.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1788")
public abstract class CallStreamObserver<V> implements StreamObserver<V> {

    /**
     * If {@code true}, indicates that the observer is capable of sending additional messages
     * without requiring excessive buffering internally. This value is just a suggestion and the
     * application is free to ignore it, however doing so may result in excessive buffering within the
     * observer.
     * 如果是 true，代表 observer 已经准备好发送附加的消息而不需要额外的内部缓冲，这仅仅是一个建议，应用可以忽略，
     * 然而这样可能会导致 observer 有过多的缓冲
     *
     * <p>If {@code false}, the runnable passed to {@link #setOnReadyHandler} will be called after
     * {@code isReady()} transitions to {@code true}.
     * 如果是 false，传递给 setOnReadyHandler 的任务将会在 isReady 过渡为 true 后调用
     */
    public abstract boolean isReady();

    /**
     * Set a {@link Runnable} that will be executed every time the stream {@link #isReady()} state
     * changes from {@code false} to {@code true}.  While it is not guaranteed that the same
     * thread will always be used to execute the {@link Runnable}, it is guaranteed that executions
     * are serialized with calls to the 'inbound' {@link StreamObserver}.
     * 设置一个每次 isReady 状态由 false 变为 true 后都会执行的任务，并不能保证任务总是由同一个线程执行，
     * 但是可以保证可以通过调用入站 StreamObserver 来序列化执行
     *
     * <p>On client-side this method may only be called during {@link
     * ClientResponseObserver#beforeStart}. On server-side it may only be called during the initial
     * call to the application, before the service returns its {@code StreamObserver}.
     * 在客户端，这个方法只有在 ClientResponseObserver#beforeStart 时会被调用，在 Server 端，只有在应用初始
     * 化，在服务返回 StreamObserver 之前调用
     *
     * <p>Because there is a processing delay to deliver this notification, it is possible for
     * concurrent writes to cause {@code isReady() == false} within this callback. Handle "spurious"
     * notifications by checking {@code isReady()}'s current value instead of assuming it is now
     * {@code true}. If {@code isReady() == false} the normal expectations apply, so there would be
     * <em>another</em> {@code onReadyHandler} callback.
     * 因为在投递通知时有处理延迟，在并发写入时可能会造成 isReady 变为 false，可以 isReady 状态而不是假定为 true，
     * 如果 isReady 是 false 是正常期望，那么应当有另外一个 onReadyHandler 回调
     *
     * @param onReadyHandler to call when peer is ready to receive more messages.
     *                       当连接准备好接收更多消息的时候调用
     */
    public abstract void setOnReadyHandler(Runnable onReadyHandler);

    /**
     * Disables automatic flow control where a token is returned to the peer after a call
     * to the 'inbound' {@link io.grpc.stub.StreamObserver#onNext(Object)} has completed. If disabled
     * an application must make explicit calls to {@link #request} to receive messages.
     * 禁用自动流控，在该调用中，当入站的请求处理完成后，会返回 token 给对方，禁用之后，应用必须显式调用
     * request 才能接收到消息
     *
     * <p>On client-side this method may only be called during {@link
     * ClientResponseObserver#beforeStart}. On server-side it may only be called during the initial
     * call to the application, before the service returns its {@code StreamObserver}.
     * 在客户端这个方法只会在客户端启动时通过 ClientResponseObserver#beforeStart 调用，在服务端，只有
     * 在应用初始化，返回服务的 StreamObserver 之前调用
     *
     * <p>Note that for cases where the runtime knows that only one inbound message is allowed
     * calling this method will have no effect and the runtime will always permit one and only
     * one message. This is true for:
     * <ul>
     *   <li>{@link io.grpc.MethodDescriptor.MethodType#UNARY} operations on both the
     *   client and server.
     *   </li>
     *   <li>{@link io.grpc.MethodDescriptor.MethodType#CLIENT_STREAMING} operations on the client.
     *   </li>
     *   <li>{@link io.grpc.MethodDescriptor.MethodType#SERVER_STREAMING} operations on the server.
     *   </li>
     * </ul>
     * </p>
     * 注意，对于运行时知道仅允许一条入站消息调用此方法无效的情况，并且运行时将始终仅允许一条消息
     * 1. 服务端和客户端的 UNARY 调用
     * 2. 客户端的 CLIENT_STREAMING 调用
     * 3. 服务端的 SERVER_STREAMING 调用
     *
     * <p>This API is being replaced, but is not yet deprecated. On server-side it being replaced
     * with {@link ServerCallStreamObserver#disableAutoRequest}. On client-side {@link
     * ClientCallStreamObserver#disableAutoRequestWithInitial disableAutoRequestWithInitial(1)}.
     */
    public abstract void disableAutoInboundFlowControl();

    /**
     * Requests the peer to produce {@code count} more messages to be delivered to the 'inbound'
     * {@link StreamObserver}.
     * 要求对等端产生指定数量的消息，投递给入站的 StreamObserver
     *
     * <p>This method is safe to call from multiple threads without external synchronization.
     * 这个方法多次调用是安全的，不需要额外的同步
     *
     * @param count more messages
     */
    public abstract void request(int count);

    /**
     * Sets message compression for subsequent calls to {@link #onNext}.
     * 为后续的调用设置消息压缩
     *
     * @param enable whether to enable compression.
     *               是否开启压缩
     */
    public abstract void setMessageCompression(boolean enable);
}
