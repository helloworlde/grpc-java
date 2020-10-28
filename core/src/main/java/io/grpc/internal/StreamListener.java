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

import javax.annotation.Nullable;
import java.io.InputStream;

/**
 * An observer of {@link Stream} events. It is guaranteed to only have one concurrent callback at a
 * time.
 * 流事件监听器，同一时间只能有一个并发的回调
 */
public interface StreamListener {
    /**
     * Called upon receiving a message from the remote end-point.
     * 当从远程端点接收到消息时调用
     *
     * <p>Implementations must eventually drain the provided {@code producer} {@link MessageProducer}
     * completely by invoking {@link MessageProducer#next()} to obtain deframed messages until the
     * producer returns null.
     * 实现必须通过调用 MessageProducer#next() 读取所有的消息，以获取解码后的消息，直到 producer 返回 null
     *
     * <p>This method should return quickly, as the same thread may be used to process other streams.
     * 这个方法应当快速返回，同一个线程可能会用于处理其他的流
     *
     * @param producer supplier of deframed messages.
     *                 用于解码消息的提供者
     */
    void messagesAvailable(MessageProducer producer);

    /**
     * This indicates that the transport is now capable of sending additional messages
     * without requiring excessive buffering internally. This event is
     * just a suggestion and the application is free to ignore it, however doing so may
     * result in excessive buffering within the transport.
     * <p>
     * 这个方法表示 Transport 已经准备好发送附加的消息，而不需要额外的内部缓冲，这个事件仅仅是一个建议，应用可以忽略，
     * 但是这样做可能会有过多额外的缓冲
     */
    void onReady();

    /**
     * A producer for deframed gRPC messages.
     * gRPC 消息解码生产者
     */
    interface MessageProducer {
        /**
         * Returns the next gRPC message, if the data has been received by the deframer and the
         * application has requested another message.
         * 返回下一个 gRPC 消息，如果数据已经被解码器接收到了，那么获取下一个消息
         *
         * <p>The provided {@code message} {@link InputStream} must be closed by the listener.
         * 提供的消息流必须由监听器关闭
         *
         * <p>This is intended to be used similar to an iterator, invoking {@code next()} to obtain
         * messages until the producer returns null, at which point the producer may be discarded.
         * 这个代替了迭代器，通过 next 获取消息，直到生产者返回 null，这时生产者可能会被丢弃
         */
        @Nullable
        public InputStream next();
    }
}
