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

import com.google.common.base.MoreObjects;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalLogId;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

import java.util.concurrent.Executor;

/**
 * 支持生命周期管理的，基于连接的代理 Transport
 */
abstract class ForwardingConnectionClientTransport implements ConnectionClientTransport {

    /**
     * 开始一个 Transport
     *
     * @param listener non-{@code null} listener of transport events
     *                 非空的 Transport 事件监听器
     */
    @Override
    public Runnable start(Listener listener) {
        return delegate().start(listener);
    }

    /**
     * 关闭 Transport
     */
    @Override
    public void shutdown(Status status) {
        delegate().shutdown(status);
    }

    /**
     * 立即关闭 Transport
     */
    @Override
    public void shutdownNow(Status status) {
        delegate().shutdownNow(status);
    }

    /**
     * 创建新的流
     *
     * @param method      the descriptor of the remote method to be called for this stream.
     *                    这个流被调用的远程方法的描述
     * @param headers     to send at the beginning of the call
     *                    在调用开始会被发送的信息
     * @param callOptions runtime options of the call
     *                    调用执行时的选项
     */
    @Override
    public ClientStream newStream(MethodDescriptor<?, ?> method,
                                  Metadata headers,
                                  CallOptions callOptions) {
        return delegate().newStream(method, headers, callOptions);
    }

    /**
     * ping 远程端点
     */
    @Override
    public void ping(PingCallback callback, Executor executor) {
        delegate().ping(callback, executor);
    }

    @Override
    public InternalLogId getLogId() {
        return delegate().getLogId();
    }

    /**
     * 获取 Transport 属性
     */
    @Override
    public Attributes getAttributes() {
        return delegate().getAttributes();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
    }

    /**
     * 获取 Transport 分析
     */
    @Override
    public ListenableFuture<SocketStats> getStats() {
        return delegate().getStats();
    }

    /**
     * 代理的 Transport
     */
    protected abstract ConnectionClientTransport delegate();
}
