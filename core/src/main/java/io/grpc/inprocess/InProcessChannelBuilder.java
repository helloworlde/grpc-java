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

package io.grpc.inprocess;

import io.grpc.ChannelLogger;
import io.grpc.ExperimentalApi;
import io.grpc.Internal;
import io.grpc.internal.AbstractManagedChannelImplBuilder;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;

import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Builder for a channel that issues in-process requests. Clients identify the in-process server by
 * its name.
 * 用于进程内请求的 Channel 构建器，客户端通过进程内的服务端的名称鉴别
 *
 * <p>The channel is intended to be fully-featured, high performance, and useful in testing.
 * 代替为功能齐全的高性能的用于测试的 Channel
 *
 * <p>For usage examples, see {@link InProcessServerBuilder}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1783")
public final class InProcessChannelBuilder extends AbstractManagedChannelImplBuilder<InProcessChannelBuilder> {
    /**
     * Create a channel builder that will connect to the server with the given name.
     * 使用给定的名称构建 Channel
     *
     * @param name the identity of the server to connect to
     *             要连接的 server 的身份
     * @return a new builder
     */
    public static InProcessChannelBuilder forName(String name) {
        return new InProcessChannelBuilder(name);
    }

    /**
     * Always fails.  Call {@link #forName} instead.
     */
    public static InProcessChannelBuilder forTarget(String target) {
        throw new UnsupportedOperationException("call forName() instead");
    }

    /**
     * Always fails.  Call {@link #forName} instead.
     */
    public static InProcessChannelBuilder forAddress(String name, int port) {
        throw new UnsupportedOperationException("call forName() instead");
    }

    private final String name;
    private ScheduledExecutorService scheduledExecutorService;
    private int maxInboundMetadataSize = Integer.MAX_VALUE;
    private boolean transportIncludeStatusCause = false;

    private InProcessChannelBuilder(String name) {
        super(new InProcessSocketAddress(name), "localhost");
        this.name = checkNotNull(name, "name");
        // In-process transport should not record its traffic to the stats module.
        // https://github.com/grpc/grpc-java/issues/2284
        setStatsRecordStartedRpcs(false);
        setStatsRecordFinishedRpcs(false);
    }

    @Override
    public final InProcessChannelBuilder maxInboundMessageSize(int max) {
        // TODO(carl-mastrangelo): maybe throw an exception since this not enforced?
        return super.maxInboundMessageSize(max);
    }

    /**
     * Does nothing.
     */
    @Override
    public InProcessChannelBuilder useTransportSecurity() {
        return this;
    }

    /**
     * Does nothing.
     */
    @Override
    public InProcessChannelBuilder usePlaintext() {
        return this;
    }

    /**
     * Does nothing.
     */
    @Override
    public InProcessChannelBuilder keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
        return this;
    }

    /**
     * Does nothing.
     */
    @Override
    public InProcessChannelBuilder keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
        return this;
    }

    /**
     * Does nothing.
     */
    @Override
    public InProcessChannelBuilder keepAliveWithoutCalls(boolean enable) {
        return this;
    }

    /**
     * Provides a custom scheduled executor service.
     * 提供自定义的调度线程池
     * <p>It's an optional parameter. If the user has not provided a scheduled executor service when
     * the channel is built, the builder will use a static cached thread pool.
     * 可选的参数，如果用户没有提供则会使用默认的
     *
     * @return this
     * @since 1.11.0
     */
    public InProcessChannelBuilder scheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = checkNotNull(scheduledExecutorService, "scheduledExecutorService");
        return this;
    }

    /**
     * Sets the maximum size of metadata allowed to be received. {@code Integer.MAX_VALUE} disables
     * the enforcement. Defaults to no limit ({@code Integer.MAX_VALUE}).
     * 设置允许接收的最大的 metadata 的大小，Integer.MAX_VALUE 表示强制禁止，默认没有限制
     *
     * <p>There is potential for performance penalty when this setting is enabled, as the Metadata
     * must actually be serialized. Since the current implementation of Metadata pre-serializes, it's
     * currently negligible. But Metadata is free to change its implementation.
     * 设置为开启的时候有潜在的性能限制，Metadata 必须序列化，因为现有的实现都是提前序列化的，所以目前可忽略不计，
     * 但是元数据可以自由更改其实现
     *
     * @param bytes the maximum size of received metadata
     * @return this
     * @throws IllegalArgumentException if bytes is non-positive
     * @since 1.17.0
     */
    @Override
    public InProcessChannelBuilder maxInboundMetadataSize(int bytes) {
        checkArgument(bytes > 0, "maxInboundMetadataSize must be > 0");
        this.maxInboundMetadataSize = bytes;
        return this;
    }

    /**
     * Sets whether to include the cause with the status that is propagated
     * forward from the InProcessTransport. This was added to make debugging failing
     * tests easier by showing the cause of the status.
     * 设置是否将原因包含在状态中使用 InProcessTransport 传播，用于调试失败的测试用例
     *
     * <p>By default, this is set to false.
     * A default value of false maintains consistency with other transports which strip causal
     * information from the status to avoid leaking information to untrusted clients, and
     * to avoid sharing language-specific information with the client.
     * For the in-process implementation, this is not a concern.
     *
     * @param enable whether to include cause in status
     * @return this
     */
    public InProcessChannelBuilder propagateCauseWithStatus(boolean enable) {
        this.transportIncludeStatusCause = enable;
        return this;
    }

    @Override
    @Internal
    protected ClientTransportFactory buildTransportFactory() {
        return new InProcessClientTransportFactory(name,
                scheduledExecutorService,
                maxInboundMetadataSize,
                transportIncludeStatusCause);
    }

    /**
     * Creates InProcess transports. Exposed for internal use, as it should be private.
     * 创建进程内的 Transport，仅用于内部使用，应当是私有的
     */
    static final class InProcessClientTransportFactory implements ClientTransportFactory {
        private final String name;
        private final ScheduledExecutorService timerService;
        private final boolean useSharedTimer;
        private final int maxInboundMetadataSize;
        private boolean closed;
        private final boolean includeCauseWithStatus;

        private InProcessClientTransportFactory(String name,
                                                @Nullable ScheduledExecutorService scheduledExecutorService,
                                                int maxInboundMetadataSize,
                                                boolean includeCauseWithStatus) {
            this.name = name;
            useSharedTimer = scheduledExecutorService == null;
            timerService = useSharedTimer ? SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE) : scheduledExecutorService;
            this.maxInboundMetadataSize = maxInboundMetadataSize;
            this.includeCauseWithStatus = includeCauseWithStatus;
        }

        @Override
        public ConnectionClientTransport newClientTransport(SocketAddress addr,
                                                            ClientTransportOptions options,
                                                            ChannelLogger channelLogger) {
            if (closed) {
                throw new IllegalStateException("The transport factory is closed.");
            }
            // TODO(carl-mastrangelo): Pass channelLogger in.
            return new InProcessTransport(name,
                    maxInboundMetadataSize,
                    options.getAuthority(),
                    options.getUserAgent(),
                    options.getEagAttributes(),
                    includeCauseWithStatus);
        }

        @Override
        public ScheduledExecutorService getScheduledExecutorService() {
            return timerService;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (useSharedTimer) {
                SharedResourceHolder.release(GrpcUtil.TIMER_SERVICE, timerService);
            }
        }
    }
}
