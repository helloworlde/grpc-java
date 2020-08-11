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

/**
 * Definition of a method exposed by a {@link Server}.
 * Server 公开的方法定义
 *
 * @see ServerServiceDefinition
 */
public final class ServerMethodDefinition<ReqT, RespT> {
    /**
     * 方法
     */
    private final MethodDescriptor<ReqT, RespT> method;
    /**
     * 处理器
     */
    private final ServerCallHandler<ReqT, RespT> handler;

    private ServerMethodDefinition(MethodDescriptor<ReqT, RespT> method,
                                   ServerCallHandler<ReqT, RespT> handler) {
        this.method = method;
        this.handler = handler;
    }

    /**
     * Create a new instance.
     * 创建一个新的方法定义实例
     *
     * @param method  the {@link MethodDescriptor} for this method.
     *                包装方法
     * @param handler to dispatch calls to.
     *                处理器
     * @return a new instance 方法定义
     */
    public static <ReqT, RespT> ServerMethodDefinition<ReqT, RespT> create(MethodDescriptor<ReqT, RespT> method,
                                                                           ServerCallHandler<ReqT, RespT> handler) {
        return new ServerMethodDefinition<>(method, handler);
    }

    /**
     * The {@code MethodDescriptor} for this method.
     */
    public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
        return method;
    }

    /**
     * Handler for incoming calls.
     */
    public ServerCallHandler<ReqT, RespT> getServerCallHandler() {
        return handler;
    }

    /**
     * Create a new method definition with a different call handler.
     * 用不同的调用处理器创建新的方法定义
     *
     * @param handler to bind to a cloned instance of this.
     *                要与方法定义克隆绑定的处理器
     * @return a cloned instance of this with the new handler bound 绑定了处理器的方法的克隆实例
     */
    public ServerMethodDefinition<ReqT, RespT> withServerCallHandler(ServerCallHandler<ReqT, RespT> handler) {
        return new ServerMethodDefinition<>(method, handler);
    }
}
