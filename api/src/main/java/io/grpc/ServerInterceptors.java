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

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Utility methods for working with {@link ServerInterceptor}s.
 * Server 端拦截器的工具方法
 */
public final class ServerInterceptors {

    // Prevent instantiation
    private ServerInterceptors() {
    }

    /**
     * Create a new {@code ServerServiceDefinition} whose {@link ServerCallHandler}s will call
     * {@code interceptors} before calling the pre-existing {@code ServerCallHandler}. The first
     * interceptor will have its {@link ServerInterceptor#interceptCall} called first.
     * 使用服务定义和拦截器创建一个在调用已经存在的 ServerCallHandler 之前调用拦截器的服务定义，
     * 第一个拦截器将首先滴啊用其 ServerInterceptor#interceptCall
     *
     * @param serviceDef   the service definition for which to intercept all its methods.
     *                     需要拦截其所有方法的服务定义
     * @param interceptors array of interceptors to apply to the service.
     *                     用于拦截服务的拦截器数组
     * @return a wrapped version of {@code serviceDef} with the interceptors applied.
     * 使用指定拦截器包装的服务定义
     */
    public static ServerServiceDefinition interceptForward(ServerServiceDefinition serviceDef,
                                                           ServerInterceptor... interceptors) {
        return interceptForward(serviceDef, Arrays.asList(interceptors));
    }

    /**
     * 使用服务定义和拦截器创建一个在调用已经存在的 ServerCallHandler 之前调用拦截器的服务定义，
     * * 第一个拦截器将首先滴啊用其 ServerInterceptor#interceptCall
     *
     * @param bindableService 需要拦截其所有方法的服务定义
     * @param interceptors    用于拦截服务的拦截器数组
     * @return 使用指定拦截器包装的服务定义
     */
    public static ServerServiceDefinition interceptForward(BindableService bindableService,
                                                           ServerInterceptor... interceptors) {
        return interceptForward(bindableService.bindService(), Arrays.asList(interceptors));
    }

    /**
     * Create a new {@code ServerServiceDefinition} whose {@link ServerCallHandler}s will call
     * {@code interceptors} before calling the pre-existing {@code ServerCallHandler}. The first
     * interceptor will have its {@link ServerInterceptor#interceptCall} called first.
     * 使用服务定义和拦截器创建一个在调用已经存在的 ServerCallHandler 之前调用拦截器的服务定义，
     * 第一个拦截器将首先滴啊用其 ServerInterceptor#interceptCall
     *
     * @param serviceDef   the service definition for which to intercept all its methods.
     *                     需要拦截其所有方法的服务定义
     * @param interceptors list of interceptors to apply to the service.
     *                     用于拦截服务的拦截器数组
     * @return a wrapped version of {@code serviceDef} with the interceptors applied.
     * 使用指定拦截器包装的服务定义
     */
    public static ServerServiceDefinition interceptForward(ServerServiceDefinition serviceDef,
                                                           List<? extends ServerInterceptor> interceptors) {
        List<? extends ServerInterceptor> copy = new ArrayList<>(interceptors);
        // 反转拦截器顺序
        Collections.reverse(copy);
        // 包装方法，重新构建服务定义
        return intercept(serviceDef, copy);
    }

    public static ServerServiceDefinition interceptForward(BindableService bindableService,
                                                           List<? extends ServerInterceptor> interceptors) {
        return interceptForward(bindableService.bindService(), interceptors);
    }

    /**
     * 与 {@link #interceptForward(ServerServiceDefinition, ServerInterceptor...)} 的区别是没有反转
     * 拦截器的顺序
     * <p>
     * Create a new {@code ServerServiceDefinition} whose {@link ServerCallHandler}s will call
     * {@code interceptors} before calling the pre-existing {@code ServerCallHandler}. The last
     * interceptor will have its {@link ServerInterceptor#interceptCall} called first.
     *
     * @param serviceDef   the service definition for which to intercept all its methods.
     * @param interceptors array of interceptors to apply to the service.
     * @return a wrapped version of {@code serviceDef} with the interceptors applied.
     */
    public static ServerServiceDefinition intercept(ServerServiceDefinition serviceDef,
                                                    ServerInterceptor... interceptors) {
        return intercept(serviceDef, Arrays.asList(interceptors));
    }

