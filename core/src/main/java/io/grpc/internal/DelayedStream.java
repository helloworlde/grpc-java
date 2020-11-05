/*
 * Copyright 2015 The gRPC Authors
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
import io.grpc.Attributes;
import io.grpc.Compressor;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.Status;

import javax.annotation.concurrent.GuardedBy;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * A stream that queues requests before the transport is available, and delegates to a real stream
 * implementation when the transport is available.
 * 支持在 Transport 可用之前将请求放入到队列中的流，当 Transport 可用之后代理真正的流
 *
 * <p>{@code ClientStream} itself doesn't require thread-safety. However, the state of {@code
 * DelayedStream} may be internally altered by different threads, thus internal synchronization is
 * necessary.
 * ClientStream 自己不要求线程安全，但是，DelayedStream 状态可能会被内部的不同线程更改，所以需要保证同步
 */
class DelayedStream implements ClientStream {
    /**
     * {@code true} once realStream is valid and all pending calls have been drained.
     * 当 realStream 有效并且所有的挂起的调用都执行时为 true
     */
    private volatile boolean passThrough;

    /**
     * Non-{@code null} iff start has been called. Used to assert methods are called in appropriate
     * order, but also used if an error occurrs before {@code realStream} is set.
     * 当 start 被调用后不是 null，用于判断方法使用适当的顺序调用，如果在设置 realStream 之前发生错误也会被调用
     */
    private ClientStreamListener listener;
    /**
     * Must hold {@code this} lock when setting.
     * 当设置的时候必须持有当前的锁
     */
    private ClientStream realStream;

    @GuardedBy("this")
    private Status error;

    /**
     * 等待执行的流
     */
    @GuardedBy("this")
    private List<Runnable> pendingCalls = new ArrayList<>();

    @GuardedBy("this")
    private DelayedStreamListener delayedListener;

    @GuardedBy("this")
    private long startTimeNanos;

    @GuardedBy("this")
    private long streamSetTimeNanos;

