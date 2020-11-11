/*
 * Copyright 2019 The gRPC Authors
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

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Queue;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Listener for when deframing on the application thread, which calls the real listener on the
 * transport thread. May only be called on the application thread.
 * 解帧监听器，会在 Transport 的线程中调用真正的监听器，只能被应用程序的线程调用
 *
 * <p>Does not call the delegate's {@code messagesAvailable()}. It's expected that
 * {@code messageReadQueuePoll()} is called on the application thread from within a message producer
 * managed elsewhere.
 * 不会调用代理的  messagesAvailable()，期望从其他消息生成管理器内部调用 messageReadQueuePoll()
 */
final class ApplicationThreadDeframerListener implements MessageDeframer.Listener {

    public interface TransportExecutor {
        void runOnTransportThread(Runnable r);
    }

    private final TransportExecutor transportExecutor;

    private final MessageDeframer.Listener storedListener;

    /**
     * Queue for messages returned by the deframer when deframing in the application thread.
     * 应用程序线程内解帧器解帧返回的消息队列
     */
    private final Queue<InputStream> messageReadQueue = new ArrayDeque<>();

    public ApplicationThreadDeframerListener(MessageDeframer.Listener listener,
                                             TransportExecutor transportExecutor) {
        this.storedListener = checkNotNull(listener, "listener");
        this.transportExecutor = checkNotNull(transportExecutor, "transportExecutor");
    }

    /**
     * 当给定数量的字节被 deframer 从输入源中读取的时候调用， 通常表明有可以读取更多的数据
     */
    @Override
    public void bytesRead(final int numBytes) {
        transportExecutor.runOnTransportThread(new Runnable() {
            @Override
            public void run() {
                storedListener.bytesRead(numBytes);
            }
        });
    }

    /**
     * 调用以传递下一条完整消息
     */
    @Override
    public void messagesAvailable(StreamListener.MessageProducer producer) {
        InputStream message;
        while ((message = producer.next()) != null) {
            messageReadQueue.add(message);
        }
    }

    /**
     * 关闭解帧器
     */
    @Override
    public void deframerClosed(final boolean hasPartialMessage) {
        transportExecutor.runOnTransportThread(new Runnable() {
            @Override
            public void run() {
                storedListener.deframerClosed(hasPartialMessage);
            }
        });
    }

    /**
     * 解帧失败
     */
    @Override
    public void deframeFailed(final Throwable cause) {
        transportExecutor.runOnTransportThread(new Runnable() {
            @Override
            public void run() {
                storedListener.deframeFailed(cause);
            }
        });
    }

    /**
     * 从队列中获取流
     */
    public InputStream messageReadQueuePoll() {
        return messageReadQueue.poll();
    }
}
