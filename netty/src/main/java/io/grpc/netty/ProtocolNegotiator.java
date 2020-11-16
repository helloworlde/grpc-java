/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.netty;

import io.netty.channel.ChannelHandler;
import io.netty.util.AsciiString;

/**
 * An class that provides a Netty handler to control protocol negotiation.
 * 用于提供协议协商控制的 Netty 处理器
 */
interface ProtocolNegotiator {

    /**
     * The HTTP/2 scheme to be used when sending {@code HEADERS}.
     * 发送 HEADERS 时使用的 HTTP2 的协议
     */
    AsciiString scheme();

    /**
     * Creates a new handler to control the protocol negotiation. Once the negotiation has completed
     * successfully, the provided handler is installed. Must call {@code
     * grpcHandler.onHandleProtocolNegotiationCompleted()} at certain point if the negotiation has
     * completed successfully.
     * 为协议协商创建一个新的处理器，一旦协商完成，这个提供器就安装完成了，如果协商通过，必须调用
     * grpcHandler.onHandleProtocolNegotiationCompleted() 方法
     */
    ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler);

    /**
     * Releases resources held by this negotiator. Called when the Channel transitions to terminated
     * or when InternalServer is shutdown (depending on client or server). That means handlers
     * returned by {@link #newHandler()} can outlive their parent negotiator on server-side, but not
     * on client-side.
     * <p>
     * 释放协商器持有的资源，当 Channel 过渡到终止阶段，或者内部服务器关闭的时候调用，意味着 newHandler 返回的处理器
     * 可以在 server 端独立存活，但是不能在客户端存活
     */
    void close();
}