    public static ServerServiceDefinition intercept(BindableService bindableService,
                                                    ServerInterceptor... interceptors) {
        Preconditions.checkNotNull(bindableService, "bindableService");
        return intercept(bindableService.bindService(), Arrays.asList(interceptors));
    }

    /**
     * Create a new {@code ServerServiceDefinition} whose {@link ServerCallHandler}s will call
     * {@code interceptors} before calling the pre-existing {@code ServerCallHandler}. The last
     * interceptor will have its {@link ServerInterceptor#interceptCall} called first.
     * 使用服务定义和拦截器创建一个在调用已经存在的 ServerCallHandler 之前调用拦截器的服务定义，
     * 第一个拦截器将首先滴啊用其 ServerInterceptor#interceptCall
     *
     * @param serviceDef   the service definition for which to intercept all its methods.
     *                     需要拦截其所有方法的服务定义
     * @param interceptors list of interceptors to apply to the service.
     *                     用于拦截服务的拦截器集合
     * @return a wrapped version of {@code serviceDef} with the interceptors applied.
     * 使用指定拦截器包装的服务定义
     */
    public static ServerServiceDefinition intercept(ServerServiceDefinition serviceDef,
                                                    List<? extends ServerInterceptor> interceptors) {
        Preconditions.checkNotNull(serviceDef, "serviceDef");
        // 如果拦截器是空的，则返回
        if (interceptors.isEmpty()) {
            return serviceDef;
        }
        // 构建新的服务定义
        ServerServiceDefinition.Builder serviceDefBuilder = ServerServiceDefinition.builder(serviceDef.getServiceDescriptor());
        // 遍历所有方法，为方法指定拦截器
        for (ServerMethodDefinition<?, ?> method : serviceDef.getMethods()) {
            wrapAndAddMethod(serviceDefBuilder, method, interceptors);
        }
        return serviceDefBuilder.build();
    }

    public static ServerServiceDefinition intercept(BindableService bindableService,
                                                    List<? extends ServerInterceptor> interceptors) {
        Preconditions.checkNotNull(bindableService, "bindableService");
        return intercept(bindableService.bindService(), interceptors);
    }

    /**
     * Create a new {@code ServerServiceDefinition} whose {@link MethodDescriptor} serializes to
     * and from InputStream for all methods.  The InputStream is guaranteed return true for
     * markSupported().  The {@code ServerCallHandler} created will automatically
     * convert back to the original types for request and response before calling the existing
     * {@code ServerCallHandler}.  Calling this method combined with the intercept methods will
     * allow the developer to choose whether to intercept messages of InputStream, or the modeled
     * types of their application.
     * 创建一个将 MethodDescriptor 序列化和反序列化所有方法为 InputStream 的服务定义，流的 markSupported
     * 会返回 true，在调用已经存在的 ServerCallHandler 之前会保证自动将请求和响应的类型转为原来的类型，
     * 将此方法与拦截方法结合使用，将允许开发人员选择是拦截 InputStream 的消息还是拦截其应用程序的类型
     *
     * @param serviceDef the service definition to convert messages to InputStream
     *                   将消息转为输入流的方法定义
     * @return a wrapped version of {@code serviceDef} with the InputStream conversion applied.
     * 将消息转为输入流的服务定义的封装
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1712")
    public static ServerServiceDefinition useInputStreamMessages(final ServerServiceDefinition serviceDef) {

        final MethodDescriptor.Marshaller<InputStream> marshaller = new MethodDescriptor.Marshaller<InputStream>() {
            @Override
            public InputStream stream(final InputStream value) {
                return value;
            }

            @Override
            public InputStream parse(final InputStream stream) {
                if (stream.markSupported()) {
                    return stream;
                } else if (stream instanceof KnownLength) {
                    return new KnownLengthBufferedInputStream(stream);
                } else {
                    return new BufferedInputStream(stream);
                }
            }
        };

        return useMarshalledMessages(serviceDef, marshaller);
    }

    /**
     * {@link BufferedInputStream} that also implements {@link KnownLength}.
     */
    private static final class KnownLengthBufferedInputStream extends BufferedInputStream implements KnownLength {
        KnownLengthBufferedInputStream(InputStream in) {
            super(in);
        }
    }

