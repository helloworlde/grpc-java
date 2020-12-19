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

import com.google.common.base.Preconditions;
import io.grpc.MethodDescriptor.Marshaller;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Utility methods for working with {@link ClientInterceptor}s.
 * 用于 ClientInterceptor 的工具方法
 */
public class ClientInterceptors {

    // Prevent instantiation
    private ClientInterceptors() {
    }

    /**
     * Create a new {@link Channel} that will call {@code interceptors} before starting a call on the
     * given channel. The first interceptor will have its {@link ClientInterceptor#interceptCall}
     * called first.
     * 在给定的 Channel 上创建一个新的 Channel，在开始调用之前会调用 interceptors，第一个拦截器会在
     * ClientInterceptor#interceptCall 时第一个调用
     *
     * @param channel      the underlying channel to intercept.
     *                     要拦截的 Channel
     * @param interceptors array of interceptors to bind to {@code channel}.
     *                     需要绑定在 Channel 上的拦截器集合
     * @return a new channel instance with the interceptors applied.
     * 拦截器绑定的 Channel 实例
     */
    public static Channel interceptForward(Channel channel, ClientInterceptor... interceptors) {
        return interceptForward(channel, Arrays.asList(interceptors));
    }

    /**
     * @see #interceptForward(Channel, List)
     * 会翻转拦截器的顺序
     */
    public static Channel interceptForward(Channel channel,
                                           List<? extends ClientInterceptor> interceptors) {
        List<? extends ClientInterceptor> copy = new ArrayList<>(interceptors);
        // 反转顺序
        Collections.reverse(copy);
        return intercept(channel, copy);
    }

    /**
     * @see #interceptForward(Channel, List)
     * 不会翻转拦截器的顺序
     */
    public static Channel intercept(Channel channel, ClientInterceptor... interceptors) {
        return intercept(channel, Arrays.asList(interceptors));
    }

    /**
     * @see #interceptForward(Channel, List)
     * 不会翻转拦截器的顺序
     */
    public static Channel intercept(Channel channel, List<? extends ClientInterceptor> interceptors) {
        Preconditions.checkNotNull(channel, "channel");
        // 遍历拦截器，创建 InterceptorChannel
        for (ClientInterceptor interceptor : interceptors) {
            channel = new InterceptorChannel(channel, interceptor);
        }
        return channel;
    }

