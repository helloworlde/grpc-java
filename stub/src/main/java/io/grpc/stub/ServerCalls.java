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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Utility functions for adapting {@link ServerCallHandler}s to application service implementation,
 * meant to be used by the generated code.
 * 适配 ServerCallHandler 和方法实现的工具，打算由生成的代码使用
 */
public final class ServerCalls {

    @VisibleForTesting
    static final String TOO_MANY_REQUESTS = "Too many requests";

    @VisibleForTesting
    static final String MISSING_REQUEST = "Half-closed without a request";

    private ServerCalls() {
    }

    /**
     * Creates a {@link ServerCallHandler} for a unary call method of the service.
     * 为 UNARY 的方法创建一个处理器
     *
     * @param method an adaptor to the actual method on the service implementation.
     *               真正的方法实现的适配器
     */
    public static <ReqT, RespT> ServerCallHandler<ReqT, RespT> asyncUnaryCall(UnaryMethod<ReqT, RespT> method) {
        return new UnaryServerCallHandler<>(method);
    }

    /**
     * Creates a {@link ServerCallHandler} for a server streaming method of the service.
     * 为服务端流请求方法创建一个处理器
     *
     * @param method an adaptor to the actual method on the service implementation.
     *               真正的方法实现的适配器
     */
    public static <ReqT, RespT> ServerCallHandler<ReqT, RespT> asyncServerStreamingCall(ServerStreamingMethod<ReqT, RespT> method) {
        return new UnaryServerCallHandler<>(method);
    }

    /**
     * Creates a {@link ServerCallHandler} for a client streaming method of the service.
     * 为客户端流请求方法创建处理器
     *
     * @param method an adaptor to the actual method on the service implementation.
     *               真正的方法实现的适配器
     */
    public static <ReqT, RespT> ServerCallHandler<ReqT, RespT> asyncClientStreamingCall(ClientStreamingMethod<ReqT, RespT> method) {
        return new StreamingServerCallHandler<>(method);
    }

    /**
     * Creates a {@link ServerCallHandler} for a bidi streaming method of the service.
     * 为双向流请求方法创建处理器
     *
     * @param method an adaptor to the actual method on the service implementation.
     *               真正的方法实现的适配器
     */
    public static <ReqT, RespT> ServerCallHandler<ReqT, RespT> asyncBidiStreamingCall(BidiStreamingMethod<ReqT, RespT> method) {
        return new StreamingServerCallHandler<>(method);
    }

    /**
     * Adaptor to a unary call method.
     * unary 调用方法的适配器
     */
    public interface UnaryMethod<ReqT, RespT> extends UnaryRequestMethod<ReqT, RespT> {
        @Override
        void invoke(ReqT request, StreamObserver<RespT> responseObserver);
    }

    /**
     * Adaptor to a server streaming method.
     * Server 端流方法的适配器
     */
    public interface ServerStreamingMethod<ReqT, RespT> extends UnaryRequestMethod<ReqT, RespT> {
        @Override
        void invoke(ReqT request, StreamObserver<RespT> responseObserver);
    }

    /**
     * Adaptor to a client streaming method.
     * 客户端流方法的处理器
     */
    public interface ClientStreamingMethod<ReqT, RespT> extends StreamingRequestMethod<ReqT, RespT> {
        @Override
        StreamObserver<ReqT> invoke(StreamObserver<RespT> responseObserver);
    }

    /**
     * Adaptor to a bidirectional streaming method.
     * 双向流方法处理器
     */
    public interface BidiStreamingMethod<ReqT, RespT> extends StreamingRequestMethod<ReqT, RespT> {
        @Override
        StreamObserver<ReqT> invoke(StreamObserver<RespT> responseObserver);
    }

    /**
     * Unary 和 Server 端流方法调用处理器
     */
    private static final class UnaryServerCallHandler<ReqT, RespT> implements ServerCallHandler<ReqT, RespT> {

        // Unary 方法
        private final UnaryRequestMethod<ReqT, RespT> method;

        // Non private to avoid synthetic class
        UnaryServerCallHandler(UnaryRequestMethod<ReqT, RespT> method) {
            this.method = method;
        }

