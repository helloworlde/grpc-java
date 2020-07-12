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
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.Status;

import javax.annotation.Nonnull;

/**
 * Extension of {@link Stream} to support client-side termination semantics.
 * Stream 的扩展，用于支持客户端的终止
 *
 * <p>An implementation doesn't need to be thread-safe. All methods are expected to execute quickly.
 * 实现不需要保证线程安全，所有的方法都有望快速执行
 */
public interface ClientStream extends Stream {

    /**
     * Abnormally terminates the stream. After calling this method, no further messages will be
     * sent or received, however it may still be possible to receive buffered messages for a brief
     * period until {@link ClientStreamListener#closed} is called. This method may only be called
     * after {@link #start}, but else is safe to be called at any time and multiple times and
     * from any thread.
     * 异常终止流，当调用后，不会再接收或发送消息，但是调用 ClientStreamListener#closed 之前依然可能接收缓冲的消息，
     * 这个方法只有在 start 方法调用之后才可以调用，但是可以被任意线程在任意时刻调用多次
     *
     * @param reason must be non-OK
     */
    void cancel(Status reason);

    /**
     * Closes the local side of this stream and flushes any remaining messages. After this is called,
     * no further messages may be sent on this stream, but additional messages may be received until
     * the remote end-point is closed. This method may only be called once, and only after
     * {@link #start}.
     * 从客户端关闭这个流，并刷新所有剩余的信息，当这个方法调用后，不能发送更多的消息，但是可以接受消息，直到远程的端点
     * 关闭，这个方法只能在调用 start 之后被调用一次，
     */
    void halfClose();

    /**
     * Override the default authority with {@code authority}. May only be called before {@link
     * #start}.
     * 使用 authority 覆盖默认的 authority，只能在调用 start 之前调用
     */
    void setAuthority(String authority);

    /**
     * Enables full-stream decompression, allowing the client stream to use {@link
     * GzipInflatingBuffer} to decode inbound GZIP compressed streams.
     * 开启整个流的解压，允许客户端流使用 GzipInflatingBuffer 解压传入的 GZIP 压缩流
     */
    void setFullStreamDecompression(boolean fullStreamDecompression);

    /**
     * Sets the registry to find a decompressor for the framer. May only be called before {@link
     * #start}. If the transport does not support compression, this may do nothing.
     * 设置注册表以查找framer的解压器，只有在 start 方法调用前调用，如果 transport 不支持压缩，则不做任何操作
     *
     * @param decompressorRegistry the registry of decompressors for decoding responses
     *                             解码响应的解压缩器注册表
     */
    void setDecompressorRegistry(DecompressorRegistry decompressorRegistry);

    /**
     * Starts stream. This method may only be called once.  It is safe to do latent initialization of
     * the stream up until {@link #start} is called.
     * 开始一个流，这个方法只能被调用一次，在调用 start 之前，对流进行潜在的初始化是安全的
     *
     * <p>This method should not throw any exceptions.
     * 这个方法不应该抛出异常
     *
     * @param listener non-{@code null} listener of stream events
     *                 非空的 stream 事件监听器
     */
    void start(ClientStreamListener listener);

    /**
     * Sets the max size accepted from the remote endpoint.
     * 设置接收的最大字节大小
     */
    void setMaxInboundMessageSize(int maxSize);

    /**
     * Sets the max size sent to the remote endpoint.
     * 设置发送的最大字节大小
     */
    void setMaxOutboundMessageSize(int maxSize);

    /**
     * Sets the effective deadline of the RPC.
     * 设置 RPC 的有效截止时间
     */
    void setDeadline(@Nonnull Deadline deadline);

    /**
     * Attributes that the stream holds at the current moment.  Thread-safe and can be called at any
     * time, although some attributes are there only after a certain point.
     * 当前流持有的属性，线程安全的，尽管一些属性在某个节点之后才有，依然可以随时调用
     */
    Attributes getAttributes();

    /**
     * Append information that will be included in the locally generated DEADLINE_EXCEEDED errors to
     * the given {@link InsightBuilder}, in order to tell the user about the state of the stream so
     * that they can better diagnose the cause of the error.
     * 将包含在本地生成的 DEADLINE_EXCEEDED 错误中的信息附加到给定的 InsightBuilder，告诉用户当前流的状态，以便于
     * 处理错误
     */
    void appendTimeoutInsight(InsightBuilder insight);
}
