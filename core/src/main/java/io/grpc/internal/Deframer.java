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

import io.grpc.Decompressor;

/**
 * Interface for deframing gRPC messages.
 * gRPC 消息解帧器
 */
public interface Deframer {

    /**
     * 设置最大可接收消息大小
     *
     * @param messageSize 消息大小
     */
    void setMaxInboundMessageSize(int messageSize);

    /**
     * Sets the decompressor available to use. The message encoding for the stream comes later in
     * time, and thus will not be available at the time of construction. This should only be set once,
     * since the compression codec cannot change after the headers have been sent.
     * 设置可用的解压缩器，流的编码消息将会在稍后出现，所以构造时不可用；仅应当被设置一次，当发送了 header 之后压缩器不能再
     * 改变
     *
     * @param decompressor the decompressing wrapper.
     *                     解压缩包装器
     */
    void setDecompressor(Decompressor decompressor);

    /**
     * Sets the decompressor used for full-stream decompression. Full-stream decompression disables
     * any per-message decompressor set by {@link #setDecompressor}.
     * 设置用于整个流的解压缩器，解压缩器在通过 setDecompressor 设置前禁止解压任何一个流
     *
     * @param fullStreamDecompressor the decompressing wrapper
     *                               解压缩包装器
     */
    void setFullStreamDecompressor(GzipInflatingBuffer fullStreamDecompressor);

    /**
     * Requests up to the given number of messages from the call. No additional messages will be
     * delivered.
     * 发送给定数量的请求消息，不会发送更多的消息
     *
     * <p>If {@link #close()} has been called, this method will have no effect.
     * 如果调用了 close，则这个方法没有作用
     *
     * @param numMessages the requested number of messages to be delivered to the listener.
     *                    发送给监听器的消息数量
     */
    void request(int numMessages);

    /**
     * Adds the given data to this deframer and attempts delivery to the listener.
     * 将给定的数据添加到解帧器中，尝试发送给监听器
     *
     * @param data the raw data read from the remote endpoint. Must be non-null.
     *             从远程端点读取的数据，必须不能是 null
     */
    void deframe(ReadableBuffer data);

    /**
     * Close when any messages currently queued have been requested and delivered.
     * 当当前队列中的消息已经请求并传递时关闭
     */
    void closeWhenComplete();

    /**
     * Closes this deframer and frees any resources. After this method is called, additional calls
     * will have no effect.
     * 关闭这个解帧器，并释放资源，调用了这个方法后其他的调用不会生效
     */
    void close();
}