        /**
         * 处理请求
         *
         * @param call    object for responding to the remote client.
         *                用于响应远程客户端的对象
         * @param headers 请求头
         * @return 监听器
         */
        @Override
        public ServerCall.Listener<ReqT> startCall(ServerCall<ReqT, RespT> call, Metadata headers) {
            // 检查类型是否是单次请求的类型
            Preconditions.checkArgument(call.getMethodDescriptor().getType().clientSendsOneMessage(), "asyncUnaryRequestCall is only for clientSendsOneMessage methods");
            // 创建响应处理器
            ServerCallStreamObserverImpl<ReqT, RespT> responseObserver = new ServerCallStreamObserverImpl<>(call);
            // We expect only 1 request, but we ask for 2 requests here so that if a misbehaving client
            // sends more than 1 requests, ServerCall will catch it. Note that disabling auto
            // inbound flow control has no effect on unary calls.
            call.request(2);
            // 返回监听器
            return new UnaryServerCallListener(responseObserver, call);
        }

        /**
         * Unary 调用监听器
         */
        private final class UnaryServerCallListener extends ServerCall.Listener<ReqT> {

            // 调用
            private final ServerCall<ReqT, RespT> call;
            // 响应观察器
            private final ServerCallStreamObserverImpl<ReqT, RespT> responseObserver;
            // 是否能调用
            private boolean canInvoke = true;
            // Transport 是否 ready
            private boolean wasReady;
            // 请求
            private ReqT request;

            // Non private to avoid synthetic class
            UnaryServerCallListener(ServerCallStreamObserverImpl<ReqT, RespT> responseObserver,
                                    ServerCall<ReqT, RespT> call) {
                this.call = call;
                this.responseObserver = responseObserver;
            }

            /**
             * 接收新的消息
             */
            @Override
            public void onMessage(ReqT request) {
                // 如果已经接收到了一个请求，则返回错误
                if (this.request != null) {
                    // Safe to close the call, because the application has not yet been invoked
                    call.close(Status.INTERNAL.withDescription(TOO_MANY_REQUESTS), new Metadata());
                    canInvoke = false;
                    return;
                }

                // We delay calling method.invoke() until onHalfClose() to make sure the client
                // half-closes.
                // 延迟执行调用 method.invoke() 直到 onHalfClose() 以确保客户端执行了半关闭
                this.request = request;
            }

            /**
             * 半关闭
             */
            @Override
            public void onHalfClose() {
                // 如果不能调用则直接返回
                if (!canInvoke) {
                    return;
                }

                // 如果请求是 null，则返回错我
                if (request == null) {
                    // Safe to close the call, because the application has not yet been invoked
                    call.close(Status.INTERNAL.withDescription(MISSING_REQUEST), new Metadata());
                    return;
                }

                // 执行方法调用
                method.invoke(request, responseObserver);
                // 处理了请求之后将请求置为 null
                request = null;
                // 冻结响应
                responseObserver.freeze();
                // 判断是否 ready
                if (wasReady) {
                    // Since we are calling invoke in halfClose we have missed the onReady
                    // event from the transport so recover it here.
                    // 因为在 halfClose 中调用，错过了来自 Transport 的 onReady 事件，从这里恢复
                    // 即在 ready 之后用于执行 onReadyHandler
                    onReady();
                }
            }

            /**
             * 处理取消事件
             */
            @Override
            public void onCancel() {
                responseObserver.cancelled = true;
                if (responseObserver.onCancelHandler != null) {
                    responseObserver.onCancelHandler.run();
                }
            }

            /**
             * 处理 ready 事件
             */
            @Override
            public void onReady() {
                // 将 ready 状态变为 true
                wasReady = true;
                // 如果响应有 readyHandler，则执行
                if (responseObserver.onReadyHandler != null) {
                    responseObserver.onReadyHandler.run();
                }
            }
        }
    }

    /**
     * 客户端流和双向流方法处理器
     */
    private static final class StreamingServerCallHandler<ReqT, RespT> implements ServerCallHandler<ReqT, RespT> {

        // 方法
        private final StreamingRequestMethod<ReqT, RespT> method;

        // Non private to avoid synthetic class
        StreamingServerCallHandler(StreamingRequestMethod<ReqT, RespT> method) {
            this.method = method;
        }

