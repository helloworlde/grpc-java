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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.Decompressor;
import io.perfmark.Link;
import io.perfmark.PerfMark;

import javax.annotation.concurrent.GuardedBy;
import java.io.InputStream;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * The stream and stream state as used by the application. Must only be called from the sending
 * application thread.
 * 应用程序使用流和流的状态，仅可以从应用程序发送的线程调用
 */
public abstract class AbstractStream implements Stream {
    /**
     * The framer to use for sending messages.
     * 用于发送消息的 framer
     */
    protected abstract Framer framer();

    /**
     * Obtain the transport state corresponding to this stream. Each stream must have its own unique
     * transport state.
     * 记录这个流对应的 Transport 的状态，每个流必须拥有自己唯一的 Transport 状态
     */
    protected abstract TransportState transportState();

    /**
     * 提供一个提示，说明 directExecutor 被监听器使用，用于回调，不需要此方法实际匹配所使用的执行器
     */
    @Override
    public void optimizeForDirectExecutor() {
        transportState().optimizeForDirectExecutor();
    }

    /**
     * 设置消息压缩
     */
    @Override
    public final void setMessageCompression(boolean enable) {
        framer().setMessageCompression(enable);
    }

    /**
     * 执行发送指定数量的消息
     *
     * @param numMessages the requested number of messages to be delivered to the listener.
     */
    @Override
    public final void request(int numMessages) {
        transportState().requestMessagesFromDeframer(numMessages);
    }

    /**
     * 调用 Transport 执行写入消息
     *
     * @param message stream containing the serialized message to be sent
     */
    @Override
    public final void writeMessage(InputStream message) {
        checkNotNull(message, "message");
        try {
            if (!framer().isClosed()) {
                // 写入消息体
                framer().writePayload(message);
            }
        } finally {
            GrpcUtil.closeQuietly(message);
        }
    }

    /**
     * 清空流
     */
    @Override
    public final void flush() {
        // 如果帧还没有关闭，则清空帧
        if (!framer().isClosed()) {
            framer().flush();
        }
    }

    /**
     * Closes the underlying framer. Should be called when the outgoing stream is gracefully closed
     * (half closure on client; closure on server).
     * 关闭底层 framer，当输出流优雅地关闭时应该调用
     * 客户端半关闭，服务端关闭
     */
    protected final void endOfMessages() {
        framer().close();
    }

    /**
     * 设置压缩器
     *
     * @param compressor the compressor to use
     *                   压缩器
     */
    @Override
    public final void setCompressor(Compressor compressor) {
        framer().setCompressor(checkNotNull(compressor, "compressor"));
    }

    /**
     * 返回流是否 ready
     */
    @Override
    public boolean isReady() {
        // 如果流已经关闭，则返回 false
        if (framer().isClosed()) {
            return false;
        }
        // 如果没有关闭，返回 Transport 的状态
        return transportState().isReady();
    }

    /**
     * Event handler to be called by the subclass when a number of bytes are being queued for sending
     * to the remote endpoint.
     * 当多个字节排队等待发送到远程端点时，子类将调用事件处理程序
     *
     * @param numBytes the number of bytes being sent.
     *                 要发送的字节数量
     */
    protected final void onSendingBytes(int numBytes) {
        transportState().onSendingBytes(numBytes);
    }

    /**
     * Stream state as used by the transport. This should only called from the transport thread
     * (except for private interactions with {@code AbstractStream}).
     * Transport 中的流状态，仅应当被 Transport 线程调用，AbstractStream 的调用除外
     */
    public abstract static class TransportState implements ApplicationThreadDeframer.TransportExecutor, MessageDeframer.Listener {
        /**
         * The default number of queued bytes for a given stream, below which
         * {@link StreamListener#onReady()} will be called.
         * 流默认的可缓冲队列的字节大小，会由 StreamListener#onReady() 使用
         */
        @VisibleForTesting
        public static final int DEFAULT_ONREADY_THRESHOLD = 32 * 1024;

        // 解帧器
        private Deframer deframer;
        private final Object onReadyLock = new Object();
        private final StatsTraceContext statsTraceCtx;
        private final TransportTracer transportTracer;
        private final MessageDeframer rawDeframer;

        /**
         * The number of bytes currently queued, waiting to be sent. When this falls below
         * DEFAULT_ONREADY_THRESHOLD, {@link StreamListener#onReady()} will be called.
         * 当前队列中的字节数量，
         */
        @GuardedBy("onReadyLock")
        private int numSentBytesQueued;

