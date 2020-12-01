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

package io.grpc.stub;

import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Deadline;
import io.grpc.ExperimentalApi;
import io.grpc.ManagedChannelBuilder;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Common base type for stub implementations. Stub configuration is immutable; changing the
 * configuration returns a new stub with updated configuration. Changing the configuration is cheap
 * and may be done before every RPC, such as would be common when using {@link #withDeadlineAfter}.
 * <p>
 * Stub 实现的通用类型，Stub 的配置是不可变的，更改配置会返回一个新的配置的新创建的 Stub，修改配置是低成本的可以
 * 在每个请求之前就完成，例如使用 withDeadlineAfter 时常见的情况
 *
 * <p>Configuration is stored in {@link CallOptions} and is passed to the {@link Channel} when
 * performing an RPC.
 * 配置存储在 CallOptions 中并且在执行请求的时候传递给 Channel
 *
 * <p>DO NOT MOCK: Customizing options doesn't work properly in mocks. Use InProcessChannelBuilder
 * to create a real channel suitable for testing. It is also possible to mock Channel instead.
 *
 * @param <S> the concrete type of this stub.
 *            Stub 具体的类型
 * @since 1.0.0
 */
@ThreadSafe
@CheckReturnValue
public abstract class AbstractStub<S extends AbstractStub<S>> {

    // 这个 Stub 用于通信的 Channel
    private final Channel channel;

    // 请求配置信息
    private final CallOptions callOptions;

    /**
     * Constructor for use by subclasses, with the default {@code CallOptions}.
     * 子类使用的构造器，使用默认的 CallOptions
     *
     * @param channel the channel that this stub will use to do communications
     *                这个 Stub 用于通信的 Channel
     * @since 1.0.0
     */
    protected AbstractStub(Channel channel) {
        this(channel, CallOptions.DEFAULT);
    }

    /**
     * Constructor for use by subclasses.
     * 子类使用的构造器
     *
     * @param channel     the channel that this stub will use to do communications
     *                    这个 Stub 用于通信的 Channel
     * @param callOptions the runtime call options to be applied to every call on this stub
     *                    用于 Stub 每个请求使用的调用选项
     * @since 1.0.0
     */
    protected AbstractStub(Channel channel, CallOptions callOptions) {
        this.channel = checkNotNull(channel, "channel");
        this.callOptions = checkNotNull(callOptions, "callOptions");
    }

    /**
     * The underlying channel of the stub.
     * Stub 的基础 Channel
     *
     * @since 1.0.0
     */
    public final Channel getChannel() {
        return channel;
    }

    /**
     * The {@code CallOptions} of the stub.
     * Stub 的调用选项
     *
     * @since 1.0.0
     */
    public final CallOptions getCallOptions() {
        return callOptions;
    }

    /**
     * Returns a new stub with the given channel for the provided method configurations.
     * 使用所给的 Channel 和调用选项构建新的 Stub
     *
     * @param channel     the channel that this stub will use to do communications
     *                    这个 Stub 用于通信的 Channel
     * @param callOptions the runtime call options to be applied to every call on this stub
     *                    用于 Stub 每个请求使用的调用选项
     * @since 1.0.0
     */
    protected abstract S build(Channel channel, CallOptions callOptions);

    /**
     * Returns a new stub with the given channel for the provided method configurations.
     * 使用给定的 Channel 和 Stub 工厂创建一个新的 Stub
     *
     * @param factory the factory to create a stub
     *                创建 Stub 的工厂
     * @param channel the channel that this stub will use to do communications
     *                这个 Stub 用于通信的 Channel
     * @since 1.26.0
     */
    public static <T extends AbstractStub<T>> T newStub(StubFactory<T> factory,
                                                        Channel channel) {
        return newStub(factory, channel, CallOptions.DEFAULT);
    }

    /**
     * Returns a new stub with the given channel for the provided method configurations.
     * 根据给定的工厂、Channel 和调用选项构建 Stub
     *
     * @param factory     the factory to create a stub
     *                    创建 Stub 的工厂
     * @param channel     the channel that this stub will use to do communications
     *                    这个 Stub 用于通信的 Channel
     * @param callOptions the runtime call options to be applied to every call on this stub
     *                    用于 Stub 每个请求使用的调用选项
     * @since 1.26.0
     */
    public static <T extends AbstractStub<T>> T newStub(StubFactory<T> factory,
                                                        Channel channel,
                                                        CallOptions callOptions) {
        return factory.newStub(channel, callOptions);
    }

    /**
     * Returns a new stub with an absolute deadline.
     * 返回一个指定了绝对的过期时间的新的 Stub
     *
     * <p>This is mostly used for propagating an existing deadline. {@link #withDeadlineAfter} is the
     * recommended way of setting a new deadline,
     * 常用于传播已经存在的超时时间，推荐使用 withDeadlineAfter 设置过期时间
     *
     * @param deadline the deadline or {@code null} for unsetting the deadline.
     *                 过期时间，如果没有则是 null
     * @since 1.0.0
     */
    public final S withDeadline(@Nullable Deadline deadline) {
        return build(channel, callOptions.withDeadline(deadline));
    }

    /**
     * Returns a new stub with a deadline that is after the given {@code duration} from now.
     * 返回一个指定了从现在开始时间为 duration 的过期时间的新的 Stub
     *
     * @see CallOptions#withDeadlineAfter
     * @since 1.0.0
     */
    public final S withDeadlineAfter(long duration, TimeUnit unit) {
        return build(channel, callOptions.withDeadlineAfter(duration, unit));
    }

    /**
     * Returns a new stub with the given executor that is to be used instead of the default one
     * specified with {@link ManagedChannelBuilder#executor}. Note that setting this option may not
     * take effect for blocking calls.
     * 返回一个根据给定的执行器创建的新的 Stub，用于代替默认指定的 ManagedChannelBuilder#executor，注意对于
     * 阻塞的调用这个配置不会生效
     *
     * @since 1.8.0
     */
    public final S withExecutor(Executor executor) {
        return build(channel, callOptions.withExecutor(executor));
    }

    /**
     * Set's the compressor name to use for the call.  It is the responsibility of the application
     * to make sure the server supports decoding the compressor picked by the client.  To be clear,
     * this is the compressor used by the stub to compress messages to the server.  To get
     * compressed responses from the server, set the appropriate {@link io.grpc.DecompressorRegistry}
     * on the {@link io.grpc.ManagedChannelBuilder}.
     * 设置用于调用的压缩器名称，需要应用确认服务端支持解码客户端指定的压缩器，这个压缩器用于压缩发送给服务端的消息，
     * 如果获取服务端压缩的响应，需要在 ManagedChannelBuilder 设置适当的 DecompressorRegistry
     *
     * @param compressorName the name (e.g. "gzip") of the compressor to use.
     *                       要使用的压缩器的名称
     * @since 1.0.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1704")
    public final S withCompression(String compressorName) {
        return build(channel, callOptions.withCompression(compressorName));
    }

    /**
     * Returns a new stub that uses the given channel.
     * 使用给定的 Channel 构建新的 Stub
     *
     * <p>This method is vestigial and is unlikely to be useful.  Instead, users should prefer to
     * use {@link #withInterceptors}.
     * 这个方法是残留的，不太有用，应当使用 withInterceptors 代替
     *
     * @since 1.0.0
     * @deprecated {@link #withInterceptors(ClientInterceptor...)}
     */
    @Deprecated // use withInterceptors() instead
    public final S withChannel(Channel newChannel) {
        return build(newChannel, callOptions);
    }

    /**
     * Sets a custom option to be passed to client interceptors on the channel
     * {@link io.grpc.ClientInterceptor} via the CallOptions parameter.
     * 设置用于传递给拦截器的自定义参数
     *
     * @param key   the option being set
     * @param value the value for the key
     * @since 1.0.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1869")
    public final <T> S withOption(CallOptions.Key<T> key, T value) {
        return build(channel, callOptions.withOption(key, value));
    }

    /**
     * Returns a new stub that has the given interceptors attached to the underlying channel.
     * 使用给定的拦截器创建当前 Channel 的新的 Stub
     *
     * @since 1.0.0
     */
    public final S withInterceptors(ClientInterceptor... interceptors) {
        return build(ClientInterceptors.intercept(channel, interceptors), callOptions);
    }

    /**
     * Returns a new stub that uses the given call credentials.
     * 使用给定的认证返回新的 Stub
     *
     * @since 1.0.0
     */
    public final S withCallCredentials(CallCredentials credentials) {
        return build(channel, callOptions.withCallCredentials(credentials));
    }

    /**
     * Returns a new stub that uses
     * <a href="https://github.com/grpc/grpc/blob/master/doc/wait-for-ready.md">'wait for ready'</a>
     * for the call. Wait-for-ready queues the RPC until a connection is available. This may
     * dramatically increase the latency of the RPC, but avoids failing "unnecessarily." The default
     * queues the RPC until an attempt to connect has completed, but fails RPCs without sending them
     * if unable to connect.
     * 返回使用  wait for ready 的新的 Stub，wait for ready 会将请求放到等待队列中直到连接可用，这可能会导致请求
     * 延时显著增加，但是避免了不必要的失败，默认将请求放到队列中直到一次尝试连接完成，如果无法连接，会使请求失败
     *
     * @since 1.1.0
     */
    public final S withWaitForReady() {
        return build(channel, callOptions.withWaitForReady());
    }

    /**
     * Returns a new stub that limits the maximum acceptable message size from a remote peer.
     * 返回设置了最大入站消息大小的新的 Stub
     *
     * <p>If unset, the {@link ManagedChannelBuilder#maxInboundMessageSize(int)} limit is used.
     * 如果没有设置，会使用 ManagedChannelBuilder#maxInboundMessageSize(int) 作为限制
     *
     * @since 1.1.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2563")
    public final S withMaxInboundMessageSize(int maxSize) {
        return build(channel, callOptions.withMaxInboundMessageSize(maxSize));
    }

    /**
     * Returns a new stub that limits the maximum acceptable message size to send a remote peer.
     * 返回设置了最大出站消息大小的新的 Stub
     *
     * @since 1.1.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2563")
    public final S withMaxOutboundMessageSize(int maxSize) {
        return build(channel, callOptions.withMaxOutboundMessageSize(maxSize));
    }

    /**
     * A factory class for stub.
     * 用于 Stub 的工厂类
     *
     * @since 1.26.0
     */
    public interface StubFactory<T extends AbstractStub<T>> {
        T newStub(Channel channel, CallOptions callOptions);
    }
}
