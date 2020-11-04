/*
 * Copyright 2018 The gRPC Authors
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

import com.google.common.base.MoreObjects;
import io.grpc.Attributes;
import io.grpc.Compressor;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.Status;

import java.io.InputStream;

abstract class ForwardingClientStream implements ClientStream {
    /**
     * 被代理的 ClientStream
     */
    protected abstract ClientStream delegate();

    /**
     * 发送指定数量的消息
     *
     * @param numMessages the requested number of messages to be delivered to the listener.
     *                    要传递给监听器的消息的数量
     */
    @Override
    public void request(int numMessages) {
        delegate().request(numMessages);
    }

    /**
     * 写入消息
     *
     * @param message stream containing the serialized message to be sent
     *                包含要发送的序列化后的消息的流
     */
    @Override
    public void writeMessage(InputStream message) {
        delegate().writeMessage(message);
    }

    /**
     * 清空流的缓冲区
     */
    @Override
    public void flush() {
        delegate().flush();
    }

    /**
     * 返回流是否 ready
     */
    @Override
    public boolean isReady() {
        return delegate().isReady();
    }

    @Override
    public void optimizeForDirectExecutor() {
        delegate().optimizeForDirectExecutor();
    }

    @Override
    public void setCompressor(Compressor compressor) {
        delegate().setCompressor(compressor);
    }

    @Override
    public void setMessageCompression(boolean enable) {
        delegate().setMessageCompression(enable);
    }

    /**
     * 取消流
     *
     * @param reason must be non-OK 取消的状态，不能是 ok
     */
    @Override
    public void cancel(Status reason) {
        delegate().cancel(reason);
    }

    /**
     * 半关闭流
     */
    @Override
    public void halfClose() {
        delegate().halfClose();
    }

    /**
     * 设置服务名称
     */
    @Override
    public void setAuthority(String authority) {
        delegate().setAuthority(authority);
    }

    @Override
    public void setFullStreamDecompression(boolean fullStreamDecompression) {
        delegate().setFullStreamDecompression(fullStreamDecompression);
    }

    @Override
    public void setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
        delegate().setDecompressorRegistry(decompressorRegistry);
    }

    /**
     * 开始流
     *
     * @param listener non-{@code null} listener of stream events
     *                 流事件监听器
     */
    @Override
    public void start(ClientStreamListener listener) {
        delegate().start(listener);
    }

    @Override
    public void setMaxInboundMessageSize(int maxSize) {
        delegate().setMaxInboundMessageSize(maxSize);
    }

    @Override
    public void setMaxOutboundMessageSize(int maxSize) {
        delegate().setMaxOutboundMessageSize(maxSize);
    }

    /**
     * 设置过期时间
     */
    @Override
    public void setDeadline(Deadline deadline) {
        delegate().setDeadline(deadline);
    }

    @Override
    public Attributes getAttributes() {
        return delegate().getAttributes();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
    }

    @Override
    public void appendTimeoutInsight(InsightBuilder insight) {
        delegate().appendTimeoutInsight(insight);
    }
}
