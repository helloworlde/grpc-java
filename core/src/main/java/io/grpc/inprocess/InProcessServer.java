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

import com.google.common.base.MoreObjects;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerTransportListener;

import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.base.Preconditions.checkNotNull;

@ThreadSafe
final class InProcessServer implements InternalServer {

    /**
     * 监听的 Server 集合
     */
    private static final ConcurrentMap<String, InProcessServer> registry = new ConcurrentHashMap<>();

    /**
     * 通过名称查找 Server
     */
    static InProcessServer findServer(String name) {
        return registry.get(name);
    }

    private final String name;
    private final int maxInboundMetadataSize;
    private final List<ServerStreamTracer.Factory> streamTracerFactories;
    private ServerListener listener;
    private boolean shutdown;
    /**
     * Defaults to be a SharedResourcePool.
     */
    private final ObjectPool<ScheduledExecutorService> schedulerPool;
    /**
     * Only used to make sure the scheduler has at least one reference. Since child transports can
     * outlive this server, they must get their own reference.
     */
    private ScheduledExecutorService scheduler;

    InProcessServer(InProcessServerBuilder builder,
                    List<? extends ServerStreamTracer.Factory> streamTracerFactories) {
        this.name = builder.name;
        this.schedulerPool = builder.schedulerPool;
        this.maxInboundMetadataSize = builder.maxInboundMetadataSize;
        this.streamTracerFactories = Collections.unmodifiableList(checkNotNull(streamTracerFactories, "streamTracerFactories"));
    }

    /**
     * 开始 Server Transport
     *
     * @param serverListener 监听器
     */
    @Override
    public void start(ServerListener serverListener) throws IOException {
        this.listener = serverListener;
        this.scheduler = schedulerPool.getObject();
        // Must be last, as channels can start connecting after this point.
        // 如果没有则放到监听的集合中
        if (registry.putIfAbsent(name, this) != null) {
            throw new IOException("name already registered: " + name);
        }
    }

    /**
     * 获取监听的地址
     */
    @Override
    public SocketAddress getListenSocketAddress() {
        return new InProcessSocketAddress(name);
    }

    /**
     * 获取监听的 Socket 统计信息
     */
    @Override
    public InternalInstrumented<SocketStats> getListenSocketStats() {
        return null;
    }

    /**
     * 关闭 Server
     */
    @Override
    public void shutdown() {
        // 从集合中移除当前 Server
        if (!registry.remove(name, this)) {
            throw new AssertionError();
        }

        scheduler = schedulerPool.returnObject(scheduler);
        synchronized (this) {
            shutdown = true;
            // 关闭监听器
            listener.serverShutdown();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", name).toString();
    }

    /**
     * 注册新的 Transport
     */
    synchronized ServerTransportListener register(InProcessTransport transport) {
        if (shutdown) {
            return null;
        }
        // 建立新的连接
        return listener.transportCreated(transport);
    }

    ObjectPool<ScheduledExecutorService> getScheduledExecutorServicePool() {
        return schedulerPool;
    }

    int getMaxInboundMetadataSize() {
        return maxInboundMetadataSize;
    }

    List<ServerStreamTracer.Factory> getStreamTracerFactories() {
        return streamTracerFactories;
    }
}
