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

/**
 * Accessor to internal methods of {@link ServerInterceptors}.
 * 访问 ServerInterceptors 内部方法
 */
@Internal
public final class InternalServerInterceptors {

    /**
     * 创建处理器
     *
     * @param interceptor 拦截器
     * @param callHandler 处理器
     * @return 处理器
     */
    public static <ReqT, RespT> ServerCallHandler<ReqT, RespT> interceptCallHandler(ServerInterceptor interceptor,
                                                                                    ServerCallHandler<ReqT, RespT> callHandler) {
        return ServerInterceptors.InterceptCallHandler.create(interceptor, callHandler);
    }


    /**
     * 包装方法，添加了处理器和监听器
     *
     * @param definition    方法定义
     * @param wrappedMethod 包装的方法
     * @return 添加了处理器和监听器的方法定义
     */
    public static <OrigReqT, OrigRespT, WrapReqT, WrapRespT> ServerMethodDefinition<WrapReqT, WrapRespT> wrapMethod(
            final ServerMethodDefinition<OrigReqT, OrigRespT> definition,
            final MethodDescriptor<WrapReqT, WrapRespT> wrappedMethod) {
        return ServerInterceptors.wrapMethod(definition, wrappedMethod);
    }

    /**
     * 创建拦截器处理器
     *
     * @param interceptor 拦截器
     * @param callHandler 处理器
     * @return 拦截器处理器
     */
    public static <ReqT, RespT> ServerCallHandler<ReqT, RespT> interceptCallHandlerCreate(ServerInterceptor interceptor,
                                                                                          ServerCallHandler<ReqT, RespT> callHandler) {
        return ServerInterceptors.InterceptCallHandler.create(interceptor, callHandler);
    }

    private InternalServerInterceptors() {
    }
}