        /**
         * 处理请求
         *
         * @param call    object for responding to the remote client.
         *                用于响应远程客户端的对象
         * @param headers 请求头
         * @return 监听器
         */
        @Override
        public ServerCall.Listener<ReqT> startCall(ServerCall<ReqT, RespT> call, Metadata headers) {
            // 创建响应观察器
            ServerCallStreamObserverImpl<ReqT, RespT> responseObserver = new ServerCallStreamObserverImpl<>(call);
            // 调用方法，返回请求观察器
            StreamObserver<ReqT> requestObserver = method.invoke(responseObserver);
            // 冻结响应观察器
            responseObserver.freeze();
            // 如果可以继续请求，则再请求一个消息
            if (responseObserver.autoRequestEnabled) {
                call.request(1);
            }
            // 返回监听器
            return new StreamingServerCallListener(requestObserver, responseObserver, call);
        }

        /**
         * 流请求监听器
         */
        private final class StreamingServerCallListener extends ServerCall.Listener<ReqT> {

            // 请求观察器
            private final StreamObserver<ReqT> requestObserver;
            // 响应观察器
            private final ServerCallStreamObserverImpl<ReqT, RespT> responseObserver;
            // 调用
            private final ServerCall<ReqT, RespT> call;
            // 半关闭
            private boolean halfClosed = false;

            // Non private to avoid synthetic class
            StreamingServerCallListener(StreamObserver<ReqT> requestObserver,
                                        ServerCallStreamObserverImpl<ReqT, RespT> responseObserver,
                                        ServerCall<ReqT, RespT> call) {
                this.requestObserver = requestObserver;
                this.responseObserver = responseObserver;
                this.call = call;
            }

            /**
             * 处理接收消息
             */
            @Override
            public void onMessage(ReqT request) {
                // 处理请求
                requestObserver.onNext(request);

                // Request delivery of the next inbound message.
                // 如果允许自动接收请求，则请求传递下一个消息
                if (responseObserver.autoRequestEnabled) {
                    call.request(1);
                }
            }

            /**
             * 半关闭
             */
            @Override
            public void onHalfClose() {
                halfClosed = true;
                // 请求观察器完成
                requestObserver.onCompleted();
            }

            /**
             * 取消请求
             */
            @Override
            public void onCancel() {
                responseObserver.cancelled = true;
                // 如果有取消处理器，则执行
                if (responseObserver.onCancelHandler != null) {
                    responseObserver.onCancelHandler.run();
                }
                // 如果还没有执行半关闭，则将请求观察器状态变为 CANCELLED
                if (!halfClosed) {
                    requestObserver.onError(Status.CANCELLED.withDescription("cancelled before receiving half close")
                                                            .asRuntimeException());
                }
            }


            /**
             * Ready 事件
             */
            @Override
            public void onReady() {
                // 如果有 ready 处理器则执行
                if (responseObserver.onReadyHandler != null) {
                    responseObserver.onReadyHandler.run();
                }
            }
        }
    }

    /**
     * Unary 调用方法适配器
     */
    private interface UnaryRequestMethod<ReqT, RespT> {
        /**
         * The provided {@code responseObserver} will extend {@link ServerCallStreamObserver}.
         * 提供的 responseObserver 会继承 ServerCallStreamObserver
         */
        void invoke(ReqT request, StreamObserver<RespT> responseObserver);
    }

    /**
     * 流请求方法适配器
     */
    private interface StreamingRequestMethod<ReqT, RespT> {
        /**
         * The provided {@code responseObserver} will extend {@link ServerCallStreamObserver}.
         * 提供的 responseObserver 会继承 ServerCallStreamObserver
         */
        StreamObserver<ReqT> invoke(StreamObserver<RespT> responseObserver);
    }