    /**
     * Create a new {@code ServerServiceDefinition} whose {@link MethodDescriptor} serializes to
     * and from T for all methods.  The {@code ServerCallHandler} created will automatically
     * convert back to the original types for request and response before calling the existing
     * {@code ServerCallHandler}.  Calling this method combined with the intercept methods will
     * allow the developer to choose whether to intercept messages of T, or the modeled types
     * of their application.  This can also be chained to allow for interceptors to handle messages
     * as multiple different T types within the chain if the added cost of serialization is not
     * a concern.
     *
     * @param serviceDef the service definition to convert messages to T
     * @return a wrapped version of {@code serviceDef} with the T conversion applied.
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1712")
    public static <T> ServerServiceDefinition useMarshalledMessages(final ServerServiceDefinition serviceDef,
                                                                    final MethodDescriptor.Marshaller<T> marshaller) {
        List<ServerMethodDefinition<?, ?>> wrappedMethods = new ArrayList<>();
        List<MethodDescriptor<?, ?>> wrappedDescriptors = new ArrayList<>();

        // Wrap the descriptors
        // 遍历方法定义，封装
        for (final ServerMethodDefinition<?, ?> definition : serviceDef.getMethods()) {
            final MethodDescriptor<?, ?> originalMethodDescriptor = definition.getMethodDescriptor();
            final MethodDescriptor<T, T> wrappedMethodDescriptor = originalMethodDescriptor.toBuilder(marshaller, marshaller).build();
            wrappedDescriptors.add(wrappedMethodDescriptor);
            wrappedMethods.add(wrapMethod(definition, wrappedMethodDescriptor));
        }
        // Build the new service descriptor
        // 构建新的服务描述构建器
        final ServiceDescriptor.Builder serviceDescriptorBuilder = ServiceDescriptor.newBuilder(serviceDef.getServiceDescriptor().getName())
                                                                                    .setSchemaDescriptor(serviceDef.getServiceDescriptor().getSchemaDescriptor());
        // 遍历方法描述，添加到服务描述构建器中
        for (MethodDescriptor<?, ?> wrappedDescriptor : wrappedDescriptors) {
            serviceDescriptorBuilder.addMethod(wrappedDescriptor);
        }

        // Create the new service definition.
        // 构建服务定义构建器，遍历方法定义，添加到构建器中
        final ServerServiceDefinition.Builder serviceBuilder = ServerServiceDefinition.builder(serviceDescriptorBuilder.build());
        for (ServerMethodDefinition<?, ?> definition : wrappedMethods) {
            serviceBuilder.addMethod(definition);
        }
        return serviceBuilder.build();
    }

    /**
     * 使用拦截器包装方法
     *
     * @param serviceDefBuilder 服务构建器
     * @param method            方法
     * @param interceptors      拦截器
     */
    private static <ReqT, RespT> void wrapAndAddMethod(ServerServiceDefinition.Builder serviceDefBuilder,
                                                       ServerMethodDefinition<ReqT, RespT> method,
                                                       List<? extends ServerInterceptor> interceptors) {
        // 获取处理器
        ServerCallHandler<ReqT, RespT> callHandler = method.getServerCallHandler();
        // 遍历拦截器，包装处理器
        for (ServerInterceptor interceptor : interceptors) {
            callHandler = InterceptCallHandler.create(interceptor, callHandler);
        }
        // 将封装后的方法处理器放到服务构建器中
        serviceDefBuilder.addMethod(method.withServerCallHandler(callHandler));
    }

    /**
     * 请求处理拦截器处理器
     *
     * @param <ReqT>
     * @param <RespT>
     */
    static final class InterceptCallHandler<ReqT, RespT> implements ServerCallHandler<ReqT, RespT> {
        /**
         * 创建拦截器处理器
         *
         * @param interceptor 拦截器
         * @param callHandler 处理器
         * @return 拦截器处理器
         */
        public static <ReqT, RespT> InterceptCallHandler<ReqT, RespT> create(ServerInterceptor interceptor,
                                                                             ServerCallHandler<ReqT, RespT> callHandler) {
            return new InterceptCallHandler<>(interceptor, callHandler);
        }

