/*
 * Copyright 2017 The gRPC Authors
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
 * Interface for framing gRPC messages.
 * 用于 gRPC 消息帧的接口
 */
public interface Framer {
    /**
     * Writes out a payload message.
     * 写入消息负载
     *
     * @param message contains the message to be written out. It will be completely consumed.
     *                包含要写入的消息，将会被完全消耗掉
     */
    void writePayload(InputStream message);

    /**
     * Flush the buffered payload.
     * 清空缓冲的负载
     */
    void flush();

    /**
     * Returns whether the framer is closed.
     * 返回 framer 是否被关闭
     */
    boolean isClosed();

    /**
     * Closes, with flush.
     * 关闭并清空
     */
    void close();

    /**
     * Closes, without flush.
     * 关闭但是不清空
     */
    void dispose();

    /**
     * Enable or disable compression.
     * 启用或者禁用压缩
     */
    Framer setMessageCompression(boolean enable);

    /**
     * Set the compressor used for compression.
     * 设置用于压缩的压缩器
     */
    Framer setCompressor(Compressor compressor);

    /**
     * Set a size limit for each outbound message.
     * 设置用于每个出站消息的的大小限制
     */
    void setMaxOutboundMessageSize(int maxSize);
}
