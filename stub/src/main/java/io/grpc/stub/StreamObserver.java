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

package io.grpc.stub;

/**
 * Receives notifications from an observable stream of messages.
 * 从可观察的消息流中获取通知
 *
 * <p>It is used by both the client stubs and service implementations for sending or receiving
 * stream messages. It is used for all {@link io.grpc.MethodDescriptor.MethodType}, including
 * {@code UNARY} calls.  For outgoing messages, a {@code StreamObserver} is provided by the GRPC
 * library to the application. For incoming messages, the application implements the
 * {@code StreamObserver} and passes it to the GRPC library for receiving.
 * 用于客户端 Stub 和 Server 端的服务实现发送或接收消息，被所有的 MethodType 使用，包括 Unary 调用，对于
 * 出站的请求，StreamObserver 由 gRPC 库为应用提供，对于入站的请求，由应用实现 StreamObserver 并且传递
 * 给 gRPC 库
 *
 * <p>Implementations are not required to be thread-safe (but should be
 * <a href="http://www.ibm.com/developerworks/library/j-jtp09263/">thread-compatible</a>).
 * Separate {@code StreamObserver}s do
 * not need to be synchronized together; incoming and outgoing directions are independent.
 * Since individual {@code StreamObserver}s are not thread-safe, if multiple threads will be
 * writing to a {@code StreamObserver} concurrently, the application must synchronize calls.
 * 并不一定要求实现是线程安全的，但要求是线程兼容的，单独的 StreamObserver 不需要一起同步
 * 传入和传出的方向是独立的，因为独立的 StreamObserver 不是线程安全的，如果有多个线程并发写入，应用必须保证
 * 同步调用
 */
public interface StreamObserver<V> {
    /**
     * Receives a value from the stream.
     * 从流中接收值
     *
     * <p>Can be called many times but is never called after {@link #onError(Throwable)} or {@link
     * #onCompleted()} are called.
     * 可以被调用多次，但是在调用 onError 或 onComplete 之后不能再调用
     *
     * <p>Unary calls must invoke onNext at most once.  Clients may invoke onNext at most once for
     * server streaming calls, but may receive many onNext callbacks.  Servers may invoke onNext at
     * most once for client streaming calls, but may receive many onNext callbacks.
     * Unary 调用最多只能调用一次 onNext；对于 Server 端流请求，客户端只能调用一次 onNext，但是可以接收多次
     * onNext 回调；对于客户端的流请求，Server 只能调用一次 onNext，但是可以接收多次 onNext 回调
     *
     * <p>If an exception is thrown by an implementation the caller is expected to terminate the
     * stream by calling {@link #onError(Throwable)} with the caught exception prior to
     * propagating it.
     * 如果实现中有抛出异常，则期望使用捕获的异常终止流
     *
     * @param value the value passed to the stream
     *              传递给流的值
     */
    void onNext(V value);

    /**
     * Receives a terminating error from the stream.
     * 从流中接收终止错误
     *
     * <p>May only be called once and if called it must be the last method called. In particular if an
     * exception is thrown by an implementation of {@code onError} no further calls to any method are
     * allowed.
     * 只能调用一次，只能作为最后一个方法调用，特别是，如果调用 onError 触发了异常，则不允许有任何方法调用
     *
     * <p>{@code t} should be a {@link io.grpc.StatusException} or {@link
     * io.grpc.StatusRuntimeException}, but other {@code Throwable} types are possible. Callers should
     * generally convert from a {@link io.grpc.Status} via {@link io.grpc.Status#asException()} or
     * {@link io.grpc.Status#asRuntimeException()}. Implementations should generally convert to a
     * {@code Status} via {@link io.grpc.Status#fromThrowable(Throwable)}.
     * 异常应当是 StatusException 或 StatusRuntimeException， 但是其他异常也有可能，调用者应当将状态通过
     * asException 或者 asRuntimeException 转为异常；实现应当将异常转为状态
     *
     * @param t the error occurred on the stream
     *          流中发生的错误
     */
    void onError(Throwable t);

    /**
     * Receives a notification of successful stream completion.
     * 流成功完成的回调
     *
     * <p>May only be called once and if called it must be the last method called. In particular if an
     * exception is thrown by an implementation of {@code onCompleted} no further calls to any method
     * are allowed.
     * 只能被调用一次，只能作为最后一个方法调用，如果 onCompleted 抛出异常，则不允许有任何方法调用
     */
    void onCompleted();
}
