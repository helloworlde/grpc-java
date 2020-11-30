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

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Utility functions for processing different call idioms. We have one-to-one correspondence
 * between utilities in this class and the potential signatures in a generated stub class so
 * that the runtime can vary behavior without requiring regeneration of the stub.
 * 处理不同类型调用的工具方法，此类的工具方法和调用类型一一对应，因此不必在运行时再生成 Stub
 */
public final class ClientCalls {

    private static final Logger logger = Logger.getLogger(ClientCalls.class.getName());

    // Prevent instantiation
    private ClientCalls() {
    }

    /**
     * Executes a unary call with a response {@link StreamObserver}.  The {@code call} should not be
     * already started.  After calling this method, {@code call} should no longer be used.
     * 使用 StreamObserver 执行 unary 调用，call 不应当已经开始，调用这个方法之后 call 不应当再使用
     *
     * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
     * {@code beforeStart()} will be called.
     * 如果提供的 responseObserver 是 ClientResponseObserver 的实例，beforeStart() 会被调用
     */
    public static <ReqT, RespT> void asyncUnaryCall(ClientCall<ReqT, RespT> call,
                                                    ReqT req,
                                                    StreamObserver<RespT> responseObserver) {
        asyncUnaryRequestCall(call, req, responseObserver, false);
    }

    /**
     * Executes a server-streaming call with a response {@link StreamObserver}.  The {@code call}
     * should not be already started.  After calling this method, {@code call} should no longer be
     * used.
     * 使用 StreamObserver 执行 server 端流的调用, call 不应当已经开始，调用这个方法之后，call 不应当再被使用
     *
     * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
     * {@code beforeStart()} will be called.
     * 如果提供的 responseObserver 是 ClientResponseObserver 的实例，beforeStart() 会被调用
     */
    public static <ReqT, RespT> void asyncServerStreamingCall(ClientCall<ReqT, RespT> call,
                                                              ReqT req,
                                                              StreamObserver<RespT> responseObserver) {
        asyncUnaryRequestCall(call, req, responseObserver, true);
    }

    /**
     * Executes a client-streaming call returning a {@link StreamObserver} for the request messages.
     * The {@code call} should not be already started.  After calling this method, {@code call}
     * should no longer be used.
     * 执行客户端流调用，为请求的消息返回 StreamObserver，call 不应当已经开始，在调用这个方法之后不应当再被使用
     *
     * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
     * {@code beforeStart()} will be called.
     * 如果提供的 responseObserver 是 ClientResponseObserver 的实例，beforeStart() 会被调用
     *
     * @return request stream observer. It will extend {@link ClientCallStreamObserver}
     * 请求流观察器，会继承 ClientCallStreamObserver
     */
    public static <ReqT, RespT> StreamObserver<ReqT> asyncClientStreamingCall(ClientCall<ReqT, RespT> call,
                                                                              StreamObserver<RespT> responseObserver) {
        return asyncStreamingRequestCall(call, responseObserver, false);
    }

    /**
     * Executes a bidirectional-streaming call.  The {@code call} should not be already started.
     * After calling this method, {@code call} should no longer be used.
     * 执行双向流调用，call 不应当已经开始，在调用这个方法之后不应当再被使用
     *
     * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
     * {@code beforeStart()} will be called.
     * 如果提供的 responseObserver 是 ClientResponseObserver 的实例，beforeStart() 会被调用
     *
     * @return request stream observer. It will extend {@link ClientCallStreamObserver}
     * 请求流观察器，会继承 ClientCallStreamObserver
     */
    public static <ReqT, RespT> StreamObserver<ReqT> asyncBidiStreamingCall(ClientCall<ReqT, RespT> call,
                                                                            StreamObserver<RespT> responseObserver) {
        return asyncStreamingRequestCall(call, responseObserver, true);
    }

