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

import io.grpc.Compressor;

import java.io.InputStream;

/**
 * A single stream of communication between two end-points within a transport.
 * 传输内部两个端点之间的单一通信流
 *
 * <p>An implementation doesn't need to be thread-safe. All methods are expected to execute quickly.
 * 实现不要求是线程安全的，所有的方法都需要快速执行
 */
public interface Stream {
    /**
     * Requests up to the given number of messages from the call to be delivered via
     * {@link StreamListener#messagesAvailable(StreamListener.MessageProducer)}. No additional
     * messages will be delivered.  If the stream has a {@code start()} method, it must be called
     * before requesting messages.
     * 通过 StreamListener#messagesAvailable 从调用中请求最多给定数量的消息，除此之外不会有更多的信息发送，
     * 如果 stream 有 start 方法，则必须先于发送消息前调用
     *
     * @param numMessages the requested number of messages to be delivered to the listener.
     *                    要传递给监听器的请求消息数量
     */
    void request(int numMessages);

    /**
     * Writes a message payload to the remote end-point. The bytes from the stream are immediately
     * read by the Transport. Where possible callers should use streams that are
     * {@link io.grpc.KnownLength} to improve efficiency. This method will always return immediately
     * and will not wait for the write to complete.  If the stream has a {@code start()} method, it
     * must be called before writing any messages.
     * 将消息写入远程端点，stream 中的字节会立即被 Transport 读取，在可能的情况下，调用者应该使用 grpc的流，
     * 知道如何提高效率，这个方法会立即返回，而不会等待写入完成，如果 stream 有 start 方法，必须在写入消息前
     * 调用
     *
     * <p>It is recommended that the caller consult {@link #isReady()} before calling this method to
     * avoid excessive buffering in the transport.
     * 建议在调用写入之前先调用 isReady 方法，避免使用过多的缓冲
     *
     * <p>This method takes ownership of the InputStream, and implementations are responsible for
     * calling {@link InputStream#close}.
     * 该方法拥有 stream 的所有权，实现应当调用 InputStream#close 方法
     *
     * @param message stream containing the serialized message to be sent
     *                包含序列化消息的要被发送的流
     */
    void writeMessage(InputStream message);

    /**
     * Flushes any internally buffered messages to the remote end-point.
     * 将所有的缓冲消息都发送给远程端点
     */
    void flush();

    /**
     * If {@code true}, indicates that the transport is capable of sending additional messages without
     * requiring excessive buffering internally. Otherwise, {@link StreamListener#onReady()} will be
     * called when it turns {@code true}.
     * 如果是 true，则意味着可以发送消息，而不需要额外的缓冲，除此之外，当变为 true 时StreamListener#onReady()
     * 会被调用
     *
     * <p>This is just a suggestion and the application is free to ignore it, however doing so may
     * result in excessive buffering within the transport.
     * 这只是一个建议，应用程序可以忽略它，但是这样做可能会导致传输中的过度缓冲
     */
    boolean isReady();

    /**
     * Provides a hint that directExecutor is being used by the listener for callbacks to the
     * application. No action is required. There is no requirement that this method actually matches
     * the executor used.
     * 提供一个提示，说明 directExecutor 被监听器使用，用于回调，不需要此方法实际匹配所使用的执行器
     */
    void optimizeForDirectExecutor();

    /**
     * Sets the compressor on the framer.
     * 设置压缩器
     *
     * @param compressor the compressor to use
     */
    void setCompressor(Compressor compressor);

    /**
     * Enables per-message compression, if an encoding type has been negotiated.  If no message
     * encoding has been negotiated, this is a no-op. By default per-message compression is enabled,
     * but may not have any effect if compression is not enabled on the call.
     * 如果已经协定了编码类型，则开启每个消息压缩，如果没有协定，则不执行；默认情况下每个消息压缩是开启的，但是如果
     * 没有在调用上启用压缩，则不会生效
     */
    void setMessageCompression(boolean enable);
}
