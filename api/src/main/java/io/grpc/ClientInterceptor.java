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
 * Interface for intercepting outgoing calls before they are dispatched by a {@link Channel}.
 * 用于在被 Channel 转发之前拦截出站调用的接口
 *
 * <p>Implementers use this mechanism to add cross-cutting behavior to {@link Channel} and
 * stub implementations. Common examples of such behavior include:
 * <ul>
 * <li>Logging and monitoring call behavior</li>
 * <li>Adding metadata for proxies to observe</li>
 * <li>Request and response rewriting</li>
 * </ul>
 * 实现可以使用这种机制在 Channel 和 Stub 上添加横切的行为，通常的使用包括
 * - 记录和监控调用行为
 * - 添加元数据以供代理观察
 * - 请求和响应重写
 *
 * <p>Providing authentication credentials is better served by {@link
 * CallCredentials}. But a {@code ClientInterceptor} could set the {@code
 * CallCredentials} within the {@link CallOptions}.
 * CallCredentials 可以更好的添加授权信息，但是 ClientInterceptor 可以在 CallOptions 中设置 CallCredentials
 */
@ThreadSafe
public interface ClientInterceptor {
    /**
     * Intercept {@link ClientCall} creation by the {@code next} {@link Channel}.
     * 拦截 next Channel 创建的 ClientCall
     *
     * <p>Many variations of interception are possible. Complex implementations may return a wrapper
     * around the result of {@code next.newCall()}, whereas a simpler implementation may just modify
     * the header metadata prior to returning the result of {@code next.newCall()}.
     * 拦截可能有很多种变化，复杂的实现可能会返回有一个封装后的 next.newCall() 的结果，简单的实现可能仅在 next.newCall()
     * 返回前修改请求的元数据
     *
     * <p>{@code next.newCall()} <strong>must not</strong> be called under a different {@link Context}
     * other than the current {@code Context}. The outcome of such usage is undefined and may cause
     * memory leak due to unbounded chain of {@code Context}s.
     * next.newCall() 不能在不同的 Context 下调用，这种用法是不确定的，并且因为 Context 无限调用链可能导致内存泄露
     *
     * @param method      the remote method to be called.
     *                    被调用的远程方法
     * @param callOptions the runtime options to be applied to this call.
     *                    这个调用中使用的调用参数
     * @param next        the channel which is being intercepted.
     *                    被拦截的 channel
     * @return the call object for the remote operation, never {@code null}.
     * 用于远程操作的调用对象，不能是 null
     */
    <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                        CallOptions callOptions,
                                                        Channel next);
}
