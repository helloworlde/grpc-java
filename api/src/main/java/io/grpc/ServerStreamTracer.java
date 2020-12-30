/*
 * Copyright 2017 The gRPC Authors
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
import javax.annotation.concurrent.ThreadSafe;

/**
 * Listens to events on a stream to collect metrics.
 * 监听 Server 端的流事件，用于收集指标
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2861")
@ThreadSafe
public abstract class ServerStreamTracer extends StreamTracer {
    /**
     * Called before the interceptors and the call handlers and make changes to the Context object
     * if needed.
     * 在拦截器和调用处理器之前调用，如果需要可以修改 Context 的对象
     */
    public Context filterContext(Context context) {
        return context;
    }

    /**
     * Called when {@link ServerCall} is created.  This is for the tracer to access information about
     * the {@code ServerCall}.  Called after {@link #filterContext} and before the application call
     * handler.
     * 当 ServerCall 创建时调用，用于 Tracer 访问 ServerCall 的信息，在 filterContext 之后，在应用程序处理之前调用
     */
    @SuppressWarnings("deprecation")
    public void serverCallStarted(ServerCallInfo<?, ?> callInfo) {
        serverCallStarted(ReadOnlyServerCall.create(callInfo));
    }

    /**
     * Called when {@link ServerCall} is created.  This is for the tracer to access information about
     * the {@code ServerCall}.  Called after {@link #filterContext} and before the application call
     * handler.
     * 当 ServerCall 创建时调用，用于 Tracer 访问 ServerCall 的信息，在 filterContext 之后，在应用程序处理之前调用
     *
     * @deprecated Implement {@link #serverCallStarted(ServerCallInfo)} instead. This method will be
     * removed in a future release of gRPC.
     */
    @Deprecated
    public void serverCallStarted(ServerCall<?, ?> call) {
    }

    /**
     * ServerStreamTracer 工厂
     */
    public abstract static class Factory {
        /**
         * Creates a {@link ServerStreamTracer} for a new server stream.
         * 为 Server 端的流创建 ServerStreamTracer
         *
         * <p>Called right before the stream is created
         * 在流创建之前调用
         *
         * @param fullMethodName the fully qualified method name
         *                       方法的全名
         * @param headers        the received request headers.  It can be safely mutated within this method.
         *                       It should not be saved because it is not safe for read or write after the method
         *                       returns.
         *                       请求的 header，可以在这个方法中被安全的修改，但是不应该保存，在这个方法返回之后读写是不安全的
         */
        public abstract ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers);
    }

    /**
     * A data class with info about the started {@link ServerCall}.
     * ServerCall 的信息
     */
    public abstract static class ServerCallInfo<ReqT, RespT> {

        public abstract MethodDescriptor<ReqT, RespT> getMethodDescriptor();

        public abstract Attributes getAttributes();

        @Nullable
        public abstract String getAuthority();
    }

    /**
     * This class exists solely to help transition to the {@link ServerCallInfo} based API.
     * 这个类存在用于过渡  ServerCallInfo 的基础 API
     *
     * @deprecated Will be deleted when {@link #serverCallStarted(ServerCall)} is removed.
     * 会在 serverCallStarted(ServerCall) 移除之后删除
     */
    @Deprecated
    private static final class ReadOnlyServerCall<ReqT, RespT> extends ForwardingServerCall<ReqT, RespT> {

        private final ServerCallInfo<ReqT, RespT> callInfo;

        private static <ReqT, RespT> ReadOnlyServerCall<ReqT, RespT> create(ServerCallInfo<ReqT, RespT> callInfo) {
            return new ReadOnlyServerCall<>(callInfo);
        }

        private ReadOnlyServerCall(ServerCallInfo<ReqT, RespT> callInfo) {
            this.callInfo = callInfo;
        }

        @Override
        public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
            return callInfo.getMethodDescriptor();
        }

        @Override
        public Attributes getAttributes() {
            return callInfo.getAttributes();
        }

        @Override
        public boolean isReady() {
            // a dummy value
            return false;
        }

        @Override
        public boolean isCancelled() {
            // a dummy value
            return false;
        }

        @Override
        public String getAuthority() {
            return callInfo.getAuthority();
        }

        @Override
        protected ServerCall<ReqT, RespT> delegate() {
            throw new UnsupportedOperationException();
        }
    }
}
