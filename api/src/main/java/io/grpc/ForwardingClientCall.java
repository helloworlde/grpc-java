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

package io.grpc;

/**
 * A {@link ClientCall} which forwards all of it's methods to another {@link ClientCall}.
 * 会将所有请求转发给另一个 ClientCall 的 ClientCall
 */
public abstract class ForwardingClientCall<ReqT, RespT> extends PartialForwardingClientCall<ReqT, RespT> {
    /**
     * Returns the delegated {@code ClientCall}.
     * 返回被代理的 ClientCall
     */
    @Override
    protected abstract ClientCall<ReqT, RespT> delegate();

    /**
     * 发送消息
     *
     * @param responseListener 接收响应
     * @param headers          包含额外的元数据，如鉴权
     */
    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {
        delegate().start(responseListener, headers);
    }

    /**
     * 发送消息
     *
     * @param message message to be sent to the server.
     *                要发送给 Server 的消息
     */
    @Override
    public void sendMessage(ReqT message) {
        delegate().sendMessage(message);
    }

    /**
     * A simplified version of {@link ForwardingClientCall} where subclasses can pass in a {@link
     * ClientCall} as the delegate.
     * 简化的 ForwardingClientCall，其子类可以传入 ClientCall 作为代理
     */
    public abstract static class SimpleForwardingClientCall<ReqT, RespT> extends ForwardingClientCall<ReqT, RespT> {

        private final ClientCall<ReqT, RespT> delegate;

        protected SimpleForwardingClientCall(ClientCall<ReqT, RespT> delegate) {
            this.delegate = delegate;
        }

        @Override
        protected ClientCall<ReqT, RespT> delegate() {
            return delegate;
        }
    }
}
