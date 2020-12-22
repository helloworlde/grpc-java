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

package io.grpc;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Listens to events on a stream to collect metrics.
 * 监听流事件用于统计
 *
 * <p>DO NOT MOCK: Use TestStreamTracer. Mocks are not thread-safe
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2861")
@ThreadSafe
public abstract class StreamTracer {
    /**
     * Stream is closed.  This will be called exactly once.
     * 流关闭，只调用一次
     */
    public void streamClosed(Status status) {
    }

    /**
     * An outbound message has been passed to the stream.  This is called as soon as the stream knows
     * about the message, but doesn't have further guarantee such as whether the message is serialized
     * or not.
     * 传送给流的出站消息，流知道消息后立即调用，但是没有进一步的保证，例如消息是否已序列化
     *
     * @param seqNo the sequential number of the message within the stream, starting from 0.  It can
     *              be used to correlate with {@link #outboundMessageSent} for the same message.
     *              这个流发送消息的序号，初始是0，可以用于 outboundMessageSent 发送的消息相关联
     */
    public void outboundMessage(int seqNo) {
    }

    /**
     * An inbound message has been received by the stream.  This is called as soon as the stream knows
     * about the message, but doesn't have further guarantee such as whether the message is
     * deserialized or not.
     * 流接收到的入栈消息，只要流接收到消息就会被调用，但是并不确定消息是否被反序列化
     *
     * @param seqNo the sequential number of the message within the stream, starting from 0.  It can
     *              be used to correlate with {@link #inboundMessageRead} for the same message.
     *              这个流接收的消息的序号，从 0 开始，用于关联 inboundMessageRead 接收到的同一条消息
     */
    public void inboundMessage(int seqNo) {
    }

    /**
     * An outbound message has been serialized and sent to the transport.
     * 将已经被序列化的出站消息发送给 Transport
     *
     * @param seqNo                    the sequential number of the message within the stream, starting from 0.  It can
     *                                 be used to correlate with {@link #outboundMessage(int)} for the same message.
     *                                 这个流发送消息的序号，从 0 开始，用于关联 outboundMessage 发送的消息
     * @param optionalWireSize         the wire size of the message. -1 if unknown
     *                                 消息的线号，如果未知则是 -1
     * @param optionalUncompressedSize the uncompressed serialized size of the message. -1 if unknown
     *                                 未压缩序列化的消息大小，如果未知则是 -1
     */
    public void outboundMessageSent(int seqNo, long optionalWireSize, long optionalUncompressedSize) {
    }

    /**
     * An inbound message has been fully read from the transport.
     * 从 Transport 完全读取的入栈消息
     *
     * @param seqNo                    the sequential number of the message within the stream, starting from 0.  It can
     *                                 be used to correlate with {@link #inboundMessage(int)} for the same message.
     *                                 这个流接收到的消息序号，
     * @param optionalWireSize         the wire size of the message. -1 if unknown
     *                                 消息的线号，如果未知则是 -1
     * @param optionalUncompressedSize the uncompressed serialized size of the message. -1 if unknown
     *                                 未解压的序列化的消息的大小，如果未知则是 -1
     */
    public void inboundMessageRead(int seqNo, long optionalWireSize, long optionalUncompressedSize) {
    }

    /**
     * The wire size of some outbound data is revealed. This can only used to record the accumulative
     * outbound wire size. There is no guarantee wrt timing or granularity of this method.
     * <p>
     * 出站数据的线径，仅用于记录累计的出站线径大小，无法保证此方法的时机或粒度
     */
    public void outboundWireSize(long bytes) {
    }

    /**
     * The uncompressed size of some outbound data is revealed. This can only used to record the
     * accumulative outbound uncompressed size. There is no guarantee wrt timing or granularity of
     * this method.
     * 未压缩的出站数据的线径，仅用于记录累计的未压缩出站线径大小，无法保证此方法的时机或粒度
     */
    public void outboundUncompressedSize(long bytes) {
    }

    /**
     * The wire size of some inbound data is revealed. This can only be used to record the
     * accumulative received wire size. There is no guarantee wrt timing or granularity of this
     * method.
     * 入站数据的线径，仅用于记录累计的入站线径大小，无法保证此方法的时机或粒度
     */
    public void inboundWireSize(long bytes) {
    }

    /**
     * The uncompressed size of some inbound data is revealed. This can only used to record the
     * accumulative received uncompressed size. There is no guarantee wrt timing or granularity of
     * this method.
     * 未压缩的入站数据的线径，仅用于记录累计的未压缩入站线径大小，无法保证此方法的时机或粒度
     */
    public void inboundUncompressedSize(long bytes) {
    }
}
