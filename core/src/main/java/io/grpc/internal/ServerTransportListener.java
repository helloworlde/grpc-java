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

import io.grpc.Attributes;
import io.grpc.Metadata;

/**
 * A observer of a server-side transport for stream creation events. Notifications must occur from
 * the transport thread.
 * Server 端的用于监听流创建事件的监听器，必须通过 Transport 的线程池通知
 */
public interface ServerTransportListener {
    /**
     * Called when a new stream was created by the remote client.
     * 当远程的客户端创建新的流时被调用
     *
     * @param stream  the newly created stream.
     *                新创建的流
     * @param method  the fully qualified method name being called on the server.
     *                被调用的方法限定名
     * @param headers containing metadata for the call.
     *                调用的元信息
     */
    void streamCreated(ServerStream stream, String method, Metadata headers);

    /**
     * The transport has finished all handshakes and is ready to process streams.
     * 当 Transport 完成所有的握手准备接收流量时调用
     *
     * @param attributes transport attributes
     *                   Transport 的属性
     * @return the effective transport attributes that is used as the basis of call attributes
     * 用于调用属性的有效的 Transport 的属性
     */
    Attributes transportReady(Attributes attributes);

    /**
     * The transport completed shutting down. All resources have been released.
     * 当 Transport 完成关闭时调用，所有的资源都被释放
     */
    void transportTerminated();
}