        private final ServerInterceptor interceptor;
        private final ServerCallHandler<ReqT, RespT> callHandler;

        private InterceptCallHandler(ServerInterceptor interceptor,
                                     ServerCallHandler<ReqT, RespT> callHandler) {
            this.interceptor = Preconditions.checkNotNull(interceptor, "interceptor");
            this.callHandler = callHandler;
        }

        /**
         * 为即将发生的调用生成一个非空的监听器
         * 通过拦截器调用
         *
         * @param call    object for responding to the remote client.
         *                用于响应远程客户端的对象
         * @param headers 请求的 Header
         * @return 用于处理 ServerCall 接收的消息的监听器
         */
        @Override
        public ServerCall.Listener<ReqT> startCall(ServerCall<ReqT, RespT> call,
                                                   Metadata headers) {
            return interceptor.interceptCall(call, headers, callHandler);
        }
    }

    /**
     * 包装方法，添加了处理器和监听器
     *
     * @param definition    方法定义
     * @param wrappedMethod 被绑定的方法
     * @return 包装后的方法定义
     */
    static <OReqT, ORespT, WReqT, WRespT> ServerMethodDefinition<WReqT, WRespT> wrapMethod(
            final ServerMethodDefinition<OReqT, ORespT> definition,
            final MethodDescriptor<WReqT, WRespT> wrappedMethod) {
        // 创建处理器
        final ServerCallHandler<WReqT, WRespT> wrappedHandler = wrapHandler(definition.getServerCallHandler(),
                definition.getMethodDescriptor(),
                wrappedMethod);
        // 根据处理器和方法创建方法定义
        return ServerMethodDefinition.create(wrappedMethod, wrappedHandler);
    }

    /**
     * 包装请求监听器和响应处理器
     *
     * @param originalHandler 原有的处理器
     * @param originalMethod  原有方法
     * @param wrappedMethod   包装后的方法
     * @return 包装后的请求监听器和响应处理器
     */
    private static <OReqT, ORespT, WReqT, WRespT> ServerCallHandler<WReqT, WRespT> wrapHandler(
            final ServerCallHandler<OReqT, ORespT> originalHandler,
            final MethodDescriptor<OReqT, ORespT> originalMethod,
            final MethodDescriptor<WReqT, WRespT> wrappedMethod) {
        return new ServerCallHandler<WReqT, WRespT>() {
            @Override
            public ServerCall.Listener<WReqT> startCall(final ServerCall<WReqT, WRespT> call, final Metadata headers) {
                // 创建支持转发的 ServerCall
                final ServerCall<OReqT, ORespT> unwrappedCall = new PartialForwardingServerCall<OReqT, ORespT>() {
                    @Override
                    protected ServerCall<WReqT, WRespT> delegate() {
                        return call;
                    }

                    @Override
                    public void sendMessage(ORespT message) {
                        // 解析响应并转发给代理的处理器
                        final InputStream is = originalMethod.streamResponse(message);
                        final WRespT wrappedMessage = wrappedMethod.parseResponse(is);
                        delegate().sendMessage(wrappedMessage);
                    }

                    @Override
                    public MethodDescriptor<OReqT, ORespT> getMethodDescriptor() {
                        return originalMethod;
                    }
                };

                // 调用并返回监听器
                final ServerCall.Listener<OReqT> originalListener = originalHandler.startCall(unwrappedCall, headers);

                return new PartialForwardingServerCallListener<WReqT>() {
                    @Override
                    protected ServerCall.Listener<OReqT> delegate() {
                        return originalListener;
                    }

                    @Override
                    public void onMessage(WReqT message) {
                        // 解析请求并转发给代理的监听器
                        final InputStream is = wrappedMethod.streamRequest(message);
                        final OReqT originalMessage = originalMethod.parseRequest(is);
                        delegate().onMessage(originalMessage);
                    }
                };
            }
        };
    }
}