    /**
     * 设置最大的可入站的消息大小
     *
     * @param maxSize 消息大小
     */
    @Override
    public void setMaxInboundMessageSize(final int maxSize) {
        // 当流有效时直接设置，当没有就绪时延迟设置
        if (passThrough) {
            realStream.setMaxInboundMessageSize(maxSize);
        } else {
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realStream.setMaxInboundMessageSize(maxSize);
                }
            });
        }
    }

    /**
     * 设置最大的出站消息大小
     *
     * @param maxSize 消息大小
     */
    @Override
    public void setMaxOutboundMessageSize(final int maxSize) {
        // 当流有效时直接设置，当没有就绪时延迟设置
        if (passThrough) {
            realStream.setMaxOutboundMessageSize(maxSize);
        } else {
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realStream.setMaxOutboundMessageSize(maxSize);
                }
            });
        }
    }

    /**
     * 设置过期时间
     */
    @Override
    public void setDeadline(final Deadline deadline) {
        delayOrExecute(new Runnable() {
            @Override
            public void run() {
                realStream.setDeadline(deadline);
            }
        });
    }


    /**
     * 将包含在本地生成的 DEADLINE_EXCEEDED 错误中的信息附加到给定的 InsightBuilder，告诉用户当前流的状态，以便于
     * 处理错误
     */
    @Override
    public void appendTimeoutInsight(InsightBuilder insight) {
        synchronized (this) {
            if (listener == null) {
                return;
            }
            if (realStream != null) {
                insight.appendKeyValue("buffered_nanos", streamSetTimeNanos - startTimeNanos);
                realStream.appendTimeoutInsight(insight);
            } else {
                insight.appendKeyValue("buffered_nanos", System.nanoTime() - startTimeNanos);
                insight.append("waiting_for_connection");
            }
        }
    }

    /**
     * Transfers all pending and future requests and mutations to the given stream.
     * 将所有等待发送后者等待返回的请求转移给这个流
     * <p>No-op if either this method or {@link #cancel} have already been called.
     */
    // When this method returns, passThrough is guaranteed to be true
    // 当这个方法返回的时候，passThrough 是 true
    final void setStream(ClientStream stream) {
        synchronized (this) {
            // If realStream != null, then either setStream() or cancel() has been called.
            if (realStream != null) {
                return;
            }
            // 设置流
            setRealStream(checkNotNull(stream, "stream"));
        }

        // 处理等待的请求
        drainPendingCalls();
    }

    /**
     * Called to transition {@code passThrough} to {@code true}. This method is not safe to be called
     * multiple times; the caller must ensure it will only be called once, ever. {@code this} lock
     * should not be held when calling this method.
     * <p>
     * 将 passThrough 的值设置为 true，这个方法多次调用是不安全的，调用者必须确保只调用一次
     */
    private void drainPendingCalls() {
        assert realStream != null;
        assert !passThrough;
        List<Runnable> toRun = new ArrayList<>();
        DelayedStreamListener delayedListener = null;
        while (true) {
            synchronized (this) {
                // 如果等待调用的为空，则设置 passThrough 为 true
                if (pendingCalls.isEmpty()) {
                    pendingCalls = null;
                    passThrough = true;
                    delayedListener = this.delayedListener;
                    break;
                }
                // Since there were pendingCalls, we need to process them. To maintain ordering we can't set
                // passThrough=true until we run all pendingCalls, but new Runnables may be added after we
                // drop the lock. So we will have to re-check pendingCalls.
                // 如果有等待的调用，需要先处理它们，为了保持顺序，在处理完等待的请求之前不能将 passThrough 设置为 true，
                // 但是在丢弃了锁之后，新的任务可以被添加进来，所以还需要再次检查是否有等待的请求
                // 如果有等待调用的，则赋值给 toRun
                List<Runnable> tmp = toRun;
                toRun = pendingCalls;
                pendingCalls = tmp;
            }
            // 执行 toRun 中的任务，
            for (Runnable runnable : toRun) {
                // Must not call transport while lock is held to prevent deadlocks.
                // 为防止死锁，在持有锁时不能调用 Transport
                // TODO(ejona): exception handling
                runnable.run();
            }
            // 执行后清除
            toRun.clear();
        }
        // 如果监听器不为空，则执行回调
        if (delayedListener != null) {
            delayedListener.drainPendingCallbacks();
        }
    }

    /**
     * Enqueue the runnable or execute it now. Call sites that may be called many times may want avoid
     * this method if {@code passThrough == true}.
     * 将任务添加到队列中，或立即执行；可能被多次调用的方法在 passThrough 为 true 时应当避免调用
     *
     * <p>Note that this method is no more thread-safe than {@code runnable}. It is thread-safe if and
     * only if {@code runnable} is thread-safe.
     * 这个方法不是线程安全的，除非 Runnable 自己是线程安全的
     */
    private void delayOrExecute(Runnable runnable) {
        synchronized (this) {
            // 如果没有有效的流，则加入队列
            if (!passThrough) {
                pendingCalls.add(runnable);
                return;
            }
        }
        // 如果有有效的流，则立即执行
        runnable.run();
    }

    /**
     * 设置服务名称
     */
    @Override
    public void setAuthority(final String authority) {
        checkState(listener == null, "May only be called before start");
        checkNotNull(authority, "authority");
        delayOrExecute(new Runnable() {
            @Override
            public void run() {
                realStream.setAuthority(authority);
            }
        });
    }

    /**
     * 开始一个流
     *
     * @param listener non-{@code null} listener of stream events
     */
    @Override
    public void start(ClientStreamListener listener) {
        checkState(this.listener == null, "already started");

        Status savedError;
        boolean savedPassThrough;
        synchronized (this) {
            this.listener = checkNotNull(listener, "listener");
            // If error != null, then cancel() has been called and was unable to close the listener
            savedError = error;
            savedPassThrough = passThrough;
            // 如果流没有 ready，则使用延迟的监听器
            if (!savedPassThrough) {
                listener = delayedListener = new DelayedStreamListener(listener);
            }
            startTimeNanos = System.nanoTime();
        }
        // 如果有错误，则关闭监听器
        if (savedError != null) {
            listener.closed(savedError, new Metadata());
            return;
        }

        // 如果流有效，则开始
        if (savedPassThrough) {
            realStream.start(listener);
        } else {
            // 如果流没有ready，则稍后执行
            final ClientStreamListener finalListener = listener;
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realStream.start(finalListener);
                }
            });
        }
    }

    /**
     * 返回流的属性
     */
    @Override
    public Attributes getAttributes() {
        ClientStream savedRealStream;
        synchronized (this) {
            savedRealStream = realStream;
        }
        // 如果有 ready 的流，则使用 ready 的流的属性，如果没有则返回空属性
        if (savedRealStream != null) {
            return savedRealStream.getAttributes();
        } else {
            return Attributes.EMPTY;
        }
    }

    /**
     * 写入消息
     *
     * @param message stream containing the serialized message to be sent
     */
    @Override
    public void writeMessage(final InputStream message) {
        checkNotNull(message, "message");
        // 如果流 ready 则直接执行，如果无效则稍后执行
        if (passThrough) {
            realStream.writeMessage(message);
        } else {
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realStream.writeMessage(message);
                }
            });
        }
    }

    /**
     * 清空流缓存
     */
    @Override
    public void flush() {
        // 如果流 ready 则直接执行，如果无效则稍后执行
        if (passThrough) {
            realStream.flush();
        } else {
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realStream.flush();
                }
            });
        }
    }

    /**
     * When this method returns, passThrough is guaranteed to be true
     * 使用指定的状态取消流，当方法返回时， passThrough 被设置为 true
     */
    @Override
    public void cancel(final Status reason) {
        checkNotNull(reason, "reason");
        boolean delegateToRealStream = true;
        ClientStreamListener listenerToClose = null;
        synchronized (this) {
            // If realStream != null, then either setStream() or cancel() has been called
            // 如果流是空的，则将设置流为 NoopClientStream
            if (realStream == null) {
                setRealStream(NoopClientStream.INSTANCE);
                delegateToRealStream = false;
                // If listener == null, then start() will later call listener with 'error'
                listenerToClose = listener;
                error = reason;
            }
        }
        // 如果有真正的流，则将流取消
        if (delegateToRealStream) {
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realStream.cancel(reason);
                }
            });
        } else {
            // 如果没有真正的流，但是有监听器，则关闭监听器
            if (listenerToClose != null) {
                listenerToClose.closed(reason, new Metadata());
            }
            // 执行等待的流
            drainPendingCalls();
        }
    }

    /**
     * 设置真正的流
     */
    @GuardedBy("this")
    private void setRealStream(ClientStream realStream) {
        checkState(this.realStream == null, "realStream already set to %s", this.realStream);
        this.realStream = realStream;
        streamSetTimeNanos = System.nanoTime();
    }

    /**
     * 半关闭流
     */
    @Override
    public void halfClose() {
        delayOrExecute(new Runnable() {
            @Override
            public void run() {
                realStream.halfClose();
            }
        });
    }

    /**
     * 发送给定数量的消息
     *
     * @param numMessages the requested number of messages to be delivered to the listener.
     */
    @Override
    public void request(final int numMessages) {
        if (passThrough) {
            realStream.request(numMessages);
        } else {
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realStream.request(numMessages);
                }
            });
        }
    }

    @Override
    public void optimizeForDirectExecutor() {
        delayOrExecute(new Runnable() {
            @Override
            public void run() {
                realStream.optimizeForDirectExecutor();
            }
        });
    }

    @Override
    public void setCompressor(final Compressor compressor) {
        checkNotNull(compressor, "compressor");
        delayOrExecute(new Runnable() {
            @Override
            public void run() {
                realStream.setCompressor(compressor);
            }
        });
    }

    @Override
    public void setFullStreamDecompression(final boolean fullStreamDecompression) {
        delayOrExecute(new Runnable() {
            @Override
            public void run() {
                realStream.setFullStreamDecompression(fullStreamDecompression);
            }
        });
    }

    @Override
    public void setDecompressorRegistry(final DecompressorRegistry decompressorRegistry) {
        checkNotNull(decompressorRegistry, "decompressorRegistry");
        delayOrExecute(new Runnable() {
            @Override
            public void run() {
                realStream.setDecompressorRegistry(decompressorRegistry);
            }
        });
    }

    /**
     * 返回流是否 ready
     */
    @Override
    public boolean isReady() {
        if (passThrough) {
            return realStream.isReady();
        } else {
            return false;
        }
    }

    @Override
    public void setMessageCompression(final boolean enable) {
        if (passThrough) {
            realStream.setMessageCompression(enable);
        } else {
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realStream.setMessageCompression(enable);
                }
            });
        }
    }

    @VisibleForTesting
    ClientStream getRealStream() {
        return realStream;
    }

    /**
     * 延迟执行的流监听器
     */
    private static class DelayedStreamListener implements ClientStreamListener {
        private final ClientStreamListener realListener;
        private volatile boolean passThrough;

        // 等待的请求的回调
        @GuardedBy("this")
        private List<Runnable> pendingCallbacks = new ArrayList<>();

        public DelayedStreamListener(ClientStreamListener listener) {
            this.realListener = listener;
        }

        /**
         * 如果没有就绪的流，则将任务添加到队列中，如果有则立即执行
         */
        private void delayOrExecute(Runnable runnable) {
            synchronized (this) {
                if (!passThrough) {
                    pendingCallbacks.add(runnable);
                    return;
                }
            }
            runnable.run();
        }

        /**
         * 当从远程端点接收到消息时调用
         */
        @Override
        public void messagesAvailable(final MessageProducer producer) {
            if (passThrough) {
                realListener.messagesAvailable(producer);
            } else {
                delayOrExecute(new Runnable() {
                    @Override
                    public void run() {
                        realListener.messagesAvailable(producer);
                    }
                });
            }
        }

        /**
         * 如果有就绪的流，则直接通知监听器 ready，如果没有则延迟执行
         */
        @Override
        public void onReady() {
            if (passThrough) {
                realListener.onReady();
            } else {
                delayOrExecute(new Runnable() {
                    @Override
                    public void run() {
                        realListener.onReady();
                    }
                });
            }
        }

        /**
         * 延迟执行当 Header 被读取之后的回调
         */
        @Override
        public void headersRead(final Metadata headers) {
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realListener.headersRead(headers);
                }
            });
        }

        /**
         * 延迟执行关闭流
         *
         * @param status   details about the remote closure
         *                 server 端关闭时的状态
         * @param trailers trailing metadata
         */
        @Override
        public void closed(final Status status, final Metadata trailers) {
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realListener.closed(status, trailers);
                }
            });
        }

        /**
         * 延迟执行流完全关闭的回调
         *
         * @param status      details about the remote closure
         *                    server 端关闭时的状态
         * @param rpcProgress RPC progress when client stream listener is closed
         *                    客户端关闭时，RPC 的进程
         * @param trailers    trailing metadata
         */
        @Override
        public void closed(final Status status,
                           final RpcProgress rpcProgress,
                           final Metadata trailers) {
            delayOrExecute(new Runnable() {
                @Override
                public void run() {
                    realListener.closed(status, rpcProgress, trailers);
                }
            });
        }

        /**
         * 处理等待的请求的回调
         */
        public void drainPendingCallbacks() {
            assert !passThrough;
            List<Runnable> toRun = new ArrayList<>();
            while (true) {
                // 如果等待调用的为空，则设置 passThrough 为 true
                synchronized (this) {
                    if (pendingCallbacks.isEmpty()) {
                        pendingCallbacks = null;
                        passThrough = true;
                        break;
                    }
                    // Since there were pendingCallbacks, we need to process them. To maintain ordering we
                    // can't set passThrough=true until we run all pendingCallbacks, but new Runnables may be
                    // added after we drop the lock. So we will have to re-check pendingCallbacks.
                    // 如果有等待的回调，则需要处理，为了保证顺序在执行完成之前，不能将 passThrough 设置为 true，但是在丢弃锁之后
                    // 可能会有新的任务可能会加入，所以需要再检查一次等待执行的回调
                    List<Runnable> tmp = toRun;
                    toRun = pendingCallbacks;
                    pendingCallbacks = tmp;
                }
                // 逐个执行回调
                for (Runnable runnable : toRun) {
                    // Avoid calling listener while lock is held to prevent deadlocks.
                    // TODO(ejona): exception handling
                    runnable.run();
                }
                toRun.clear();
            }
        }
    }
}
