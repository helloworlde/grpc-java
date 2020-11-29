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
 * A refinement of {@link CallStreamObserver} to allows for interaction with call
 * cancellation events on the server side.
 * 允许与 Server 端调用中取消事件的优化的 CallStreamObserver
 *
 * <p>Like {@code StreamObserver}, implementations are not required to be thread-safe; if multiple
 * threads will be writing to an instance concurrently, the application must synchronize its calls.
 * 和 StreamObserver 一样，不要求实现是线程安全的，如果多个线程并发写入，应用需要确保调用是同步的
 *
 * <p>DO NOT MOCK: The API is too complex to reliably mock. Use InProcessChannelBuilder to create
 * "real" RPCs suitable for testing and interact with the server using a normal client stub.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1788")
public abstract class ServerCallStreamObserver<V> extends CallStreamObserver<V> {

    /**
     * Returns {@code true} when the call is cancelled and the server is encouraged to abort
     * processing to save resources, since the client will not be processing any further methods.
     * Cancellations can be caused by timeouts, explicit cancellation by client, network errors, and
     * similar.
     * 当调用被取消时返回 true，server 应当取消继续处理以节约资源，因为客户端不会再处理任何方法，取消可能是因为超时，
     * 客户端异常，网络原因等
     *
     * <p>This method may safely be called concurrently from multiple threads.
     * 这个方法可以被多个线程安全的的并发调用
     */
    public abstract boolean isCancelled();

    /**
     * Sets a {@link Runnable} to be called if the call is cancelled and the server is encouraged to
     * abort processing to save resources, since the client will not process any further messages.
     * Cancellations can be caused by timeouts, explicit cancellation by the client, network errors,
     * etc.
     * 设置一个回调任务，当调用被取消时执行，server 应当取消继续处理节省资源，因为客户端不会再处理任何消息，
     * 取消可能是因为超时，客户端异常，网络原因等
     *
     * <p>It is guaranteed that execution of the {@link Runnable} is serialized with calls to the
     * 'inbound' {@link StreamObserver}. That also means that the callback will be delayed if other
     * callbacks are running; if one of those other callbacks runs for a significant amount of time
     * it can poll {@link #isCancelled()}, which is not delayed.
     * 确保通过调用入站 StreamObserver 序列化 Runnable 的执行，同时也意味着如果有其他的回调在执行，可能会延迟，
     * 如果其中一个回调执行了很长时间，它可以调用 isCancelled()，而不会延迟
     *
     * <p>This method may only be called during the initial call to the application, before the
     * service returns its {@code StreamObserver}.
     * 这个方法只能在程序初始调用期间，在服务返回它的 StreamObserver 之前
     *
     * <p>Setting the onCancelHandler will suppress the on-cancel exception thrown by
     * {@link #onNext}.
     * 设置取消回调可能会抑制 onNext 抛出的取消异常
     *
     * @param onCancelHandler to call when client has cancelled the call.
     */
    public abstract void setOnCancelHandler(Runnable onCancelHandler);

    /**
     * Sets the compression algorithm to use for the call. May only be called before sending any
     * messages. Default gRPC servers support the "gzip" compressor.
     * 设置用于调用的压缩算法，可能会在发送消息之前调用，默认的 gRPC 服务端支持 gzip 压缩
     *
     * <p>It is safe to call this even if the client does not support the compression format chosen.
     * The implementation will handle negotiation with the client and may fall back to no compression.
     * 即使客户端不支持压缩调用也是安全的，实现会处理与客户端的谈判，如果没有压缩会倒退到没有压缩
     *
     * @param compression the compression algorithm to use.
     *                    使用的压缩算法
     * @throws IllegalArgumentException if the compressor name can not be found.
     */
    public abstract void setCompression(String compression);

    /**
     * Swaps to manual flow control where no message will be delivered to {@link
     * StreamObserver#onNext(Object)} unless it is {@link #request request()}ed.
     * 切换到手动流控，除非显式调用 request，否则不会有消息投递
     *
     * <p>It may only be called during the initial call to the application, before the service returns
     * its {@code StreamObserver}.
     * 可能只有在应用初始调用的时候调用，在服务返回 StreamObserver 之前
     *
     * <p>Note that for cases where the message is received before the service handler is invoked,
     * this method will have no effect. This is true for:
     *
     * <ul>
     *   <li>{@link io.grpc.MethodDescriptor.MethodType#UNARY} operations.</li>
     *   <li>{@link io.grpc.MethodDescriptor.MethodType#SERVER_STREAMING} operations.</li>
     * </ul>
     * </p>
     * 需要注意用于接收到消息，在服务处理器调用之前调用方法是没有作用的，用于：
     * 1. 调用类型是 UNARY 的操作
     * 2. 调用类型是 SERVER_STREAMING 的操作
     *
     * <p>This API is still a work in-progress and may change in the future.
     */
    public void disableAutoRequest() {
        throw new UnsupportedOperationException();
    }
}
