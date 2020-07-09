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
 * A virtual connection to a conceptual endpoint, to perform RPCs. A channel is free to have zero or
 * many actual connections to the endpoint based on configuration, load, etc. A channel is also free
 * to determine which actual endpoints to use and may change it every RPC, permitting client-side
 * load balancing. Applications are generally expected to use stubs instead of calling this class
 * directly.
 * <p>
 * 用于执行 RPC 的到概念上的端点的虚拟连接，基于负载和配置，一个 channel 可以有 0 个或多个真实连接，
 * channel 自由的来决定使用哪个实际的端点，并且可以在每个 RPC 中更改它，允许客户端的负载均衡，
 * 应用程序通常使用 stub，而不是直接调用这个类
 *
 * <p>Applications can add common cross-cutting behaviors to stubs by decorating Channel
 * implementations using {@link ClientInterceptor}. It is expected that most application
 * code will not use this class directly but rather work with stubs that have been bound to a
 * Channel that was decorated during application initialization.
 * <p>
 * 通过使用 ClientInterceptor 装饰 channel 实现，应用可以将常见的横切行为添加到 stub 中，
 * 大多数应用程序代码不应该直接使用这个类，而是使用绑定到 channel 的 stub ，该 channel 在应用程序初始化期间被修饰
 */
@ThreadSafe
public abstract class Channel {
    /**
     * Create a {@link ClientCall} to the remote operation specified by the given
     * {@link MethodDescriptor}. The returned {@link ClientCall} does not trigger any remote
     * behavior until {@link ClientCall#start(ClientCall.Listener, Metadata)} is
     * invoked.
     * <p>
     * 根据所给 MethodDescriptor 创建用于远程调用的 ClientCall，返回的 ClientCall 在调用 ClientCall#start
     * 方法之前不会有任何远程行为
     *
     * @param methodDescriptor describes the name and parameter types of the operation to call.
     *                         描述要调用的方法名称和参数
     * @param callOptions      runtime options to be applied to this call.
     *                         这次调用的参数
     * @return a {@link ClientCall} bound to the specified method.
     * @since 1.0.0
     */
    public abstract <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
            MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions);

    /**
     * The authority of the destination this channel connects to. Typically this is in the format
     * {@code host:port}.
     * channel 要连接的目标地址，通常是 host:port 格式的
     *
     * @since 1.0.0
     */
    public abstract String authority();
}
