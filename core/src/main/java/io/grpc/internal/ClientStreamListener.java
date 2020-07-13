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

package io.grpc.internal;

import io.grpc.Metadata;
import io.grpc.Status;

/**
 * An observer of client-side stream events.
 * 客户端的流事件监听器
 */
public interface ClientStreamListener extends StreamListener {
    /**
     * Called upon receiving all header information from the remote end-point. Note that transports
     * are not required to call this method if no header information is received, this would occur
     * when a stream immediately terminates with an error and only
     * {@link #closed(io.grpc.Status, Metadata)} is called.
     * <p>
     * 当收到所有服务端返回的 header 时调用，如果没有 header 返回，Transport 不会调用这个方法，当流被立即终止且有
     * 错误时只有 closed() 方法被调用
     *
     * <p>This method should return quickly, as the same thread may be used to process other streams.
     * 这个方法会快速返回，同一个线程可能会处理其他的流
     *
     * @param headers the fully buffered received headers.
     *                已经完全缓冲的接收到的 header
     */
    void headersRead(Metadata headers);

    /**
     * Called when the stream is fully closed. {@link
     * io.grpc.Status.Code#OK} is the only status code that is guaranteed
     * to have been sent from the remote server. Any other status code may have been caused by
     * abnormal stream termination. This is guaranteed to always be the final call on a listener. No
     * further callbacks will be issued.
     * <p>
     * 当流被完全关闭时调用，OK 是唯一保证已从远程服务器发送的状态代码，流终止异常可能导致其他的状态码，这是监听器
     * 最后的调用，不会有更多的回调
     *
     * <p>This method should return quickly, as the same thread may be used to process other streams.
     * 这个方法会快速返回，同一个线程可能会处理其他的流
     *
     * @param status   details about the remote closure
     *                 server 端关闭时的状态
     * @param trailers trailing metadata
     *                 响应的元数据
     */
    // TODO(zdapeng): remove this method in favor of the 3-arg one.
    void closed(Status status, Metadata trailers);

    /**
     * Called when the stream is fully closed. {@link
     * io.grpc.Status.Code#OK} is the only status code that is guaranteed
     * to have been sent from the remote server. Any other status code may have been caused by
     * abnormal stream termination. This is guaranteed to always be the final call on a listener. No
     * further callbacks will be issued.
     * 当流完全关闭时调用，OK 是唯一保证已从远程服务器发送的状态代码，流终止异常可能导致其他的状态码，这是监听器
     * 最后的调用，不会有更多的回调
     *
     * <p>This method should return quickly, as the same thread may be used to process other streams.
     *
     * @param status      details about the remote closure
     *                    server 端关闭时的状态
     * @param rpcProgress RPC progress when client stream listener is closed
     *                    客户端关闭时，RPC 的进程
     * @param trailers    trailing metadata
     *                    响应的元数据
     */
    void closed(Status status, RpcProgress rpcProgress, Metadata trailers);

    /**
     * The progress of the RPC when client stream listener is closed.
     * 当客户端流监听器关闭时 RPC 的进程
     */
    enum RpcProgress {
        /**
         * The RPC is processed by the server normally.
         * RPC 被服务端正常处理
         */
        PROCESSED,
        /**
         * The RPC is not processed by the server's application logic.
         * RPC 没有被服务端的应用逻辑层处理
         */
        REFUSED,
        /**
         * The RPC is dropped (by load balancer).
         * RPC 被负载均衡丢弃了
         */
        DROPPED
    }
}
