/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.stub;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.stub.ClientCalls.StubType;

import javax.annotation.CheckReturnValue;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Stub implementations for async stubs.
 * Async 的 Stub 实现
 *
 * <p>DO NOT MOCK: Customizing options doesn't work properly in mocks. Use InProcessChannelBuilder
 * to create a real channel suitable for testing. It is also possible to mock Channel instead.
 *
 * @since 1.26.0
 */
@ThreadSafe
@CheckReturnValue
public abstract class AbstractAsyncStub<S extends AbstractAsyncStub<S>> extends AbstractStub<S> {

    protected AbstractAsyncStub(Channel channel, CallOptions callOptions) {
        super(channel, callOptions);
    }

    /**
     * Returns a new async stub with the given channel for the provided method configurations.
     * 根据给定的 Channel 和工厂构建新的异步的 Stub
     *
     * @param factory the factory to create an async stub
     *                用于创建 Stub 的新的工厂
     * @param channel the channel that this stub will use to do communications
     *                这个 Stub 用于通信的 Channel
     * @since 1.26.0
     */
    public static <T extends AbstractStub<T>> T newStub(StubFactory<T> factory, Channel channel) {
        return newStub(factory, channel, CallOptions.DEFAULT);
    }

    /**
     * Returns a new async stub with the given channel for the provided method configurations.
     * 使用给定的工厂、Channel 和调用选项构建新的 Stub
     *
     * @param factory     the factory to create an async stub
     *                    用于创建 Stub 的新的工厂
     * @param channel     the channel that this stub will use to do communications
     *                    这个 Stub 用于通信的 Channel
     * @param callOptions the runtime call options to be applied to every call on this stub
     *                    用于 Stub 每个请求使用的调用选项
     * @since 1.26.0
     */
    public static <T extends AbstractStub<T>> T newStub(StubFactory<T> factory,
                                                        Channel channel,
                                                        CallOptions callOptions) {
        T stub = factory.newStub(channel,
                callOptions.withOption(ClientCalls.STUB_TYPE_OPTION, StubType.ASYNC));

        assert stub instanceof AbstractAsyncStub : String.format("Expected AbstractAsyncStub, but got %s.", stub.getClass());
        return stub;
    }
}