    /**
     * Creates a new ClientInterceptor that transforms requests into {@code WReqT} and responses into
     * {@code WRespT} before passing them into the {@code interceptor}.
     * <p>
     * 创建一个新的 ClientInterceptor，会将请求和响应在传递给 interceptor 之前转发
     */
    static <WReqT, WRespT> ClientInterceptor wrapClientInterceptor(final ClientInterceptor interceptor,
                                                                   final Marshaller<WReqT> reqMarshaller,
                                                                   final Marshaller<WRespT> respMarshaller) {
        // 创建新的拦截器
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(final MethodDescriptor<ReqT, RespT> method,
                                                                       CallOptions callOptions,
                                                                       Channel next) {
                // 使用指定的 Marshaller 重新构建方法
                final MethodDescriptor<WReqT, WRespT> wrappedMethod = method.toBuilder(reqMarshaller, respMarshaller).build();
                // 使用指定的拦截器调用
                final ClientCall<WReqT, WRespT> wrappedCall = interceptor.interceptCall(wrappedMethod, callOptions, next);

                return new PartialForwardingClientCall<ReqT, RespT>() {

                    @Override
                    public void start(final Listener<RespT> responseListener, Metadata headers) {
                        // 创建新的监听器
                        wrappedCall.start(new PartialForwardingClientCallListener<WRespT>() {
                            @Override
                            public void onMessage(WRespT wMessage) {
                                // 先使用指定的 Marshaller 解析流
                                InputStream bytes = respMarshaller.stream(wMessage);
                                // 然后使用方法的 Marshaller 解析字节
                                RespT message = method.getResponseMarshaller().parse(bytes);
                                // 发送给监听器
                                responseListener.onMessage(message);
                            }

                            @Override
                            protected Listener<?> delegate() {
                                return responseListener;
                            }
                        }, headers);
                    }

                    @Override
                    public void sendMessage(ReqT message) {
                        // 使用方法的 Marshaller 将消息序列化流
                        InputStream bytes = method.getRequestMarshaller().stream(message);
                        // 然后使用指定的 Marshaller 解析
                        WReqT wReq = reqMarshaller.parse(bytes);
                        wrappedCall.sendMessage(wReq);
                    }

                    @Override
                    protected ClientCall<?, ?> delegate() {
                        return wrappedCall;
                    }
                };
            }
        };
    }

    /**
     * 拦截器 Channel，下一步将调用拦截器
     */
    private static class InterceptorChannel extends Channel {
        private final Channel channel;
        private final ClientInterceptor interceptor;

        private InterceptorChannel(Channel channel, ClientInterceptor interceptor) {
            this.channel = channel;
            this.interceptor = Preconditions.checkNotNull(interceptor, "interceptor");
        }

        /**
         * BlockingStub 初始化 ClientCall 执行请求顺序: 1
         * BlockingStub 初始化 ClientCall 执行请求顺序: 5
         */
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method,
                                                             CallOptions callOptions) {
            return interceptor.interceptCall(method, callOptions, channel);
        }

        @Override
        public String authority() {
            return channel.authority();
        }
    }

    /**
     * 不做任何操作的 ClientCall
     */
    private static final ClientCall<Object, Object> NOOP_CALL = new ClientCall<Object, Object>() {
        @Override
        public void start(Listener<Object> responseListener, Metadata headers) {
        }

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void cancel(String message, Throwable cause) {
        }

        @Override
        public void halfClose() {
        }

        @Override
        public void sendMessage(Object message) {
        }

        /**
         * Always returns {@code false}, since this is only used when the startup of the {@link
         * ClientCall} fails (i.e. the {@link ClientCall} is closed).
         */
        @Override
        public boolean isReady() {
            return false;
        }
    };

    /**
     * A {@link io.grpc.ForwardingClientCall} that delivers exceptions from its start logic to the
     * call listener.
     * <p>
     * 转发的 ClientCall，会将异常从开始逻辑投递到调用监听器中
     *
     * <p>{@link ClientCall#start(ClientCall.Listener, Metadata)} should not throw any
     * exception other than those caused by misuse, e.g., {@link IllegalStateException}.  {@code
     * CheckedForwardingClientCall} provides {@code checkedStart()} in which throwing exceptions is
     * allowed.
     */
    public abstract static class CheckedForwardingClientCall<ReqT, RespT> extends io.grpc.ForwardingClientCall<ReqT, RespT> {

        private ClientCall<ReqT, RespT> delegate;

        /**
         * Subclasses implement the start logic here that would normally belong to {@code start()}.
         * 实现子类在这里开始的逻辑应当数据 start()
         *
         * <p>Implementation should call {@code this.delegate().start()} in the normal path. Exceptions
         * may safely be thrown prior to calling {@code this.delegate().start()}. Such exceptions will
         * be handled by {@code CheckedForwardingClientCall} and be delivered to {@code
         * responseListener}.  Exceptions <em>must not</em> be thrown after calling {@code
         * this.delegate().start()}, as this can result in {@link ClientCall.Listener#onClose} being
         * called multiple times.
         * <p>
         * 实现应当以正常的方式调用 this.delegate().start()，异常可能会预先以安全的方式抛出，一些异常会被
         * CheckedForwardingClientCall 处理，投递给 responseListener，在调用了 this.delegate().start()
         * 不能再抛出异常，否则可能会导致多次调用 ClientCall.Listener#onClose
         */
        protected abstract void checkedStart(Listener<RespT> responseListener,
                                             Metadata headers) throws Exception;

        protected CheckedForwardingClientCall(ClientCall<ReqT, RespT> delegate) {
            this.delegate = delegate;
        }

        @Override
        protected final ClientCall<ReqT, RespT> delegate() {
            return delegate;
        }

        /**
         * 开始一次调用，通过 responseListener 处理返回响应
         *
         * @param responseListener 响应监听器
         * @param headers          元数据
         */
        @Override
        @SuppressWarnings("unchecked")
        public final void start(Listener<RespT> responseListener, Metadata headers) {
            try {
                checkedStart(responseListener, headers);
            } catch (Exception e) {
                // Because start() doesn't throw, the caller may still try to call other methods on this
                // call object. Passing these invocations to the original delegate will cause
                // IllegalStateException because delegate().start() was not called. We switch the delegate
                // to a NO-OP one to prevent the IllegalStateException. The user will finally get notified
                // about the error through the listener.
                delegate = (ClientCall<ReqT, RespT>) NOOP_CALL;
                responseListener.onClose(Status.fromThrowable(e), new Metadata());
            }
        }
    }
}