    /**
     * Executes a unary call and blocks on the response.  The {@code call} should not be already
     * started.  After calling this method, {@code call} should no longer be used.
     * <p>
     * 执行 unary 调用并阻塞等待响应，call 在此之前不应当开始，调用这个方法之后，call 不应当再使用
     *
     * @return the single response message 单个的响应
     * @throws StatusRuntimeException on error
     */
    public static <ReqT, RespT> RespT blockingUnaryCall(ClientCall<ReqT, RespT> call, ReqT req) {
        try {
            return getUnchecked(futureUnaryCall(call, req));
        } catch (RuntimeException e) {
            throw cancelThrow(call, e);
        } catch (Error e) {
            throw cancelThrow(call, e);
        }
    }

    /**
     * Executes a unary call and blocks on the response.  The {@code call} should not be already
     * started.  After calling this method, {@code call} should no longer be used.
     * <p>
     * 执行 UNARY 调用，等待返回，当调用完后，call不应该再调用
     *
     * @return the single response message.
     * @throws StatusRuntimeException on error
     */
    public static <ReqT, RespT> RespT blockingUnaryCall(Channel channel,
                                                        MethodDescriptor<ReqT, RespT> method,
                                                        CallOptions callOptions,
                                                        ReqT req) {
        // 构建任务队列和单线程的线程池
        ThreadlessExecutor executor = new ThreadlessExecutor();
        boolean interrupt = false;
        // 创建新的调用的 ClientCall，指定了调用类型和执行器
        ClientCall<ReqT, RespT> call = channel.newCall(method, callOptions.withOption(ClientCalls.STUB_TYPE_OPTION, StubType.BLOCKING)
                                                                          .withExecutor(executor));
        try {
            // 执行调用，发出请求
            ListenableFuture<RespT> responseFuture = futureUnaryCall(call, req);
            while (!responseFuture.isDone()) {
                try {
                    executor.waitAndDrain();
                } catch (InterruptedException e) {
                    interrupt = true;
                    call.cancel("Thread interrupted", e);
                    // Now wait for onClose() to be called, so interceptors can clean up
                }
            }
            return getUnchecked(responseFuture);
        } catch (RuntimeException e) {
            // Something very bad happened. All bets are off; it may be dangerous to wait for onClose().
            throw cancelThrow(call, e);
        } catch (Error e) {
            // Something very bad happened. All bets are off; it may be dangerous to wait for onClose().
            throw cancelThrow(call, e);
        } finally {
            if (interrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Executes a server-streaming call returning a blocking {@link Iterator} over the
     * response stream.  The {@code call} should not be already started.  After calling this method,
     * {@code call} should no longer be used.
     * 执行 server 端流调用，返回阻塞的响应流的迭代器，call 不应当已经开始，调用这个方法之后不应当再使用
     *
     * <p>The returned iterator may throw {@link StatusRuntimeException} on error.
     * 返回的迭代器可能会在错误时抛出 StatusRuntimeException
     *
     * @return an iterator over the response stream.
     * 响应流的迭代器
     */
    // TODO(louiscryan): Not clear if we want to use this idiom for 'simple' stubs.
    public static <ReqT, RespT> Iterator<RespT> blockingServerStreamingCall(ClientCall<ReqT, RespT> call,
                                                                            ReqT req) {
        // 创建阻塞的响应流
        BlockingResponseStream<RespT> result = new BlockingResponseStream<>(call);
        // 开始异步调用
        asyncUnaryRequestCall(call, req, result.listener());
        return result;
    }

    /**
     * Executes a server-streaming call returning a blocking {@link Iterator} over the
     * response stream.  The {@code call} should not be already started.  After calling this method,
     * {@code call} should no longer be used.
     * 执行 server 端流调用，返回阻塞的响应流迭代器，call 不应当已经开始，调用这个方法之后不应当再使用
     *
     * <p>The returned iterator may throw {@link StatusRuntimeException} on error.
     * 返回的迭代器可能会在错误时抛出 StatusRuntimeException
     *
     * @return an iterator over the response stream.
     * 响应流的迭代器
     */
    // TODO(louiscryan): Not clear if we want to use this idiom for 'simple' stubs.
    public static <ReqT, RespT> Iterator<RespT> blockingServerStreamingCall(Channel channel,
                                                                            MethodDescriptor<ReqT, RespT> method,
                                                                            CallOptions callOptions,
                                                                            ReqT req) {
        // 创建只有一个线程的线程池
        ThreadlessExecutor executor = new ThreadlessExecutor();
        // 使用指定的 Channel 创建 ClientCall
        ClientCall<ReqT, RespT> call = channel.newCall(method, callOptions.withOption(ClientCalls.STUB_TYPE_OPTION, StubType.BLOCKING)
                                                                          .withExecutor(executor));
        // 发起阻塞的流调用
        BlockingResponseStream<RespT> result = new BlockingResponseStream<>(call, executor);
        // 执行请求
        asyncUnaryRequestCall(call, req, result.listener());
        return result;
    }

    /**
     * Executes a unary call and returns a {@link ListenableFuture} to the response.  The
     * {@code call} should not be already started.  After calling this method, {@code call} should no
     * longer be used.
     * 执行 unary 调用，并返回 ListenableFuture，在调用之前不应该开始，调用后不应该再被使用
     *
     * @return a future for the single response message
     * 返回用于单个消息响应的 Future
     */
    public static <ReqT, RespT> ListenableFuture<RespT> futureUnaryCall(ClientCall<ReqT, RespT> call, ReqT req) {
        // 初始化 GrpcFuture
        GrpcFuture<RespT> responseFuture = new GrpcFuture<>(call);
        // 将 GrpcFuture 包装为继承了 Listener 的 UnaryStreamToFuture，提交任务
        asyncUnaryRequestCall(call, req, new UnaryStreamToFuture<>(responseFuture));
        return responseFuture;
    }

    /**
     * Returns the result of calling {@link Future#get()} interruptibly on a task known not to throw a
     * checked exception.
     * 返回 Future#get 获取到的结果
     *
     * <p>If interrupted, the interrupt is restored before throwing an exception..
     * 如果是被中断，则中断在引发异常之前恢复
     *
     * @throws java.util.concurrent.CancellationException if {@code get} throws a {@code CancellationException}.
     * @throws io.grpc.StatusRuntimeException             if {@code get} throws an {@link ExecutionException}
     *                                                    or an {@link InterruptedException}.
     */
    private static <V> V getUnchecked(Future<V> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Status.CANCELLED
                    .withDescription("Thread interrupted")
                    .withCause(e)
                    .asRuntimeException();
        } catch (ExecutionException e) {
            throw toStatusRuntimeException(e.getCause());
        }
    }

    /**
     * Wraps the given {@link Throwable} in a {@link StatusRuntimeException}. If it contains an
     * embedded {@link StatusException} or {@link StatusRuntimeException}, the returned exception will
     * contain the embedded trailers and status, with the given exception as the cause. Otherwise, an
     * exception will be generated from an {@link Status#UNKNOWN} status.
     * 将给定的异常包装为 StatusRuntimeException，如果包含嵌套的 StatusException 或 StatusRuntimeException，
     * 返回的异常包含内嵌的元数据和状态，使用给定的异常作为 cause，除此之外，会使用 UNKNOWN 状态产生异常
     */
    private static StatusRuntimeException toStatusRuntimeException(Throwable t) {
        Throwable cause = checkNotNull(t, "t");
        while (cause != null) {
            // If we have an embedded status, use it and replace the cause
            if (cause instanceof StatusException) {
                StatusException se = (StatusException) cause;
                return new StatusRuntimeException(se.getStatus(), se.getTrailers());
            } else if (cause instanceof StatusRuntimeException) {
                StatusRuntimeException se = (StatusRuntimeException) cause;
                return new StatusRuntimeException(se.getStatus(), se.getTrailers());
            }
            cause = cause.getCause();
        }
        return Status.UNKNOWN.withDescription("unexpected exception").withCause(t)
                             .asRuntimeException();
    }

    /**
     * Cancels a call, and throws the exception.
     * 取消调用，抛出异常
     *
     * @param t must be a RuntimeException or Error
     *          必须是 RuntimeException 或者错误
     */
    private static RuntimeException cancelThrow(ClientCall<?, ?> call, Throwable t) {
        try {
            // 取消调用
            call.cancel(null, t);
        } catch (Throwable e) {
            assert e instanceof RuntimeException || e instanceof Error;
            logger.log(Level.SEVERE, "RuntimeException encountered while closing call", e);
        }
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        } else if (t instanceof Error) {
            throw (Error) t;
        }
        // should be impossible
        throw new AssertionError(t);
    }

    /**
     * 异步一元调用，用于 UNARY 和 SERVER_STREAMING 请求
     */
    private static <ReqT, RespT> void asyncUnaryRequestCall(ClientCall<ReqT, RespT> call,
                                                            ReqT req,
                                                            StreamObserver<RespT> responseObserver,
                                                            boolean streamingResponse) {
        asyncUnaryRequestCall(call, req,
                new StreamObserverToCallListenerAdapter<>(responseObserver,
                        new CallToStreamObserverAdapter<>(call, streamingResponse)));
    }

    /**
     * 开始异步调用
     *
     * @param call             ClientCallImpl 实例
     * @param req              请求，proto 生成的入参
     * @param responseListener 继承了 Listener，封装 GrpcFuture 的 UnaryStreamToFuture
     */
    private static <ReqT, RespT> void asyncUnaryRequestCall(ClientCall<ReqT, RespT> call,
                                                            ReqT req,
                                                            StartableListener<RespT> responseListener) {
        // 开始调用
        startCall(call, responseListener);
        try {
            // 发送消息，提交 BufferEntry 任务
            call.sendMessage(req);
            // 从客户端关闭流
            call.halfClose();
        } catch (RuntimeException e) {
            throw cancelThrow(call, e);
        } catch (Error e) {
            throw cancelThrow(call, e);
        }
    }

    /**
     * 异步的客户端流或双向流调用
     */
    private static <ReqT, RespT> StreamObserver<ReqT> asyncStreamingRequestCall(ClientCall<ReqT, RespT> call,
                                                                                StreamObserver<RespT> responseObserver,
                                                                                boolean streamingResponse) {
        // 创建观察适配器
        CallToStreamObserverAdapter<ReqT> adapter = new CallToStreamObserverAdapter<>(call, streamingResponse);
        // 开始调用
        startCall(call, new StreamObserverToCallListenerAdapter<>(responseObserver, adapter));
        // 返回观察器
        return adapter;
    }

    /**
     * 发起调用
     *
     * @param call             ClientCallImpl 调用实现类
     * @param responseListener 响应监听器
     * @param <ReqT>           请求
     * @param <RespT>          响应
     */
    private static <ReqT, RespT> void startCall(ClientCall<ReqT, RespT> call,
                                                StartableListener<RespT> responseListener) {
        // 通过 ClientCallImpl 调用 start
        call.start(responseListener, new Metadata());
        // 启动监听器，延迟执行 Stream 的 request 方法
        responseListener.onStart();
    }

    /**
     * 可启动的监听器
     */
    private abstract static class StartableListener<T> extends ClientCall.Listener<T> {
        abstract void onStart();
    }

    /**
     * 调用和 StreamObserver 的适配器
     */
    private static final class CallToStreamObserverAdapter<T> extends ClientCallStreamObserver<T> {
        // 是否冻结
        private boolean frozen;
        // 调用
        private final ClientCall<T, ?> call;
        // 是否是流响应
        private final boolean streamingResponse;
        // ready 事件处理器
        private Runnable onReadyHandler;
        // 初始请求数量
        private int initialRequest = 1;
        // 是否开启自动流控
        private boolean autoRequestEnabled = true;
        // 是否丢弃了请求
        private boolean aborted = false;
        // 是否完成
        private boolean completed = false;

        // Non private to avoid synthetic class
        CallToStreamObserverAdapter(ClientCall<T, ?> call, boolean streamingResponse) {
            this.call = call;
            this.streamingResponse = streamingResponse;
        }

        /**
         * 冻结请求
         */
        private void freeze() {
            this.frozen = true;
        }

        /**
         * 发送下一个请求
         *
         * @param value the value passed to the stream
         *              传递给流的值
         */
        @Override
        public void onNext(T value) {
            // 检查流是否丢弃或者已经完成
            checkState(!aborted, "Stream was terminated by error, no further calls are allowed");
            checkState(!completed, "Stream is already completed, no further calls are allowed");
            // 发送消息
            call.sendMessage(value);
        }

        /**
         * 错误事件
         *
         * @param t the error occurred on the stream
         *          流中出现的错误
         */
        @Override
        public void onError(Throwable t) {
            // 取消请求
            call.cancel("Cancelled by client with StreamObserver.onError()", t);
            // 修改丢弃状态
            aborted = true;
        }

        /**
         * 请求完成
         */
        @Override
        public void onCompleted() {
            // 半关闭
            call.halfClose();
            // 修改完成状态
            completed = true;
        }

        /**
         * 流是否 ready
         */
        @Override
        public boolean isReady() {
            return call.isReady();
        }

        /**
         * 设置 ready 处理器
         */
        @Override
        public void setOnReadyHandler(Runnable onReadyHandler) {
            // 如果请求已经冻结了，则抛出异常
            if (frozen) {
                throw new IllegalStateException("Cannot alter onReadyHandler after call started. Use ClientResponseObserver");
            }
            this.onReadyHandler = onReadyHandler;
        }

        /**
         * 禁用自动流控
         */
        @Deprecated
        @Override
        public void disableAutoInboundFlowControl() {
            disableAutoRequestWithInitial(1);
        }

        /**
         * 禁用自动流控
         */
        @Override
        public void disableAutoRequestWithInitial(int request) {
            // 如果请求已经冻结了，则抛出异常
            if (frozen) {
                throw new IllegalStateException("Cannot disable auto flow control after call started. Use ClientResponseObserver");
            }
            Preconditions.checkArgument(request >= 0, "Initial requests must be non-negative");
            initialRequest = request;
            autoRequestEnabled = false;
        }

        /**
         * 要求对等端产生指定数量的消息，投递给入站的 StreamObserver
         */
        @Override
        public void request(int count) {
            // 如果不是流响应，且要求的数量是 1，则要求两个消息
            if (!streamingResponse && count == 1) {
                // Initially ask for two responses from flow-control so that if a misbehaving server
                // sends more than one responses, we can catch it and fail it in the listener.
                // 最初从流控制中请求两个响应，以便如果行为不正常的服务器发送多个响应，可以在监听器中捕获它并使其失败
                call.request(2);
            } else {
                call.request(count);
            }
        }

        /**
         * 设置是否开启消息压缩
         *
         * @param enable whether to enable compression.
         *               是否开启压缩
         */
        @Override
        public void setMessageCompression(boolean enable) {
            call.setMessageCompression(enable);
        }

        /**
         * 取消请求
         *
         * @param message if not {@code null}, will appear as the description of the CANCELLED status
         *                如果不为 null，会以描述出现在取消状态中
         * @param cause   if not {@code null}, will appear as the cause of the CANCELLED status
         */
        @Override
        public void cancel(@Nullable String message, @Nullable Throwable cause) {
            call.cancel(message, cause);
        }
    }

    /**
     * StreamObserver 转为 ClientCall 的适配器
     */
    private static final class StreamObserverToCallListenerAdapter<ReqT, RespT> extends StartableListener<RespT> {
        // 观察器
        private final StreamObserver<RespT> observer;
        // 适配器
        private final CallToStreamObserverAdapter<ReqT> adapter;
        // 是否收到第一个响应
        private boolean firstResponseReceived;

        // Non private to avoid synthetic class
        StreamObserverToCallListenerAdapter(StreamObserver<RespT> observer,
                                            CallToStreamObserverAdapter<ReqT> adapter) {
            this.observer = observer;
            this.adapter = adapter;
            // 如果是 ClientResponseObserver，则调用 beforeStart 
            if (observer instanceof ClientResponseObserver) {
                @SuppressWarnings("unchecked")
                ClientResponseObserver<ReqT, RespT> clientResponseObserver = (ClientResponseObserver<ReqT, RespT>) observer;
                clientResponseObserver.beforeStart(adapter);
            }
            // 冻结请求
            adapter.freeze();
        }

        /**
         * 接收到 header 事件
         */
        @Override
        public void onHeaders(Metadata headers) {
        }

        /**
         * 接收到消息
         *
         * @param message returned by the server
         *                Server 返回的消息
         */
        @Override
        public void onMessage(RespT message) {
            // 如果已经接收到第一个响应，且不是流响应，则返回错误
            if (firstResponseReceived && !adapter.streamingResponse) {
                throw Status.INTERNAL.withDescription("More than one responses received for unary or client-streaming call")
                                     .asRuntimeException();
            }
            // 修改第一个响应接收状态
            firstResponseReceived = true;
            // 将返回的响应投递给观察器
            observer.onNext(message);

            // 如果是流响应，且开启了自动流控，则要求投递下一个消息
            if (adapter.streamingResponse && adapter.autoRequestEnabled) {
                // Request delivery of the next inbound message.
                adapter.request(1);
            }
        }

        /**
         * 请求关闭事件
         *
         * @param status   the result of the remote call. 调用的结果
         * @param trailers metadata provided at call completion. 调用完成时提供的元数据
         */
        @Override
        public void onClose(Status status, Metadata trailers) {
            // 如果状态是 OK，则完成观察器
            if (status.isOk()) {
                // 执行半关闭
                observer.onCompleted();
            } else {
                // 否则返回错误
                observer.onError(status.asRuntimeException(trailers));
            }
        }

        /**
         * 接收 ready 事件
         */
        @Override
        public void onReady() {
            // 如果有 ready 处理器，则执行
            if (adapter.onReadyHandler != null) {
                adapter.onReadyHandler.run();
            }
        }

        /**
         * 接收开始事件
         */
        @Override
        void onStart() {
            // 如果初始请求数量大于 0，则要求指定数量的请求
            if (adapter.initialRequest > 0) {
                adapter.request(adapter.initialRequest);
            }
        }
    }

    /**
     * Completes a {@link GrpcFuture} using {@link StreamObserver} events.
     * 使用 StreamObserver 事件完成 GrpcFuture，继承了 io.grpc.ClientCall.Listener
     */
    private static final class UnaryStreamToFuture<RespT> extends StartableListener<RespT> {
        // 返回的 Future
        private final GrpcFuture<RespT> responseFuture;
        // 返回的值
        private RespT value;

        /**
         * 使用 GrpcFuture 初始化 UnaryStreamToFuture
         *
         * @param responseFuture 返回结果的 Future
         */
        UnaryStreamToFuture(GrpcFuture<RespT> responseFuture) {
            this.responseFuture = responseFuture;
        }

        @Override
        public void onHeaders(Metadata headers) {
        }

        /**
         * 接收消息
         */
        @Override
        public void onMessage(RespT value) {
            // 如果已经有值了，则返回异常
            if (this.value != null) {
                throw Status.INTERNAL.withDescription("More than one value received for unary call")
                                     .asRuntimeException();
            }
            this.value = value;
        }

        /**
         * 监听关闭事件
         *
         * @param status   the result of the remote call. 调用的结果
         * @param trailers metadata provided at call completion. 调用完成时提供的元数据
         */
        @Override
        public void onClose(Status status, Metadata trailers) {
            // 如果状态是 OK
            if (status.isOk()) {
                // 如果值为 null，则返回错误
                if (value == null) {
                    // No value received so mark the future as an error
                    responseFuture.setException(Status.INTERNAL.withDescription("No value received for unary call")
                                                               .asRuntimeException(trailers));
                }
                // 设置值
                responseFuture.set(value);
            } else {
                // 如果状态不是 OK，则返回错误
                responseFuture.setException(status.asRuntimeException(trailers));
            }
        }

        /**
         * 启动监听器
         */
        @Override
        void onStart() {
            // 要求两个请求
            responseFuture.call.request(2);
        }
    }

    /**
     * 响应的 Future
     */
    private static final class GrpcFuture<RespT> extends AbstractFuture<RespT> {
        // 调用
        private final ClientCall<?, RespT> call;

        /**
         * 初始化 GrpcFuture，用于获取响应
         */
        GrpcFuture(ClientCall<?, RespT> call) {
            this.call = call;
        }

        /**
         * 打断请求，会取消调用
         */
        @Override
        protected void interruptTask() {
            call.cancel("GrpcFuture was cancelled", null);
        }

        /**
         * 设置响应
         */
        @Override
        protected boolean set(@Nullable RespT resp) {
            return super.set(resp);
        }

        /**
         * 设置异常
         */
        @Override
        protected boolean setException(Throwable throwable) {
            return super.setException(throwable);
        }

        @SuppressWarnings("MissingOverride") // Add @Override once Java 6 support is dropped
        protected String pendingToString() {
            return MoreObjects.toStringHelper(this).add("clientCall", call).toString();
        }
    }

    /**
     * Convert events on a {@link io.grpc.ClientCall.Listener} into a blocking {@link Iterator}.
     * 阻塞响应流观察器，将 Listener 中的事件放到阻塞的迭代器中
     *
     * <p>The class is not thread-safe, but it does permit {@link ClientCall.Listener} calls in a
     * separate thread from {@link Iterator} calls.
     * 这个类不是线程安全的，但是允许迭代器在不同的线程中调用 Listener
     */
    // TODO(ejona86): determine how to allow ClientCall.cancel() in case of application error.
    private static final class BlockingResponseStream<T> implements Iterator<T> {
        // Due to flow control, only needs to hold up to 3 items: 2 for value, 1 for close.
        // (2 for value, not 1, because of early request() in next())
        // 因为流控，只需要持有三个对象，两个用于值，一个用于关闭，值为2是因为 next() 中有早期的请求
        private final BlockingQueue<Object> buffer = new ArrayBlockingQueue<>(3);
        // 监听器
        private final StartableListener<T> listener = new QueuingListener();
        // 调用
        private final ClientCall<?, T> call;
        /**
         * May be null.
         * 只有一个线程的线程池
         */
        private final ThreadlessExecutor threadless;
        // Only accessed when iterating.
        private Object last;

        // Non private to avoid synthetic class
        BlockingResponseStream(ClientCall<?, T> call) {
            this(call, null);
        }

        // Non private to avoid synthetic class
        BlockingResponseStream(ClientCall<?, T> call, ThreadlessExecutor threadless) {
            this.call = call;
            this.threadless = threadless;
        }

        StartableListener<T> listener() {
            return listener;
        }

        /**
         * 等待下一个响应
         */
        private Object waitForNext() {
            boolean interrupt = false;
            try {
                // 如果线程池为 null，则直接从队列中获取
                if (threadless == null) {
                    while (true) {
                        try {
                            return buffer.take();
                        } catch (InterruptedException ie) {
                            // 如果有异常则打断并取消流
                            interrupt = true;
                            call.cancel("Thread interrupted", ie);
                            // Now wait for onClose() to be called, to guarantee BlockingQueue doesn't fill
                        }
                    }
                } else {
                    // 如果线程池不为 null，则通过线程池获取
                    Object next;
                    while ((next = buffer.poll()) == null) {
                        try {
                            threadless.waitAndDrain();
                        } catch (InterruptedException ie) {
                            // 如果有异常则打断并取消流
                            interrupt = true;
                            call.cancel("Thread interrupted", ie);
                            // Now wait for onClose() to be called, so interceptors can clean up
                        }
                    }
                    return next;
                }
            } finally {
                // 如果请求被打断，则打断线程
                if (interrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * 是否还有下一个元素
         */
        @Override
        public boolean hasNext() {
            // 如果最后一个为 null，则阻塞等待
            while (last == null) {
                // Will block here indefinitely waiting for content. RPC timeouts defend against permanent
                // hangs here as the call will become closed.
                // 会无限期地阻塞这里等待内容，RPC超时可以防止永久阻塞在这里，因为调用将关闭
                last = waitForNext();
            }
            // 如果获取到异常，则返回异常
            if (last instanceof StatusRuntimeException) {
                // Rethrow the exception with a new stacktrace.
                StatusRuntimeException e = (StatusRuntimeException) last;
                throw e.getStatus().asRuntimeException(e.getTrailers());
            }
            // 返回是否有下一个
            return last != this;
        }

        /**
         * 获取下一个值
         */
        @Override
        public T next() {
            // Eagerly call request(1) so it can be processing the next message while we wait for the
            // current one, which reduces latency for the next message. With MigratingThreadDeframer and
            // if the data has already been recieved, every other message can be delivered instantly. This
            // can be run after hasNext(), but just would be slower.
            // 如果不是异常且不是当前的值，则要求一个响应
            if (!(last instanceof StatusRuntimeException) && last != this) {
                call.request(1);
            }
            // 如果没有，则抛出异常
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            @SuppressWarnings("unchecked")
            T tmp = (T) last;
            last = null;
            return tmp;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        /**
         * 队列监听器
         */
        private final class QueuingListener extends StartableListener<T> {
            // Non private to avoid synthetic class
            QueuingListener() {
            }

            private boolean done = false;

            @Override
            public void onHeaders(Metadata headers) {
            }

            /**
             * 接收消息事件
             */
            @Override
            public void onMessage(T value) {
                // 检查是否完成
                Preconditions.checkState(!done, "ClientCall already closed");
                // 接收到消息时将消息添加到队列中
                buffer.add(value);
            }

            /**
             * 关闭事件
             *
             * @param status   the result of the remote call. 调用的结果
             * @param trailers metadata provided at call completion. 调用完成时提供的元数据
             */
            @Override
            public void onClose(Status status, Metadata trailers) {
                // 检查是否完成
                Preconditions.checkState(!done, "ClientCall already closed");
                // 如果状态是 OK，则将响应加入到队列中
                if (status.isOk()) {
                    buffer.add(BlockingResponseStream.this);
                } else {
                    // 否则将错误加入到队列中
                    buffer.add(status.asRuntimeException(trailers));
                }
                // 修改完成状态
                done = true;
            }

            /**
             * 开始事件
             */
            @Override
            void onStart() {
                // 要去一条消息
                call.request(1);
            }
        }
    }

    /**
     * 用于从队列中拉取任务并执行，只有一个线程
     */
    @SuppressWarnings("serial")
    private static final class ThreadlessExecutor extends ConcurrentLinkedQueue<Runnable> implements Executor {

        private static final Logger log = Logger.getLogger(ThreadlessExecutor.class.getName());

        private volatile Thread waiter;

        // Non private to avoid synthetic class
        ThreadlessExecutor() {
        }

        /**
         * Waits until there is a Runnable, then executes it and all queued Runnables after it.
         * Must only be called by one thread at a time.
         * <p>
         * 等待执行队列中的 Runnable，一次只能有一个线程调用
         */
        public void waitAndDrain() throws InterruptedException {
            // 如果线程已经被打断了，则抛出异常
            throwIfInterrupted();
            // 从队列中获取任务
            Runnable runnable = poll();
            // 如果没有拉取到任务，那么让当前线程等待
            if (runnable == null) {
                waiter = Thread.currentThread();
                try {
                    // 遍历拉取
                    while ((runnable = poll()) == null) {
                        LockSupport.park(this);
                        throwIfInterrupted();
                    }
                } finally {
                    waiter = null;
                }
            }
            // 当有任务时不断拉取执行
            do {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    log.log(Level.WARNING, "Runnable threw exception", t);
                }
            } while ((runnable = poll()) != null);
        }

        /**
         * 当线程被打断时抛出异常
         *
         * @throws InterruptedException
         */
        private static void throwIfInterrupted() throws InterruptedException {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }

        /**
         * 执行任务，将任务添加到队列中
         *
         * @param runnable
         */
        @Override
        public void execute(Runnable runnable) {
            add(runnable);
            LockSupport.unpark(waiter); // no-op if null
        }
    }

    /**
     * 请求子类型
     */
    enum StubType {
        BLOCKING, FUTURE, ASYNC
    }

    /**
     * Internal {@link CallOptions.Key} to indicate stub types.
     * 表示请求子类型的 key
     */
    static final CallOptions.Key<StubType> STUB_TYPE_OPTION = CallOptions.Key.create("internal-stub-type");
}
