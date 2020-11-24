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

/**
 * A listener to a server for transport creation events. The listener need not be thread-safe, so
 * notifications must be properly synchronized externally.
 * <p>
 * ServerTransport 事件监听器，监听器没有保证线程安全，所以停止必须在外部正确同步
 */
public interface  ServerListener {

    /**
     * Called upon the establishment of a new client connection.
     * 要求建立新的客户端连接
     *
     * @param transport the new transport to be observed.
     *                  监听的服务端的 Transport
     * @return a listener for stream creation events on the transport.
     * 这个 Transport 上的流创建事件的监听器
     */
    ServerTransportListener transportCreated(ServerTransport transport);

    /**
     * The server is shutting down. No new transports will be processed, but existing transports may
     * continue. Shutdown is only caused by a call to {@link InternalServer#shutdown()}. All
     * resources have been released.
     * <p>
     * 服务关闭，不会有新的 Transport 处理，但是已经存在的 Transport 会继续，只有通过 InternalServer#shutdown()
     * 才会执行关闭，所有的资源都会被释放
     */
    void serverShutdown();
}
