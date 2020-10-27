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

package io.grpc.internal;

import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.IOException;
import java.net.SocketAddress;

/**
 * An object that accepts new incoming connections. This would commonly encapsulate a bound socket
 * that {@code accept()}s new connections.
 * <p>
 * 接受新的连接的对象，通常封装一个绑定的 Socket，以绑定 accept() 的新连接
 */
@ThreadSafe
public interface InternalServer {
    /**
     * Starts transport. Implementations must not call {@code listener} until after {@code start()}
     * returns. The method only returns after it has done the equivalent of bind()ing, so it will be
     * able to service any connections created after returning.
     * <p>
     * 开始一个 Transport，实现类在调用了 start() 之后不能再调用 Listener，只有当完成绑定后才会返回，所以当返回后
     * 可以支持服务连接的建立
     *
     * @param listener non-{@code null} listener of server events
     *                 非空的 server 事件监听器
     * @throws IOException if unable to bind
     */
    void start(ServerListener listener) throws IOException;

    /**
     * Initiates an orderly shutdown of the server. Existing transports continue, but new transports
     * will not be created (once {@link ServerListener#serverShutdown()} callback is called). This
     * method may only be called once.  Blocks until the listening socket(s) have been closed.  If
     * interrupted, this method will not wait for the close to complete, but it will happen
     * asynchronously.
     * <p>
     * 开始顺序关闭 server，存在的 Transport 会继续，但是一旦调用了 ServerListener#serverShutdown() ，
     * 新的 Transport 不能创建，这个方法只会被调用一次，阻塞直到监听的 Socket 被关闭，如果被打断，这个方法不会
     * 等待完成，但是会继续异步执行
     */
    void shutdown();

    /**
     * Returns the listening socket address.  May change after {@link start(ServerListener)} is
     * called.
     * 返回监听 Socket 的地址，当调用 start 方法后可能会变化
     */
    SocketAddress getListenSocketAddress();

    /**
     * Returns the listen socket stats of this server. May return {@code null}.
     * 返回 server 监听的 Socket 的状态，可能会返回 null
     */
    @Nullable
    InternalInstrumented<SocketStats> getListenSocketStats();
}
