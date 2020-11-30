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

import javax.annotation.Nullable;

/**
 * A refinement of {@link CallStreamObserver} that allows for lower-level interaction with
 * client calls.
 * CallStreamObserver 的改进，允许更低级别的客户端调用交互
 *
 * <p>Like {@code StreamObserver}, implementations are not required to be thread-safe; if multiple
 * threads will be writing to an instance concurrently, the application must synchronize its calls.
 * 和 StreamObserver 一样，不要求实现是线程安全的，如果有多个线程并发写入同一个实例，应用应当确保同步调用
 *
 * <p>DO NOT MOCK: The API is too complex to reliably mock. Use InProcessChannelBuilder to create
 * "real" RPCs suitable for testing and make a fake for the server-side.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1788")
public abstract class ClientCallStreamObserver<V> extends CallStreamObserver<V> {
    /**
     * Prevent any further processing for this {@code ClientCallStreamObserver}. No further messages
     * will be received. The server is informed of cancellations, but may not stop processing the
     * call. Cancelling an already
     * {@code cancel()}ed {@code ClientCallStreamObserver} has no effect.
     * 阻止 ClientCallStreamObserver 有其他调用，不会再接收到新的消息，服务器通知取消，但是不会停止处理调用，
     * 再次取消已经取消了的 ClientCallStreamObserver 没有影响
     *
     * <p>No other methods on this class can be called after this method has been called.
     * 当调用这个方法之后这个类中的其他方法不会被再调用
     *
     * <p>It is recommended that at least one of the arguments to be non-{@code null}, to provide
     * useful debug information. Both argument being null may log warnings and result in suboptimal
     * performance. Also note that the provided information will not be sent to the server.
     * 建议至少有一个参数不为 null，用于提供有效的调试信息，两个参数都为空可能会告警并导致性能不佳，同时需要注意提供
     * 的信息不会发送给 Server
     *
     * @param message if not {@code null}, will appear as the description of the CANCELLED status
     *                如果不为 null，会以描述出现在取消状态中
     * @param cause   if not {@code null}, will appear as the cause of the CANCELLED status
     *                如果不为 null，会以 cause 出现在取消状态中
     */
    public abstract void cancel(@Nullable String message, @Nullable Throwable cause);

    /**
     * Swaps to manual flow control where no message will be delivered to {@link
     * StreamObserver#onNext(Object)} unless it is {@link #request request()}ed. Since {@code
     * request()} may not be called before the call is started, a number of initial requests may be
     * specified.
     * 切换到手动流控，新的消息不会通过 StreamObserver#onNext(Object) 投递，除非主动调用 request() 获取；
     * 因为 request() 在这个请求之前不会调用，可以指定一定数量的初始请求
     *
     * <p>This method may only be called during {@link ClientResponseObserver#beforeStart
     * ClientResponseObserver.beforeStart()}.
     * 这个方法只能在 ClientResponseObserver.beforeStart() 中间调用
     *
     * <p>This API is still a work in-progress and may change in the future.
     */
    public void disableAutoRequestWithInitial(int request) {
        throw new UnsupportedOperationException();
    }
}
