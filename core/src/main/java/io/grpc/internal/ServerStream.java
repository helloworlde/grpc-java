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
import io.grpc.Decompressor;
import io.grpc.Metadata;
import io.grpc.Status;

import javax.annotation.Nullable;

/**
 * Extension of {@link Stream} to support server-side termination semantics.
 * Stream 的扩展，用于支持服务端终止语义
 *
 * <p>An implementation doesn't need to be thread-safe. All methods are expected to execute quickly.
 * 实现不需要保证线程安全，
 */
public interface ServerStream extends Stream {

    /**
     * Writes custom metadata as headers on the response stream sent to the client. This method may
     * only be called once and cannot be called after calls to {@link Stream#writeMessage}
     * or {@link #close}.
     * 将自定义的 Metadata 作为 Header 写入到发送给 Client 的响应中，这个方法只能调用一次，当调用了 Stream#writeMessage
     * 或者 close 方法后不能再调用
     *
     * @param headers to send to client.
     *                发送给客户端的 Header
     */
    void writeHeaders(Metadata headers);

    /**
     * Closes the stream for both reading and writing. A status code of
     * {@link io.grpc.Status.Code#OK} implies normal termination of the
     * stream. Any other value implies abnormal termination.
     * 关闭读和写的流，OK 状态表示正常终止，其他的状态都是非正常的
     *
     * <p>Attempts to read from or write to the stream after closing
     * should be ignored by implementations, and should not throw
     * exceptions.
     * 在流关闭后尝试写入或者读取应当被忽略，不应该抛出异常
     *
     * @param status   details of the closure
     *                 关闭的状态
     * @param trailers an additional block of metadata to pass to the client on stream closure.
     *                 在关闭时发送给客户端的附加的元数据
     */
    void close(Status status, Metadata trailers);


    /**
     * Tears down the stream, typically in the event of a timeout. This method may be called multiple
     * times and from any thread.
     * 关闭流，通常是超时的情况下，这个方法可能在任意线程内被多次调用
     */
    void cancel(Status status);

    /**
     * Sets the decompressor on the deframer. If the transport does not support compression, this may
     * do nothing.
     * 设置用于解析消息的解压缩器，如果 Transport 不支持压缩，则不会有作用
     *
     * @param decompressor the decompressor to use.
     */
    void setDecompressor(Decompressor decompressor);

    /**
     * Attributes describing stream.  This is inherited from the transport attributes, and used
     * as the basis of {@link io.grpc.ServerCall#getAttributes}.
     * 描述流的属性，从 Transport 的属性中传递的，作为 ServerCall#getAttributes 的基础属性
     *
     * @return Attributes container
     */
    Attributes getAttributes();

    /**
     * Gets the authority this stream is addressed to.
     * 获取流指向的服务名称
     *
     * @return the authority string. {@code null} if not available.
     */
    @Nullable
    String getAuthority();

    /**
     * Sets the server stream listener.
     * 设置服务端流监听器
     */
    void setListener(ServerStreamListener serverStreamListener);

    /**
     * The context for recording stats and traces for this stream.
     * 记录流状态和跟踪的上下文
     */
    StatsTraceContext statsTraceContext();

    /**
     * The HTTP/2 stream id, or {@code -1} if not supported.
     * HTTP2 的流 ID，如果不支持则是 -1
     */
    int streamId();
}