    private static final class ServerCallStreamObserverImpl<ReqT, RespT>
            extends ServerCallStreamObserver<RespT> {
        final ServerCall<ReqT, RespT> call;
        volatile boolean cancelled;
        private boolean frozen;
        private boolean autoRequestEnabled = true;
        private boolean sentHeaders;
        private Runnable onReadyHandler;
        private Runnable onCancelHandler;
        private boolean aborted = false;
        private boolean completed = false;

        // Non private to avoid synthetic class
        ServerCallStreamObserverImpl(ServerCall<ReqT, RespT> call) {
            this.call = call;
        }

        private void freeze() {
            this.frozen = true;
        }

        @Override
        public void setMessageCompression(boolean enable) {
            call.setMessageCompression(enable);
        }

        @Override
        public void setCompression(String compression) {
            call.setCompression(compression);
        }

        @Override
        public void onNext(RespT response) {
            if (cancelled) {
                if (onCancelHandler == null) {
                    throw Status.CANCELLED.withDescription("call already cancelled").asRuntimeException();
                }
                return;
            }
            checkState(!aborted, "Stream was terminated by error, no further calls are allowed");
            checkState(!completed, "Stream is already completed, no further calls are allowed");
            if (!sentHeaders) {
                call.sendHeaders(new Metadata());
                sentHeaders = true;
            }
            call.sendMessage(response);
        }

        @Override
        public void onError(Throwable t) {
            Metadata metadata = Status.trailersFromThrowable(t);
            if (metadata == null) {
                metadata = new Metadata();
            }
            call.close(Status.fromThrowable(t), metadata);
            aborted = true;
        }

        @Override
        public void onCompleted() {
            if (cancelled) {
                if (onCancelHandler == null) {
                    throw Status.CANCELLED.withDescription("call already cancelled").asRuntimeException();
                }
            } else {
                call.close(Status.OK, new Metadata());
                completed = true;
            }
        }

        @Override
        public boolean isReady() {
            return call.isReady();
        }

        /**
         * 设置 ready 事件回调任务
         */
        @Override
        public void setOnReadyHandler(Runnable r) {
            // 在被冻结之后不能再设置，因为请求已经被处理
            checkState(!frozen, "Cannot alter onReadyHandler after initialization. May only be called "
                    + "during the initial call to the application, before the service returns its "
                    + "StreamObserver");
            this.onReadyHandler = r;
        }

        @Override
        public boolean isCancelled() {
            return call.isCancelled();
        }

        @Override
        public void setOnCancelHandler(Runnable onCancelHandler) {
            checkState(!frozen, "Cannot alter onCancelHandler after initialization. May only be called "
                    + "during the initial call to the application, before the service returns its "
                    + "StreamObserver");
            this.onCancelHandler = onCancelHandler;
        }

        @Deprecated
        @Override
        public void disableAutoInboundFlowControl() {
            disableAutoRequest();
        }

        @Override
        public void disableAutoRequest() {
            checkState(!frozen, "Cannot disable auto flow control after initialization");
            autoRequestEnabled = false;
        }

        @Override
        public void request(int count) {
            call.request(count);
        }
    }

    /**
     * Sets unimplemented status for method on given response stream for unary call.
     * 为 unary 调用设置 UNIMPLEMENTED 状态的响应
     *
     * @param methodDescriptor of method for which error will be thrown.
     *                         方法
     * @param responseObserver on which error will be set.
     *                         响应
     */
    public static void asyncUnimplementedUnaryCall(MethodDescriptor<?, ?> methodDescriptor,
                                                   StreamObserver<?> responseObserver) {
        checkNotNull(methodDescriptor, "methodDescriptor");
        checkNotNull(responseObserver, "responseObserver");
        responseObserver.onError(Status.UNIMPLEMENTED.withDescription(String.format("Method %s is unimplemented", methodDescriptor.getFullMethodName()))
                                                     .asRuntimeException());
    }

    /**
     * Sets unimplemented status for streaming call.
     * 为流请求设置 UNIMPLEMENTED 状态的响应
     *
     * @param methodDescriptor of method for which error will be thrown.
     *                         方法
     * @param responseObserver on which error will be set.
     *                         响应观察器
     */
    public static <T> StreamObserver<T> asyncUnimplementedStreamingCall(MethodDescriptor<?, ?> methodDescriptor,
                                                                        StreamObserver<?> responseObserver) {
        // NB: For streaming call we want to do the same as for unary call. Fail-fast by setting error
        // on responseObserver and then return no-op observer.
        asyncUnimplementedUnaryCall(methodDescriptor, responseObserver);
        return new NoopStreamObserver<>();
    }

    /**
     * No-op implementation of StreamObserver. Used in abstract stubs for default implementations of
     * methods which throws UNIMPLEMENTED error and tests.
     * 流请求观察器的无操作的实现，是抽象的 Stub 的默认实现，会返回 UNIMPLEMENTED 错误
     */
    static class NoopStreamObserver<V> implements StreamObserver<V> {
        @Override
        public void onNext(V value) {
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }
}
