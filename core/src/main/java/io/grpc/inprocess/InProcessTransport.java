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

package io.grpc.inprocess;

import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Compressor;
import io.grpc.Deadline;
import io.grpc.Decompressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Grpc;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalLogId;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.internal.ClientStream;
import io.grpc.internal.ClientStreamListener;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InUseStateAggregator;
import io.grpc.internal.InsightBuilder;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.NoopClientStream;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerStreamListener;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.StreamListener;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.internal.GrpcUtil.TIMEOUT_KEY;
import static java.lang.Math.max;

/**
 * 进程内的 Server 和 Client Transport 的实现，用于测试
 */
@ThreadSafe
final class InProcessTransport implements ServerTransport, ConnectionClientTransport {

    private static final Logger log = Logger.getLogger(InProcessTransport.class.getName());

    private final InternalLogId logId;
    private final String name;

    private final int clientMaxInboundMetadataSize;
    private final String authority;
    private final String userAgent;

    private final Optional<ServerListener> optionalServerListener;
    private int serverMaxInboundMetadataSize;
    private final boolean includeCauseWithStatus;

    private ObjectPool<ScheduledExecutorService> serverSchedulerPool;
    private ScheduledExecutorService serverScheduler;
    private ServerTransportListener serverTransportListener;
    private Attributes serverStreamAttributes;
    private ManagedClientTransport.Listener clientTransportListener;

    @GuardedBy("this")
    private boolean shutdown;
    @GuardedBy("this")
    private boolean terminated;
    @GuardedBy("this")
    private Status shutdownStatus;
    @GuardedBy("this")
    private Set<InProcessStream> streams = new HashSet<>();
    @GuardedBy("this")
    private List<ServerStreamTracer.Factory> serverStreamTracerFactories;
    private final Attributes attributes;

    /**
     * 使用中状态对象集合
     */
    @GuardedBy("this")
    private final InUseStateAggregator<InProcessStream> inUseState = new InUseStateAggregator<InProcessStream>() {
        /**
         * 修改 Transport 使用中状态为 true
         */
        @Override
        protected void handleInUse() {
            clientTransportListener.transportInUse(true);
        }

        /**
         * 修改 Transport 使用中状态为 false
         */
        @Override
        protected void handleNotInUse() {
            clientTransportListener.transportInUse(false);
        }
    };

