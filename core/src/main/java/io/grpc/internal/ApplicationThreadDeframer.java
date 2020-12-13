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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Decompressor;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Sits between {@link AbstractStream.TransportState} and {@link MessageDeframer} to deframe in the
 * client thread. Calls from the transport to the deframer are wrapped into an {@link
 * InitializingMessageProducer} and given back to the transport, where they will be run on the
 * client thread. Calls from the deframer back to the transport use {@link
 * TransportExecutor#runOnTransportThread} to run on the transport thread.
 * <p>
 * 在 TransportState 和 MessageDeframer 之间，以便在客户端线程中解码；从 Transport 向 deframer 的调用被包装
 * 成 InitializingMessageProducer，并返回给 Transport，会在客户端线程中执行，从 dframer 调用 Transport 使用
 * TransportExecutor#runOnTransportThread 在 Transport 线程中执行
 */
public class ApplicationThreadDeframer implements Deframer {

    interface TransportExecutor extends ApplicationThreadDeframerListener.TransportExecutor {
    }

    private final MessageDeframer.Listener storedListener;
    private final ApplicationThreadDeframerListener appListener;
    private final MessageDeframer deframer;

    ApplicationThreadDeframer(MessageDeframer.Listener listener,
                              TransportExecutor transportExecutor,
                              MessageDeframer deframer) {
        this.storedListener = new SquelchLateMessagesAvailableDeframerListener(checkNotNull(listener, "listener"));
        this.appListener = new ApplicationThreadDeframerListener(storedListener, transportExecutor);
        deframer.setListener(appListener);
        this.deframer = deframer;
    }

    @Override
    public void setMaxInboundMessageSize(int messageSize) {
        deframer.setMaxInboundMessageSize(messageSize);
    }

    @Override
    public void setDecompressor(Decompressor decompressor) {
        deframer.setDecompressor(decompressor);
    }

    @Override
    public void setFullStreamDecompressor(GzipInflatingBuffer fullStreamDecompressor) {
        deframer.setFullStreamDecompressor(fullStreamDecompressor);
    }

    /**
     * 发送给定数量的请求消息，不会发送更多的消息
     */
    @Override
    public void request(final int numMessages) {
        // 提交初始化消息生产任务
        storedListener.messagesAvailable(new InitializingMessageProducer(new Runnable() {
            @Override
            public void run() {
                if (deframer.isClosed()) {
                    return;
                }
                try {
                    // 发送指定数量的消息
                    deframer.request(numMessages);
                } catch (Throwable t) {
                    appListener.deframeFailed(t);
                    deframer.close(); // unrecoverable state
                }
            }
        }));
    }

    /**
     * 将给定的数据添加到解帧器中，尝试发送给监听器
     */
    @Override
    public void deframe(final ReadableBuffer data) {
        storedListener.messagesAvailable(new CloseableInitializingMessageProducer(new Runnable() {
            @Override
            public void run() {
                try {
                    // 解帧
                    deframer.deframe(data);
                } catch (Throwable t) {
                    appListener.deframeFailed(t);
                    deframer.close(); // unrecoverable state
                }
            }
        }, new Closeable() {
            @Override
            public void close() {
                data.close();
            }
        }));
    }

    /**
     * 当消息完成时关闭
     */
    @Override
    public void closeWhenComplete() {
        storedListener.messagesAvailable(new InitializingMessageProducer(new Runnable() {
            @Override
            public void run() {
                deframer.closeWhenComplete();
            }
        }));
    }

    /**
     * 关闭消息
     */
    @Override
    public void close() {
        deframer.stopDelivery();
        storedListener.messagesAvailable(new InitializingMessageProducer(new Runnable() {
            @Override
            public void run() {
                deframer.close();
            }
        }));
    }

    @VisibleForTesting
    MessageDeframer.Listener getAppListener() {
        return appListener;
    }

    /**
     * gRPC 消息解码生产者
     */
    private class InitializingMessageProducer implements StreamListener.MessageProducer {

        private final Runnable runnable;
        private boolean initialized = false;

        private InitializingMessageProducer(Runnable runnable) {
            this.runnable = runnable;
        }

        /**
         * 初始化，会执行任务
         */
        private void initialize() {
            if (!initialized) {
                runnable.run();
                initialized = true;
            }
        }

        @Nullable
        @Override
        public InputStream next() {
            // 初始化
            initialize();
            // 从队列中获取流
            return appListener.messageReadQueuePoll();
        }
    }

    /**
     * 可关闭的消息生产者
     */
    private class CloseableInitializingMessageProducer extends InitializingMessageProducer implements Closeable {
        private final Closeable closeable;

        public CloseableInitializingMessageProducer(Runnable runnable, Closeable closeable) {
            super(runnable);
            this.closeable = closeable;
        }

        @Override
        public void close() throws IOException {
            closeable.close();
        }
    }
}
