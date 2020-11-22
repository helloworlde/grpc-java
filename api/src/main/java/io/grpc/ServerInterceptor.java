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

import javax.annotation.concurrent.ThreadSafe;

/**
 * Interface for intercepting incoming calls before that are dispatched by
 * {@link ServerCallHandler}.
 * 用于在 ServerCallHandler 调度之前拦截调用的接口
 *
 * <p>Implementers use this mechanism to add cross-cutting behavior to server-side calls. Common
 * example of such behavior include:
 * <ul>
 * <li>Enforcing valid authentication credentials</li>
 * <li>Logging and monitoring call behavior</li>
 * <li>Delegating calls to other servers</li>
 * </ul>
 * <p>
 * 实现使用这个机制将交叉行为添加到服务端的调用中，此类行为的常见示例包括：
 * 1. 强制校验授权
 * 2. 记录监控调用行为
 * 3. 代理调用其他的 Server
 */
@ThreadSafe
public interface ServerInterceptor {
    /**
     * Intercept {@link ServerCall} dispatch by the {@code next} {@link ServerCallHandler}. General
     * semantics of {@link ServerCallHandler#startCall} apply and the returned
     * {@link io.grpc.ServerCall.Listener} must not be {@code null}.
     * 在 ServerCallHandler 调度之前拦截调用，调用了 ServerCallHandler#startCall 之后返回的
     * io.grpc.ServerCall.Listener 不能为 null
     *
     * <p>If the implementation throws an exception, {@code call} will be closed with an error.
     * Implementations must not throw an exception if they started processing that may use {@code
     * call} on another thread.
     * 如果实现抛出异常，调用会以错误关闭；如果实现在另一个线程处理，调用后不能抛出异常
     *
     * @param call    object to receive response messages
     *                用于接收响应消息的对象
     * @param headers which can contain extra call metadata from {@link ClientCall#start},
     *                e.g. authentication credentials.
     *                包含额外调用信息的请求头，如认证凭证
     * @param next    next processor in the interceptor chain
     *                下一个处理的拦截器
     * @return listener for processing incoming messages for {@code call}, never {@code null}.
     * 用于处理后续消息的监听器，不能是 null
     */
    <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                          Metadata headers,
                                                          ServerCallHandler<ReqT, RespT> next);
}