        /**
         * Indicates the stream has been created on the connection. This implies that the stream is no
         * longer limited by MAX_CONCURRENT_STREAMS.
         * 表示流已经在这个连接上创建，暗示这个流已经不会被 MAX_CONCURRENT_STREAMS 限制
         */
        @GuardedBy("onReadyLock")
        private boolean allocated;

        /**
         * Indicates that the stream no longer exists for the transport. Implies that the application
         * should be discouraged from sending, because doing so would have no effect.
         * 表示流已经不存在于这个 Transport，暗示应用应当不再发送，因为即使发送也没有效果
         */
        @GuardedBy("onReadyLock")
        private boolean deallocated;

        protected TransportState(int maxMessageSize, StatsTraceContext statsTraceCtx, TransportTracer transportTracer) {
            this.statsTraceCtx = checkNotNull(statsTraceCtx, "statsTraceCtx");
            this.transportTracer = checkNotNull(transportTracer, "transportTracer");
            rawDeframer = new MessageDeframer(this, Codec.Identity.NONE, maxMessageSize, statsTraceCtx, transportTracer);
            // TODO(#7168): use MigratingThreadDeframer when enabling retry doesn't break.
            deframer = rawDeframer;
        }

        /**
         * 提供一个提示，说明 directExecutor 被监听器使用，用于回调，不需要此方法实际匹配所使用的执行器
         */
        final void optimizeForDirectExecutor() {
            // 为 MessageDeframer 设置监听器
            rawDeframer.setListener(this);
            deframer = rawDeframer;
        }

        /**
         * 设置整个流的解压缩器
         *
         * @param fullStreamDecompressor 解压缩器
         */
        protected void setFullStreamDecompressor(GzipInflatingBuffer fullStreamDecompressor) {
            rawDeframer.setFullStreamDecompressor(fullStreamDecompressor);
            deframer = new ApplicationThreadDeframer(this, this, rawDeframer);
        }

        /**
         * 设置最大的可接收的流的大小
         */
        final void setMaxInboundMessageSize(int maxSize) {
            deframer.setMaxInboundMessageSize(maxSize);
        }

        /**
         * Override this method to provide a stream listener.
         * 流监听器
         */
        protected abstract StreamListener listener();

        /**
         * 调用以传递下一条完整消息
         *
         * @param producer single message producer wrapping the message.
         *                 包装的单个消息生产者
         */
        @Override
        public void messagesAvailable(StreamListener.MessageProducer producer) {
            listener().messagesAvailable(producer);
        }

        /**
         * Closes the deframer and frees any resources. After this method is called, additional calls
         * will have no effect.
         * 关闭解帧器并释放所有资源，当调用这个方法之后，后续的调用将不会有作用
         *
         * <p>When {@code stopDelivery} is false, the deframer will wait to close until any already
         * queued messages have been delivered.
         * 当 stopDelivery 是 false 时，deframer 将不会关闭直到已经在队列中的消息都投递之后
         *
         * <p>The deframer will invoke {@link #deframerClosed(boolean)} upon closing.
         * deframer 关闭时将会调用 deframerClosed
         *
         * @param stopDelivery interrupt pending deliveries and close immediately
         *                     打断等待的投递并立即关闭
         */
        protected final void closeDeframer(boolean stopDelivery) {
            // 如果是立即关闭，则调用 close 方法立即关闭
            if (stopDelivery) {
                deframer.close();
            } else {
                // 如果不是立即关闭，则等待完成后关闭
                deframer.closeWhenComplete();
            }
        }

        /**
         * Called to parse a received frame and attempt delivery of any completed messages. Must be
         * called from the transport thread.
         * 用于解析接收到的帧并尝试投递任何完成的消息，必须由 Transport 的线程调用
         */
        protected final void deframe(final ReadableBuffer frame) {
            try {
                // 将给定的数据添加到解帧器中，尝试发送给监听器
                deframer.deframe(frame);
            } catch (Throwable t) {
                deframeFailed(t);
            }
        }

        /**
         * Called to request the given number of messages from the deframer. May be called from any
         * thread.
         * 执行发送指定数量的消息的任务
         */
        private void requestMessagesFromDeframer(final int numMessages) {
            // 如果是线程安全的解帧器，则直接执行
            if (deframer instanceof ThreadOptimizedDeframer) {
                PerfMark.startTask("AbstractStream.request");
                try {
                    // 发送指定数量的消息
                    deframer.request(numMessages);
                } finally {
                    PerfMark.stopTask("AbstractStream.request");
                }
                return;
            }
            // 如果不是线程安全的解帧器，则由 Transport 的线程执行
            final Link link = PerfMark.linkOut();
            class RequestRunnable implements Runnable {
                @Override
                public void run() {
                    PerfMark.startTask("AbstractStream.request");
                    PerfMark.linkIn(link);
                    try {
                        deframer.request(numMessages);
                    } catch (Throwable t) {
                        deframeFailed(t);
                    } finally {
                        PerfMark.stopTask("AbstractStream.request");
                    }
                }
            }

            runOnTransportThread(new RequestRunnable());
        }