    private InProcessTransport(String name,
                               int maxInboundMetadataSize,
                               String authority,
                               String userAgent,
                               Attributes eagAttrs,
                               Optional<ServerListener> optionalServerListener,
                               boolean includeCauseWithStatus) {
        this.name = name;
        this.clientMaxInboundMetadataSize = maxInboundMetadataSize;
        this.authority = authority;
        this.userAgent = GrpcUtil.getGrpcUserAgent("inprocess", userAgent);
        checkNotNull(eagAttrs, "eagAttrs");

        this.attributes = Attributes.newBuilder()
                                    .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.PRIVACY_AND_INTEGRITY)
                                    .set(GrpcAttributes.ATTR_CLIENT_EAG_ATTRS, eagAttrs)
                                    .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InProcessSocketAddress(name))
                                    .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InProcessSocketAddress(name))
                                    .build();
        this.optionalServerListener = optionalServerListener;
        logId = InternalLogId.allocate(getClass(), name);
        this.includeCauseWithStatus = includeCauseWithStatus;
    }

    public InProcessTransport(String name,
                              int maxInboundMetadataSize,
                              String authority,
                              String userAgent,
                              Attributes eagAttrs,
                              boolean includeCauseWithStatus) {
        this(name, maxInboundMetadataSize, authority, userAgent, eagAttrs, Optional.<ServerListener>absent(), includeCauseWithStatus);
    }

    InProcessTransport(String name,
                       int maxInboundMetadataSize,
                       String authority,
                       String userAgent,
                       Attributes eagAttrs,
                       ObjectPool<ScheduledExecutorService> serverSchedulerPool,
                       List<ServerStreamTracer.Factory> serverStreamTracerFactories,
                       ServerListener serverListener) {
        this(name, maxInboundMetadataSize, authority, userAgent, eagAttrs, Optional.of(serverListener), false);
        this.serverMaxInboundMetadataSize = maxInboundMetadataSize;
        this.serverSchedulerPool = serverSchedulerPool;
        this.serverStreamTracerFactories = serverStreamTracerFactories;
    }

    /**
     * 开始 ClientTransport
     *
     * @param listener non-{@code null} listener of transport events
     *                 非空的 Transport 事件监听器
     */
    @CheckReturnValue
    @Override
    public synchronized Runnable start(ManagedClientTransport.Listener listener) {
        this.clientTransportListener = listener;

        // 如果有 ServerListener，则触发 Transport 创建事件
        if (optionalServerListener.isPresent()) {
            serverScheduler = serverSchedulerPool.getObject();
            serverTransportListener = optionalServerListener.get().transportCreated(this);
        } else {
            // 如果没有 ServerListener，则根据名称查找 Server
            InProcessServer server = InProcessServer.findServer(name);
            // 如果 Server 存在，则通过当前的 Transport 建立连接
            if (server != null) {
                serverMaxInboundMetadataSize = server.getMaxInboundMetadataSize();
                serverSchedulerPool = server.getScheduledExecutorServicePool();
                serverScheduler = serverSchedulerPool.getObject();
                serverStreamTracerFactories = server.getStreamTracerFactories();
                // Must be semi-initialized; past this point, can begin receiving requests
                serverTransportListener = server.register(this);
            }
        }

        // 如果 Server Transport 监听器为 null，则返回异常并关闭 Transport
        if (serverTransportListener == null) {
            shutdownStatus = Status.UNAVAILABLE.withDescription("Could not find server: " + name);
            final Status localShutdownStatus = shutdownStatus;
            return new Runnable() {
                @Override
                public void run() {
                    synchronized (InProcessTransport.this) {
                        // 关闭 Transport 及监听器
                        notifyShutdown(localShutdownStatus);
                        notifyTerminated();
                    }
                }
            };
        }
        // 返回将 Transport 状态改为 Ready 的任务
        return new Runnable() {
            @Override
            @SuppressWarnings("deprecation")
            public void run() {
                synchronized (InProcessTransport.this) {
                    Attributes serverTransportAttrs = Attributes.newBuilder()
                                                                .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InProcessSocketAddress(name))
                                                                .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InProcessSocketAddress(name))
                                                                .build();
                    serverStreamAttributes = serverTransportListener.transportReady(serverTransportAttrs);
                    clientTransportListener.transportReady();
                }
            }
        };
    }

    /**
     * 创建新的流
     *
     * @param method      the descriptor of the remote method to be called for this stream.
     *                    这个流被调用的远程方法的描述
     * @param headers     to send at the beginning of the call
     *                    在调用开始会被发送的信息
     * @param callOptions runtime options of the call
     *                    调用执行时的选项
     */
    @Override
    public synchronized ClientStream newStream(final MethodDescriptor<?, ?> method,
                                               final Metadata headers,
                                               final CallOptions callOptions) {
        // 如果是关闭中，则返回失败的流
        if (shutdownStatus != null) {
            return failedClientStream(StatsTraceContext.newClientContext(callOptions, attributes, headers), shutdownStatus);
        }

        headers.put(GrpcUtil.USER_AGENT_KEY, userAgent);

        // 如果 server 最大接收的 Metadata 有效
        if (serverMaxInboundMetadataSize != Integer.MAX_VALUE) {
            // 计算 Metadata 大小
            int metadataSize = metadataSize(headers);
            // 如果 Metadata 超过限制，则返回失败的流
            if (metadataSize > serverMaxInboundMetadataSize) {
                // Other transports would compute a status with:
                //   GrpcUtil.httpStatusToGrpcStatus(431 /* Request Header Fields Too Large */);
                // However, that isn't handled specially today, so we'd leak HTTP-isms even though we're
                // in-process. We go ahead and make a Status, which may need to be updated if
                // statuscodes.md is updated.
                Status status = Status.RESOURCE_EXHAUSTED.withDescription(String.format("Request metadata larger than %d: %d", serverMaxInboundMetadataSize, metadataSize));
                return failedClientStream(StatsTraceContext.newClientContext(callOptions, attributes, headers), status);
            }
        }

        // 如果 Metadata size 正常则返回客户端流
        return new InProcessStream(method, headers, callOptions, authority).clientStream;
    }

    /**
     * 返回失败的流
     */
    private ClientStream failedClientStream(final StatsTraceContext statsTraceCtx,
                                            final Status status) {
        return new NoopClientStream() {
            @Override
            public void start(ClientStreamListener listener) {
                statsTraceCtx.clientOutboundHeaders();
                statsTraceCtx.streamClosed(status);
                listener.closed(status, new Metadata());
            }
        };
    }

    /**
     * 向 Server 端发送 Ping
     *
     * @param callback ping 回调
     * @param executor 执行回调的线程池
     */
    @Override
    public synchronized void ping(final PingCallback callback, Executor executor) {
        // 如果是关闭中，则执行失败的回调
        if (terminated) {
            final Status shutdownStatus = this.shutdownStatus;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    callback.onFailure(shutdownStatus.asRuntimeException());
                }
            });
        } else {
            // 执行成功的回调
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    callback.onSuccess(0);
                }
            });
        }
    }

    /**
     * 关闭 ClientStream
     *
     * @param reason 关闭状态
     */
    @Override
    public synchronized void shutdown(Status reason) {
        // Can be called multiple times: once for ManagedClientTransport, once for ServerTransport.
        if (shutdown) {
            return;
        }
        shutdownStatus = reason;
        notifyShutdown(reason);
        if (streams.isEmpty()) {
            notifyTerminated();
        }
    }

    /**
     * Server 端关闭
     */
    @Override
    public synchronized void shutdown() {
        shutdown(Status.UNAVAILABLE.withDescription("InProcessTransport shutdown by the server-side"));
    }

    /**
     * 客户端立即关闭
     *
     * @param reason 关闭状态
     */
    @Override
    public void shutdownNow(Status reason) {
        checkNotNull(reason, "reason");
        List<InProcessStream> streamsCopy;
        synchronized (this) {
            shutdown(reason);
            if (terminated) {
                return;
            }
            streamsCopy = new ArrayList<>(streams);
        }
        // 遍历所有的流并取消
        for (InProcessStream stream : streamsCopy) {
            stream.clientStream.cancel(reason);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("logId", logId.getId())
                          .add("name", name)
                          .toString();
    }

    @Override
    public InternalLogId getLogId() {
        return logId;
    }

    @Override
    public Attributes getAttributes() {
        return attributes;
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
        return serverScheduler;
    }

    /**
     * 获取统计信息
     */
    @Override
    public ListenableFuture<SocketStats> getStats() {
        SettableFuture<SocketStats> ret = SettableFuture.create();
        ret.set(null);
        return ret;
    }

    /**
     * 异常关闭 Client Transport
     */
    private synchronized void notifyShutdown(Status s) {
        if (shutdown) {
            return;
        }
        shutdown = true;
        clientTransportListener.transportShutdown(s);
    }

    /**
     * 终止 Client 和 Server Transport
     */
    private synchronized void notifyTerminated() {
        if (terminated) {
            return;
        }
        terminated = true;
        if (serverScheduler != null) {
            serverScheduler = serverSchedulerPool.returnObject(serverScheduler);
        }
        clientTransportListener.transportTerminated();
        if (serverTransportListener != null) {
            serverTransportListener.transportTerminated();
        }
    }

    /**
     * 计算 metadata 大小
     */
    private static int metadataSize(Metadata metadata) {
        byte[][] serialized = InternalMetadata.serialize(metadata);
        if (serialized == null) {
            return 0;
        }
        // Calculate based on SETTINGS_MAX_HEADER_LIST_SIZE in RFC 7540 §6.5.2. We could use something
        // different, but it's "sane."
        long size = 0;
        for (int i = 0; i < serialized.length; i += 2) {
            size += 32 + serialized[i].length + serialized[i + 1].length;
        }
        size = Math.min(size, Integer.MAX_VALUE);
        return (int) size;
    }

    /**
     * 进程内的流
     */
    private class InProcessStream {

        // 客户端流
        private final InProcessClientStream clientStream;
        // 服务端流
        private final InProcessServerStream serverStream;
        // 调用参数
        private final CallOptions callOptions;
        // 请求头
        private final Metadata headers;
        // 方法描述
        private final MethodDescriptor<?, ?> method;
        // 服务名称
        private volatile String authority;

        private InProcessStream(MethodDescriptor<?, ?> method,
                                Metadata headers,
                                CallOptions callOptions,
                                String authority) {
            this.method = checkNotNull(method, "method");
            this.headers = checkNotNull(headers, "headers");
            this.callOptions = checkNotNull(callOptions, "callOptions");
            this.authority = authority;
            this.clientStream = new InProcessClientStream(callOptions, headers);
            this.serverStream = new InProcessServerStream(method, headers);
        }

        // Can be called multiple times due to races on both client and server closing at same time.
        // 关闭流，可能多次调用，因为客户端和服务端可能会同时关闭
        private void streamClosed() {
            synchronized (InProcessTransport.this) {
                // 移除流
                boolean justRemovedAnElement = streams.remove(this);
                // 如果不是 LoadBalancer 用于自己的 Channel，则修改使用中状态
                if (GrpcUtil.shouldBeCountedForInUse(callOptions)) {
                    inUseState.updateObjectInUse(this, false);
                }
                // 如果流是空的，且移除流成功，其是关闭中则通知 Transport 终止
                if (streams.isEmpty() && justRemovedAnElement) {
                    if (shutdown) {
                        notifyTerminated();
                    }
                }
            }
        }

        /**
         * InProcess server 流的实现
         */
        private class InProcessServerStream implements ServerStream {
            final StatsTraceContext statsTraceCtx;

            @GuardedBy("this")
            private ClientStreamListener clientStreamListener;

            @GuardedBy("this")
            private int clientRequested;

            @GuardedBy("this")
            private ArrayDeque<StreamListener.MessageProducer> clientReceiveQueue = new ArrayDeque<>();

            @GuardedBy("this")
            private Status clientNotifyStatus;

            @GuardedBy("this")
            private Metadata clientNotifyTrailers;

            // Only is intended to prevent double-close when client cancels.
            @GuardedBy("this")
            private boolean closed;

            @GuardedBy("this")
            private int outboundSeqNo;

            /**
             * 通过方法和 header 构建流
             *
             * @param method  方法
             * @param headers header
             */
            InProcessServerStream(MethodDescriptor<?, ?> method, Metadata headers) {
                statsTraceCtx = StatsTraceContext.newServerContext(serverStreamTracerFactories, method.getFullMethodName(), headers);
            }


            /**
             * 设置客户端流监听器
             *
             * @param listener 监听器
             */
            private synchronized void setListener(ClientStreamListener listener) {
                clientStreamListener = listener;
            }

            /**
             * 设置服务端流监听器
             *
             * @param serverStreamListener 监听器
             */
            @Override
            public void setListener(ServerStreamListener serverStreamListener) {
                clientStream.setListener(serverStreamListener);
            }

            /**
             * Client 发送指定数量的消息
             *
             * @param numMessages the requested number of messages to be delivered to the listener.
             *                    要发送给监听器的消息的数量
             */
            @Override
            public void request(int numMessages) {
                // 通知 server 接收消息
                boolean onReady = clientStream.serverRequested(numMessages);
                // 如果是第一次 ready，且没有关闭，则调用 Client 流监听器通知 ready
                if (onReady) {
                    synchronized (this) {
                        if (!closed) {
                            clientStreamListener.onReady();
                        }
                    }
                }
            }

            // This method is the only reason we have to synchronize field accesses.

            /**
             * Client requested more messages.
             * 客户端发送更多的消息
             *
             * @return whether onReady should be called on the server
             */
            private synchronized boolean clientRequested(int numMessages) {
                // 如果是关闭的状态，则返回 false
                if (closed) {
                    return false;
                }
                // 如果有未发送的消息，则发送
                boolean previouslyReady = clientRequested > 0;
                clientRequested += numMessages;
                while (clientRequested > 0 && !clientReceiveQueue.isEmpty()) {
                    clientRequested--;
                    clientStreamListener.messagesAvailable(clientReceiveQueue.poll());
                }

                // Attempt being reentrant-safe
                // 再次检测是否关闭
                if (closed) {
                    return false;
                }

                // 如果所有的消息都发送完了，则关闭客户端流，更新统计
                if (clientReceiveQueue.isEmpty() && clientNotifyStatus != null) {
                    closed = true;
                    // 从 server 端接收到的尾元数据
                    clientStream.statsTraceCtx.clientInboundTrailers(clientNotifyTrailers);
                    // 流关闭
                    clientStream.statsTraceCtx.streamClosed(clientNotifyStatus);
                    // 通知客户端流监听器关闭
                    clientStreamListener.closed(clientNotifyStatus, clientNotifyTrailers);
                }
                // 返回 ready 的状态
                boolean nowReady = clientRequested > 0;
                return !previouslyReady && nowReady;
            }

            /**
             * 客户端取消流
             *
             * @param status 取消状态
             */
            private void clientCancelled(Status status) {
                internalCancel(status);
            }

            /**
             * 写入消息
             *
             * @param message stream containing the serialized message to be sent
             *                流包含需要发送的序列化的消息
             */
            @Override
            public synchronized void writeMessage(InputStream message) {
                // 如果关闭则返回
                if (closed) {
                    return;
                }
                statsTraceCtx.outboundMessage(outboundSeqNo);
                statsTraceCtx.outboundMessageSent(outboundSeqNo, -1, -1);
                clientStream.statsTraceCtx.inboundMessage(outboundSeqNo);
                clientStream.statsTraceCtx.inboundMessageRead(outboundSeqNo, -1, -1);
                outboundSeqNo++;
                StreamListener.MessageProducer producer = new SingleMessageProducer(message);
                if (clientRequested > 0) {
                    clientRequested--;
                    clientStreamListener.messagesAvailable(producer);
                } else {
                    clientReceiveQueue.add(producer);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public synchronized boolean isReady() {
                if (closed) {
                    return false;
                }
                return clientRequested > 0;
            }

            @Override
            public void writeHeaders(Metadata headers) {
                if (clientMaxInboundMetadataSize != Integer.MAX_VALUE) {
                    int metadataSize = metadataSize(headers);
                    if (metadataSize > clientMaxInboundMetadataSize) {
                        Status serverStatus = Status.CANCELLED.withDescription("Client cancelled the RPC");
                        clientStream.serverClosed(serverStatus, serverStatus);
                        // Other transports provide very little information in this case. We go ahead and make a
                        // Status, which may need to be updated if statuscodes.md is updated.
                        Status failedStatus = Status.RESOURCE_EXHAUSTED.withDescription(
                                String.format(
                                        "Response header metadata larger than %d: %d",
                                        clientMaxInboundMetadataSize,
                                        metadataSize));
                        notifyClientClose(failedStatus, new Metadata());
                        return;
                    }
                }

                synchronized (this) {
                    if (closed) {
                        return;
                    }

                    clientStream.statsTraceCtx.clientInboundHeaders();
                    clientStreamListener.headersRead(headers);
                }
            }

            @Override
            public void close(Status status, Metadata trailers) {
                // clientStream.serverClosed must happen before clientStreamListener.closed, otherwise
                // clientStreamListener.closed can trigger clientStream.cancel (see code in
                // ClientCalls.blockingUnaryCall), which may race with clientStream.serverClosed as both are
                // calling internalCancel().
                clientStream.serverClosed(Status.OK, status);

                if (clientMaxInboundMetadataSize != Integer.MAX_VALUE) {
                    int statusSize = status.getDescription() == null ? 0 : status.getDescription().length();
                    // Go ahead and throw in the status description's length, since that could be very long.
                    int metadataSize = metadataSize(trailers) + statusSize;
                    if (metadataSize > clientMaxInboundMetadataSize) {
                        // Override the status for the client, but not the server. Transports do not guarantee
                        // notifying the server of the failure.

                        // Other transports provide very little information in this case. We go ahead and make a
                        // Status, which may need to be updated if statuscodes.md is updated.
                        status = Status.RESOURCE_EXHAUSTED.withDescription(
                                String.format(
                                        "Response header metadata larger than %d: %d",
                                        clientMaxInboundMetadataSize,
                                        metadataSize));
                        trailers = new Metadata();
                    }
                }

                notifyClientClose(status, trailers);
            }

            /**
             * clientStream.serverClosed() must be called before this method
             */
            private void notifyClientClose(Status status, Metadata trailers) {
                Status clientStatus = cleanStatus(status, includeCauseWithStatus);
                synchronized (this) {
                    if (closed) {
                        return;
                    }
                    if (clientReceiveQueue.isEmpty()) {
                        closed = true;
                        clientStream.statsTraceCtx.clientInboundTrailers(trailers);
                        clientStream.statsTraceCtx.streamClosed(clientStatus);
                        clientStreamListener.closed(clientStatus, trailers);
                    } else {
                        clientNotifyStatus = clientStatus;
                        clientNotifyTrailers = trailers;
                    }
                }

                streamClosed();
            }

            @Override
            public void cancel(Status status) {
                if (!internalCancel(Status.CANCELLED.withDescription("server cancelled stream"))) {
                    return;
                }
                clientStream.serverClosed(status, status);
                streamClosed();
            }

            /**
             * 取消客户端流
             *
             * @param clientStatus 客户端状态
             * @return 取消结果
             */
            private synchronized boolean internalCancel(Status clientStatus) {
                // 如果是关闭状态，则返回 false
                if (closed) {
                    return false;
                }
                closed = true;

                StreamListener.MessageProducer producer;

                // 从队列中获取消息
                while ((producer = clientReceiveQueue.poll()) != null) {
                    InputStream message;
                    // 遍历并关闭
                    while ((message = producer.next()) != null) {
                        try {
                            message.close();
                        } catch (Throwable t) {
                            log.log(Level.WARNING, "Exception closing stream", t);
                        }
                    }
                }
                // 关闭统计和监听器
                clientStream.statsTraceCtx.streamClosed(clientStatus);
                clientStreamListener.closed(clientStatus, new Metadata());
                return true;
            }

            @Override
            public void setMessageCompression(boolean enable) {
                // noop
            }

            @Override
            public void optimizeForDirectExecutor() {
            }

            @Override
            public void setCompressor(Compressor compressor) {
            }

            @Override
            public void setDecompressor(Decompressor decompressor) {
            }

            @Override
            public Attributes getAttributes() {
                return serverStreamAttributes;
            }

            @Override
            public String getAuthority() {
                return InProcessStream.this.authority;
            }

            @Override
            public StatsTraceContext statsTraceContext() {
                return statsTraceCtx;
            }

            @Override
            public int streamId() {
                return -1;
            }
        }

        /**
         * InProcess 客户端流
         */
        private class InProcessClientStream implements ClientStream {

            final StatsTraceContext statsTraceCtx;

            final CallOptions callOptions;

            @GuardedBy("this")
            private ServerStreamListener serverStreamListener;

            @GuardedBy("this")
            private int serverRequested;

            @GuardedBy("this")
            private ArrayDeque<StreamListener.MessageProducer> serverReceiveQueue = new ArrayDeque<>();

            @GuardedBy("this")
            private boolean serverNotifyHalfClose;

            // Only is intended to prevent double-close when server closes.
            @GuardedBy("this")
            private boolean closed;

            @GuardedBy("this")
            private int outboundSeqNo;

            InProcessClientStream(CallOptions callOptions, Metadata headers) {
                this.callOptions = callOptions;
                statsTraceCtx = StatsTraceContext.newClientContext(callOptions, attributes, headers);
            }

            private synchronized void setListener(ServerStreamListener listener) {
                this.serverStreamListener = listener;
            }

            @Override
            public void request(int numMessages) {
                boolean onReady = serverStream.clientRequested(numMessages);
                if (onReady) {
                    synchronized (this) {
                        if (!closed) {
                            serverStreamListener.onReady();
                        }
                    }
                }
            }

            // This method is the only reason we have to synchronize field accesses.

            /**
             * Client requested more messages.
             * 客户端请求消息
             *
             * @return whether onReady should be called on the server
             * 返回是否应该在服务端调用 onReady
             */
            private synchronized boolean serverRequested(int numMessages) {
                // 如果已经是关闭的，则返回false
                if (closed) {
                    return false;
                }

                boolean previouslyReady = serverRequested > 0;
                serverRequested += numMessages;
                // 如果有消息，则通知 server
                while (serverRequested > 0 && !serverReceiveQueue.isEmpty()) {
                    serverRequested--;
                    serverStreamListener.messagesAvailable(serverReceiveQueue.poll());
                }

                // 如果接受队列为空，或者 server 通知半关闭，则调用半关闭
                if (serverReceiveQueue.isEmpty() && serverNotifyHalfClose) {
                    serverNotifyHalfClose = false;
                    serverStreamListener.halfClosed();
                }
                // 当之前的状态不是 ready，且新的状态是 ready 时返回 true
                boolean nowReady = serverRequested > 0;
                return !previouslyReady && nowReady;
            }

            private void serverClosed(Status serverListenerStatus, Status serverTracerStatus) {
                internalCancel(serverListenerStatus, serverTracerStatus);
            }

            @Override
            public synchronized void writeMessage(InputStream message) {
                if (closed) {
                    return;
                }
                statsTraceCtx.outboundMessage(outboundSeqNo);
                statsTraceCtx.outboundMessageSent(outboundSeqNo, -1, -1);
                serverStream.statsTraceCtx.inboundMessage(outboundSeqNo);
                serverStream.statsTraceCtx.inboundMessageRead(outboundSeqNo, -1, -1);
                outboundSeqNo++;
                StreamListener.MessageProducer producer = new SingleMessageProducer(message);
                if (serverRequested > 0) {
                    serverRequested--;
                    serverStreamListener.messagesAvailable(producer);
                } else {
                    serverReceiveQueue.add(producer);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public synchronized boolean isReady() {
                if (closed) {
                    return false;
                }
                return serverRequested > 0;
            }

            // Must be thread-safe for shutdownNow()
            @Override
            public void cancel(Status reason) {
                Status serverStatus = cleanStatus(reason, includeCauseWithStatus);
                if (!internalCancel(serverStatus, serverStatus)) {
                    return;
                }
                serverStream.clientCancelled(reason);
                streamClosed();
            }

            private synchronized boolean internalCancel(
                    Status serverListenerStatus, Status serverTracerStatus) {
                if (closed) {
                    return false;
                }
                closed = true;

                StreamListener.MessageProducer producer;
                while ((producer = serverReceiveQueue.poll()) != null) {
                    InputStream message;
                    while ((message = producer.next()) != null) {
                        try {
                            message.close();
                        } catch (Throwable t) {
                            log.log(Level.WARNING, "Exception closing stream", t);
                        }
                    }
                }
                serverStream.statsTraceCtx.streamClosed(serverTracerStatus);
                serverStreamListener.closed(serverListenerStatus);
                return true;
            }

            @Override
            public synchronized void halfClose() {
                if (closed) {
                    return;
                }
                if (serverReceiveQueue.isEmpty()) {
                    serverStreamListener.halfClosed();
                } else {
                    serverNotifyHalfClose = true;
                }
            }

            @Override
            public void setMessageCompression(boolean enable) {
            }

            @Override
            public void setAuthority(String string) {
                InProcessStream.this.authority = string;
            }

            @Override
            public void start(ClientStreamListener listener) {
                serverStream.setListener(listener);

                synchronized (InProcessTransport.this) {
                    statsTraceCtx.clientOutboundHeaders();
                    streams.add(InProcessTransport.InProcessStream.this);
                    if (GrpcUtil.shouldBeCountedForInUse(callOptions)) {
                        inUseState.updateObjectInUse(InProcessTransport.InProcessStream.this, true);
                    }
                    serverTransportListener.streamCreated(serverStream, method.getFullMethodName(), headers);
                }
            }

            @Override
            public Attributes getAttributes() {
                return attributes;
            }

            @Override
            public void optimizeForDirectExecutor() {
            }

            @Override
            public void setCompressor(Compressor compressor) {
            }

            @Override
            public void setFullStreamDecompression(boolean fullStreamDecompression) {
            }

            @Override
            public void setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
            }

            @Override
            public void setMaxInboundMessageSize(int maxSize) {
            }

            @Override
            public void setMaxOutboundMessageSize(int maxSize) {
            }

            @Override
            public void setDeadline(Deadline deadline) {
                headers.discardAll(TIMEOUT_KEY);
                long effectiveTimeout = max(0, deadline.timeRemaining(TimeUnit.NANOSECONDS));
                headers.put(TIMEOUT_KEY, effectiveTimeout);
            }

            @Override
            public void appendTimeoutInsight(InsightBuilder insight) {
            }
        }
    }

    /**
     * Returns a new status with the same code and description.
     * If includeCauseWithStatus is true, cause is also included.
     * 如果 includeCauseWithStatus 是 true，则返回一个编码和描述相同的新的状态，包含异常
     *
     * <p>For InProcess transport to behave in the same way as the other transports,
     * when exchanging statuses between client and server and vice versa,
     * the cause should be excluded from the status.
     * For easier debugging, the status may be optionally included.
     * <p>
     * 为了使 InProcess 传输的行为方式与其他传输相同，在客户端和服务器之间交换状态（反之亦然）时，原因应从状态中排除
     * 为了便于调试，可以选择包含状态
     */
    private static Status cleanStatus(Status status, boolean includeCauseWithStatus) {
        if (status == null) {
            return null;
        }
        Status clientStatus = Status.fromCodeValue(status.getCode().value()).withDescription(status.getDescription());

        if (includeCauseWithStatus) {
            clientStatus = clientStatus.withCause(status.getCause());
        }
        return clientStatus;
    }

    /**
     * gRPC 消息解码生产者
     */
    private static class SingleMessageProducer implements StreamListener.MessageProducer {
        private InputStream message;

        private SingleMessageProducer(InputStream message) {
            this.message = message;
        }

        @Nullable
        @Override
        public InputStream next() {
            InputStream messageToReturn = message;
            message = null;
            return messageToReturn;
        }
    }
}
