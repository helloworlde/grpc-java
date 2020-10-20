/*
 * Copyright 2016 The gRPC Authors
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

import io.grpc.CallOptions;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.Executor;

/**
 * The client-side transport typically encapsulating a single connection to a remote
 * server. However, streams created before the client has discovered any server address may
 * eventually be issued on different connections.  All methods on the transport and its callbacks
 * are expected to execute quickly.
 * <p>
 * 客户端的 Transport 通常封装一个连接远程 server 的单独的连接，但是，在发现服务之前创建的流可能会使用任意连接，
 * Transport 的所有方法和回调都希望可以快速执行
 */
@ThreadSafe
public interface ClientTransport extends InternalInstrumented<SocketStats> {

    /**
     * Creates a new stream for sending messages to a remote end-point.
     * 创建新的流，用于给远程服务端发送消息
     *
     * <p>This method returns immediately and does not wait for any validation of the request. If
     * creation fails for any reason, {@link ClientStreamListener#closed} will be called to provide
     * the error information. Any sent messages for this stream will be buffered until creation has
     * completed (either successfully or unsuccessfully).
     * 这个方法会立即返回，不验证请求，如果因为任何原因创建失败，会调用 ClientStreamListener#closed 提供错误信息，
     * 这个流发送的任何信息都会被缓存直到请求完成（无论成功或失败）
     *
     * <p>This method is called under the {@link io.grpc.Context} of the {@link io.grpc.ClientCall}.
     * 这个方法被 ClientCall 的 Context 调用
     *
     * @param method      the descriptor of the remote method to be called for this stream.
     *                    这个流被调用的远程方法的描述
     * @param headers     to send at the beginning of the call
     *                    在调用开始会被发送的信息
     * @param callOptions runtime options of the call
     *                    调用执行时的选项
     * @return the newly created stream. 新创建的流
     */
    // TODO(nmittler): Consider also throwing for stopping.
    ClientStream newStream(MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions);

    /**
     * Pings a remote endpoint. When an acknowledgement is received, the given callback will be
     * invoked using the given executor.
     * ping 远程的端点，当收到 ack 之后，会使用所给的 Executor 执行回调
     *
     * <p>Pings are not necessarily sent to the same endpont, thus a successful ping only means at
     * least one endpoint responded, but doesn't imply the availability of other endpoints (if there
     * is any).
     * ping 没有必要发给同一个端点，ping 仅意味着至少一个端点有响应，并不代表其他的端点有响应
     *
     * <p>This is an optional method. Transports that do not have any mechanism by which to ping the
     * remote endpoint may throw {@link UnsupportedOperationException}.
     * 可选的方法，没有任何可 ping 的远程端点可能会抛出异常
     */
    void ping(PingCallback callback, Executor executor);

    /**
     * A callback that is invoked when the acknowledgement to a {@link #ping} is received. Exactly one
     * of the two methods should be called per {@link #ping}.
     * 当调用 ping 接收到 ack 时的回调，每个 ping 应该恰好调用两种方法中的一种
     */
    interface PingCallback {

        /**
         * Invoked when a ping is acknowledged. The given argument is the round-trip time of the ping,
         * in nanoseconds.
         * 当接收到 ack 时调用，参数是 ping 往返的纳秒数
         *
         * @param roundTripTimeNanos the round-trip duration between the ping being sent and the
         *                           acknowledgement received
         *                           ping 发送和接收的纳秒的时间间隔
         */
        void onSuccess(long roundTripTimeNanos);

        /**
         * Invoked when a ping fails. The given argument is the cause of the failure.
         * 当 ping 失败时调用，给定的参数是失败的原因
         *
         * @param cause the cause of the ping failure
         *              ping 失败的原因
         */
        void onFailure(Throwable cause);
    }
}