        /**
         * Very rarely used. Prefer stream.request() instead of this; this method is only necessary if
         * a stream is not available.
         */
        @VisibleForTesting
        public final void requestMessagesFromDeframerForTesting(int numMessages) {
            requestMessagesFromDeframer(numMessages);
        }

        /**
         * 用于统计的上下文
         */
        public final StatsTraceContext getStatsTraceContext() {
            return statsTraceCtx;
        }

        /**
         * 设置解压缩器
         */
        protected final void setDecompressor(Decompressor decompressor) {
            deframer.setDecompressor(decompressor);
        }

        /**
         * 流是否 ready
         * 当流已经在这个连接上创建，且已发送的字节数小于流默认的可缓冲队列的字节大小，且依然存在时表示 ready
         */
        private boolean isReady() {
            synchronized (onReadyLock) {
                return allocated && numSentBytesQueued < DEFAULT_ONREADY_THRESHOLD && !deallocated;
            }
        }

        /**
         * Event handler to be called by the subclass when the stream's headers have passed any
         * connection flow control (i.e., MAX_CONCURRENT_STREAMS). It may call the listener's {@link
         * StreamListener#onReady()} handler if appropriate. This must be called from the transport
         * thread, since the listener may be called back directly.
         * <p>
         * 当流的 header 传递给流控的任意连接时的事件处理器，在适当的时候会调用 StreamListener#onReady()，
         * 因为可能直接回调监听器，必须从 Transport 的线程中调用
         */
        protected void onStreamAllocated() {
            checkState(listener() != null);
            synchronized (onReadyLock) {
                checkState(!allocated, "Already allocated");
                allocated = true;
            }
            notifyIfReady();
        }

        /**
         * Notify that the stream does not exist in a usable state any longer. This causes {@link
         * AbstractStream#isReady()} to return {@code false} from this point forward.
         * 通知当流的状态不可以再使用，会导致 AbstractStream#isReady() 返回 false
         *
         * <p>This does not generally need to be called explicitly by the transport, as it is handled
         * implicitly by {@link AbstractClientStream} and {@link AbstractServerStream}.
         * 通常不需要由 Transport 显式调用，通常由 AbstractClientStream 和 AbstractServerStream 隐式调用
         */
        protected final void onStreamDeallocated() {
            synchronized (onReadyLock) {
                deallocated = true;
            }
        }

        /**
         * Event handler to be called by the subclass when a number of bytes are being queued for
         * sending to the remote endpoint.
         * 当多个字节排队等待发送到远程端点时，子类将调用事件处理程序
         *
         * @param numBytes the number of bytes being sent.
         */
        private void onSendingBytes(int numBytes) {
            synchronized (onReadyLock) {
                numSentBytesQueued += numBytes;
            }
        }

        /**
         * Event handler to be called by the subclass when a number of bytes has been sent to the remote
         * endpoint. May call back the listener's {@link StreamListener#onReady()} handler if
         * appropriate.  This must be called from the transport thread, since the listener may be called
         * back directly.
         * 当多个字节发送给远程端点时的事件处理，可能调用 StreamListener#onReady() 回调，必须由 Transport 线程调用，
         * 监听器可能会直接调用
         *
         * @param numBytes the number of bytes that were sent.
         *                 已经发送的字节数
         */
        public final void onSentBytes(int numBytes) {
            boolean doNotify;
            synchronized (onReadyLock) {
                checkState(allocated, "onStreamAllocated was not called, but it seems the stream is active");
                boolean belowThresholdBefore = numSentBytesQueued < DEFAULT_ONREADY_THRESHOLD;
                numSentBytesQueued -= numBytes;
                boolean belowThresholdAfter = numSentBytesQueued < DEFAULT_ONREADY_THRESHOLD;
                doNotify = !belowThresholdBefore && belowThresholdAfter;
            }
            // 如果需要则通知 ready
            if (doNotify) {
                notifyIfReady();
            }
        }

        /**
         * 获取 Transport
         */
        protected TransportTracer getTransportTracer() {
            return transportTracer;
        }

        /**
         * 如果 ready 则通知
         */
        private void notifyIfReady() {
            boolean doNotify;
            synchronized (onReadyLock) {
                // 判断是否 ready
                doNotify = isReady();
            }
            // 如果 ready 则调用监听器 ready
            if (doNotify) {
                listener().onReady();
            }
        }
    }
}
