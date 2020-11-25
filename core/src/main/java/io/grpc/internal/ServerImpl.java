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
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.*;
import io.grpc.InternalChannelz.ServerStats;
import io.grpc.InternalChannelz.SocketStats;
import io.perfmark.Link;
import io.perfmark.PerfMark;
import io.perfmark.Tag;

import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.grpc.Contexts.statusFromCancelled;
import static io.grpc.Status.DEADLINE_EXCEEDED;
import static io.grpc.internal.GrpcUtil.MESSAGE_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.TIMEOUT_KEY;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Default implementation of {@link io.grpc.Server}, for creation by transports.
 * Server 的默认实现，通过 Transport 创建
 *
 *
 * <p>Expected usage (by a theoretical TCP transport):
 * <pre><code>public class TcpTransportServerFactory {
 *   public static Server newServer(Executor executor, HandlerRegistry registry,
 *       String configuration) {
 *     return new ServerImpl(executor, registry, new TcpTransportServer(configuration));
 *   }
 * }</code></pre>
 *
 * <p>Starting the server starts the underlying transport for servicing requests. Stopping the
 * server stops servicing new requests and waits for all connections to terminate.
 * 启动服务器将启动用于服务请求的 Transport，关闭 Server 将会停止接收新的请求，等待所有的连接终止
 */
public final class ServerImpl extends io.grpc.Server implements InternalInstrumented<ServerStats> {

    private static final Logger log = Logger.getLogger(ServerImpl.class.getName());

    // 不做任何操作的监听器
    private static final ServerStreamListener NOOP_LISTENER = new NoopListener();

    private final InternalLogId logId;

    private final ObjectPool<? extends Executor> executorPool;

    /**
     * Executor for application processing. Safe to read after {@link #start()}.
     * 用于应用处理的执行器，在 start 调用之后是读取安全的
     */
    private Executor executor;

    private final HandlerRegistry registry;
    private final HandlerRegistry fallbackRegistry;

    private final List<ServerTransportFilter> transportFilters;

    // This is iterated on a per-call basis.  Use an array instead of a Collection to avoid iterator
    // creations.
    private final ServerInterceptor[] interceptors;

    private final long handshakeTimeoutMillis;

    @GuardedBy("lock")
    private boolean started;

    @GuardedBy("lock")
    private boolean shutdown;

    /**
     * non-{@code null} if immediate shutdown has been requested.
     * 如果要求立即关闭则是 null
     */
    @GuardedBy("lock")
    private Status shutdownNowStatus;

    /**
     * {@code true} if ServerListenerImpl.serverShutdown() was called.
     * 如果调用了 ServerListenerImpl.serverShutdown() 则是 true
     */
    @GuardedBy("lock")
    private boolean serverShutdownCallbackInvoked;

    @GuardedBy("lock")
    private boolean terminated;

    /**
     * Service encapsulating something similar to an accept() socket.
     * 用于接收 Socket 的 Server 封装的东西
     */
    private final List<? extends InternalServer> transportServers;

    private final Object lock = new Object();

    @GuardedBy("lock")
    private boolean transportServersTerminated;

    /**
     * {@code transportServer} and services encapsulating something similar to a TCP connection.
     * 用于 TCP 连接的 Server 封装的东西
     */
    @GuardedBy("lock")
    private final Set<ServerTransport> transports = new HashSet<>();

    @GuardedBy("lock")
    private int activeTransportServers;

    private final Context rootContext;

    private final DecompressorRegistry decompressorRegistry;
    private final CompressorRegistry compressorRegistry;
    private final BinaryLog binlog;

    private final InternalChannelz channelz;
    private final CallTracer serverCallTracer;
    private final Deadline.Ticker ticker;

    /**
     * Construct a server.
     * 构建 Server
     *
     * @param builder          builder with configuration for server
     *                         服务配置的构建器
     * @param transportServers transport servers that will create new incoming transports
     *                         用于创建新的 Transport 的 Transport 服务器
     * @param rootContext      context that callbacks for new RPCs should be derived from
     *                         新的请求的回调的上下文
     */
    ServerImpl(AbstractServerImplBuilder<?> builder,
               List<? extends InternalServer> transportServers,
               Context rootContext) {
        this.executorPool = Preconditions.checkNotNull(builder.executorPool, "executorPool");
        this.registry = Preconditions.checkNotNull(builder.registryBuilder.build(), "registryBuilder");
        this.fallbackRegistry = Preconditions.checkNotNull(builder.fallbackRegistry, "fallbackRegistry");
        Preconditions.checkNotNull(transportServers, "transportServers");
        Preconditions.checkArgument(!transportServers.isEmpty(), "no servers provided");
        this.transportServers = new ArrayList<>(transportServers);
        this.logId = InternalLogId.allocate("Server", String.valueOf(getListenSocketsIgnoringLifecycle()));
        // Fork from the passed in context so that it does not propagate cancellation, it only
        // inherits values.
        this.rootContext = Preconditions.checkNotNull(rootContext, "rootContext").fork();
        this.decompressorRegistry = builder.decompressorRegistry;
        this.compressorRegistry = builder.compressorRegistry;
        this.transportFilters = Collections.unmodifiableList(new ArrayList<>(builder.transportFilters));
        this.interceptors = builder.interceptors.toArray(new ServerInterceptor[builder.interceptors.size()]);
        this.handshakeTimeoutMillis = builder.handshakeTimeoutMillis;
        this.binlog = builder.binlog;
        this.channelz = builder.channelz;
        this.serverCallTracer = builder.callTracerFactory.create();
        this.ticker = checkNotNull(builder.ticker, "ticker");
        channelz.addServer(this);
    }

    /**
     * Bind and start the server.
     * 绑定并启动 Server
     *
     * @return {@code this} object
     * @throws IllegalStateException if already started
     * @throws IOException           if unable to bind
     */
    @Override
    public ServerImpl start() throws IOException {
        synchronized (lock) {
            checkState(!started, "Already started");
            checkState(!shutdown, "Shutting down");
            // Start and wait for any ports to actually be bound.
            // 创建服务监听器实例
            ServerListenerImpl listener = new ServerListenerImpl();
            // 遍历 Server 并启动
            for (InternalServer ts : transportServers) {
                ts.start(listener);
                activeTransportServers++;
            }
            executor = Preconditions.checkNotNull(executorPool.getObject(), "executor");
            started = true;
            return this;
        }
    }


    /**
     * 获取监听的端口
     */
    @Override
    public int getPort() {
        synchronized (lock) {
            checkState(started, "Not started");
            checkState(!terminated, "Already terminated");
            // 遍历监听的 Server，返回第一个 InetSocketAddress 的端口
            for (InternalServer ts : transportServers) {
                SocketAddress addr = ts.getListenSocketAddress();
                if (addr instanceof InetSocketAddress) {
                    return ((InetSocketAddress) addr).getPort();
                }
            }
            return -1;
        }
    }

    /**
     * 获取监听的 Socket
     */
    @Override
    public List<SocketAddress> getListenSockets() {
        synchronized (lock) {
            checkState(started, "Not started");
            checkState(!terminated, "Already terminated");
            return getListenSocketsIgnoringLifecycle();
        }
    }

    /**
     * 获取监听的 Socket
     */
    private List<SocketAddress> getListenSocketsIgnoringLifecycle() {
        synchronized (lock) {
            List<SocketAddress> addrs = new ArrayList<>(transportServers.size());
            // 遍历获取 Socket
            for (InternalServer ts : transportServers) {
                addrs.add(ts.getListenSocketAddress());
            }
            return Collections.unmodifiableList(addrs);
        }
    }

    /**
     * 获取注册的服务
     */
    @Override
    public List<ServerServiceDefinition> getServices() {
        // 获取回退注册器中的服务
        List<ServerServiceDefinition> fallbackServices = fallbackRegistry.getServices();
        // 如果回退注册器为空，则获取注册器中的服务
        if (fallbackServices.isEmpty()) {
            return registry.getServices();
        } else {
            // 获取注册器中的服务
            List<ServerServiceDefinition> registryServices = registry.getServices();
            int servicesCount = registryServices.size() + fallbackServices.size();
            List<ServerServiceDefinition> services = new ArrayList<>(servicesCount);
            // 合并注册器和回退注册器中的服务
            services.addAll(registryServices);
            services.addAll(fallbackServices);
            return Collections.unmodifiableList(services);
        }
    }

    /**
     * 获取注册器中的服务
     */
    @Override
    public List<ServerServiceDefinition> getImmutableServices() {
        return registry.getServices();
    }

    /**
     * 获取回退注册器中的服务
     */
    @Override
    public List<ServerServiceDefinition> getMutableServices() {
        return Collections.unmodifiableList(fallbackRegistry.getServices());
    }

    /**
     * Initiates an orderly shutdown in which preexisting calls continue but new calls are rejected.
     * 开始顺序的关闭，已经存在的请求会继续执行，新的请求会被拒绝
     */
    @Override
    public ServerImpl shutdown() {
        boolean shutdownTransportServers;
        synchronized (lock) {
            if (shutdown) {
                return this;
            }
            shutdown = true;
            shutdownTransportServers = started;
            if (!shutdownTransportServers) {
                transportServersTerminated = true;
                // 检查是否终止
                checkForTermination();
            }
        }
        if (shutdownTransportServers) {
            // 遍历所有的 Server 并关闭
            for (InternalServer ts : transportServers) {
                ts.shutdown();
            }
        }
        return this;
    }

    /**
     * 立即关闭 Server
     */
    @Override
    public ServerImpl shutdownNow() {
        // 调用 shutdown 关闭 Transport
        shutdown();
        Collection<ServerTransport> transportsCopy;
        Status nowStatus = Status.UNAVAILABLE.withDescription("Server shutdownNow invoked");
        boolean savedServerShutdownCallbackInvoked;
        synchronized (lock) {
            // Short-circuiting not strictly necessary, but prevents transports from needing to handle
            // multiple shutdownNow invocations if shutdownNow is called multiple times.
            if (shutdownNowStatus != null) {
                return this;
            }
            shutdownNowStatus = nowStatus;
            transportsCopy = new ArrayList<>(transports);
            savedServerShutdownCallbackInvoked = serverShutdownCallbackInvoked;
        }
        // Short-circuiting not strictly necessary, but prevents transports from needing to handle
        // multiple shutdownNow invocations, between here and the serverShutdown callback.
        // 遍历 Transport 调用 shutdownNow
        if (savedServerShutdownCallbackInvoked) {
            // Have to call shutdownNow, because serverShutdown callback only called shutdown, not
            // shutdownNow
            for (ServerTransport transport : transportsCopy) {
                transport.shutdownNow(nowStatus);
            }
        }
        return this;
    }

    @Override
    public boolean isShutdown() {
        synchronized (lock) {
            return shutdown;
        }
    }

    /**
     * 指定超时时间等待终止
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        synchronized (lock) {
            long timeoutNanos = unit.toNanos(timeout);
            long endTimeNanos = System.nanoTime() + timeoutNanos;
            while (!terminated && (timeoutNanos = endTimeNanos - System.nanoTime()) > 0) {
                NANOSECONDS.timedWait(lock, timeoutNanos);
            }
            return terminated;
        }
    }

    /**
     * 等待终止
     */
    @Override
    public void awaitTermination() throws InterruptedException {
        synchronized (lock) {
            while (!terminated) {
                lock.wait();
            }
        }
    }

    @Override
    public boolean isTerminated() {
        synchronized (lock) {
            return terminated;
        }
    }

    /**
     * Remove transport service from accounting collection and notify of complete shutdown if
     * necessary.
     * 从集合中移除 Transport，并在必要时通知关闭
     *
     * @param transport service to remove
     */
    private void transportClosed(ServerTransport transport) {
        synchronized (lock) {
            if (!transports.remove(transport)) {
                throw new AssertionError("Transport already removed");
            }
            channelz.removeServerSocket(ServerImpl.this, transport);
            checkForTermination();
        }
    }

    /**
     * Notify of complete shutdown if necessary.
     * 是否终止的通知
     */
    private void checkForTermination() {
        synchronized (lock) {
            // 如果处于终止状态
            if (shutdown && transports.isEmpty() && transportServersTerminated) {
                // 如果已经终止了，则抛出异常
                if (terminated) {
                    throw new AssertionError("Server already terminated");
                }
                terminated = true;
                channelz.removeServer(this);
                if (executor != null) {
                    executor = executorPool.returnObject(executor);
                }
                // 通知所有的锁
                lock.notifyAll();
            }
        }
    }

    /**
     * 服务监听器
     */
    private final class ServerListenerImpl implements ServerListener {

        /**
         * Transport 创建事件
         */
        @Override
        public ServerTransportListener transportCreated(ServerTransport transport) {
            synchronized (lock) {
                transports.add(transport);
            }
            // Transport 监听器
            ServerTransportListenerImpl stli = new ServerTransportListenerImpl(transport);
            // 初始化监听器，添加握手超时的取消任务
            stli.init();
            return stli;
        }

        /**
         * Server 关闭事件
         */
        @Override
        public void serverShutdown() {
            ArrayList<ServerTransport> copiedTransports;
            Status shutdownNowStatusCopy;
            // 复制 Transport 和状态
            synchronized (lock) {
                activeTransportServers--;
                if (activeTransportServers != 0) {
                    return;
                }

                // transports collection can be modified during shutdown(), even if we hold the lock, due
                // to reentrancy.
                copiedTransports = new ArrayList<>(transports);
                shutdownNowStatusCopy = shutdownNowStatus;
                serverShutdownCallbackInvoked = true;
            }

            // 遍历 Transport，如果没有关闭状态，则调用shutdown 关闭，如果有状态，则调用 shutdownNow 立即关闭
            for (ServerTransport transport : copiedTransports) {
                if (shutdownNowStatusCopy == null) {
                    transport.shutdown();
                } else {
                    transport.shutdownNow(shutdownNowStatusCopy);
                }
            }

            synchronized (lock) {
                transportServersTerminated = true;
                // 是否终止的通知
                checkForTermination();
            }
        }
    }

    /**
     * Server Transport 监听器
     */
    private final class ServerTransportListenerImpl implements ServerTransportListener {

        private final ServerTransport transport;
        private Future<?> handshakeTimeoutFuture;
        private Attributes attributes;

        ServerTransportListenerImpl(ServerTransport transport) {
            this.transport = transport;
        }

        /**
         * 初始化监听器，添加握手超时的取消任务
         */
        public void init() {
            // 握手超时的取消任务
            class TransportShutdownNow implements Runnable {
                @Override
                public void run() {
                    transport.shutdownNow(Status.CANCELLED.withDescription("Handshake timeout exceeded"));
                }
            }

            // 如果允许握手超时，则提交握手超时的取消任务
            if (handshakeTimeoutMillis != Long.MAX_VALUE) {
                handshakeTimeoutFuture = transport.getScheduledExecutorService()
                                                  .schedule(new TransportShutdownNow(), handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
            } else {
                // Noop, to avoid triggering Thread creation in InProcessServer
                handshakeTimeoutFuture = new FutureTask<Void>(new Runnable() {
                    @Override
                    public void run() {
                    }
                }, null);
            }
            // 添加到 Channelz 中
            channelz.addServerSocket(ServerImpl.this, transport);
        }

        /**
         * Transport ready 事件
         */
        @Override
        public Attributes transportReady(Attributes attributes) {
            // 如果有握手超时回调，则取消
            handshakeTimeoutFuture.cancel(false);
            handshakeTimeoutFuture = null;

            // 遍历 TransportFilter，通知 ready 事件并获取 attributes
            for (ServerTransportFilter filter : transportFilters) {
                attributes = Preconditions.checkNotNull(filter.transportReady(attributes), "Filter %s returned null", filter);
            }
            this.attributes = attributes;
            return attributes;
        }

        /**
         * Transport 终止事件
         */
        @Override
        public void transportTerminated() {
            // 如果有等待握手超时回调，则取消
            if (handshakeTimeoutFuture != null) {
                handshakeTimeoutFuture.cancel(false);
                handshakeTimeoutFuture = null;
            }
            // 遍历 Transport 的 Filter，通知终止
            for (ServerTransportFilter filter : transportFilters) {
                filter.transportTerminated(attributes);
            }
            // 关闭 Transport，从集合中移除并通知终止
            transportClosed(transport);
        }


        /**
         * 流创建事件
         */
        @Override
        public void streamCreated(ServerStream stream, String methodName, Metadata headers) {
            Tag tag = PerfMark.createTag(methodName, stream.streamId());
            PerfMark.startTask("ServerTransportListener.streamCreated", tag);
            try {
                streamCreatedInternal(stream, methodName, headers, tag);
            } finally {
                PerfMark.stopTask("ServerTransportListener.streamCreated", tag);
            }
        }

        private void streamCreatedInternal(final ServerStream stream,
                                           final String methodName,
                                           final Metadata headers,
                                           final Tag tag) {
            final Executor wrappedExecutor;
            // This is a performance optimization that avoids the synchronization and queuing overhead
            // that comes with SerializingExecutor.
            // 这是一种性能优化，避免了 SerializingExecutor 附带的同步和排队开销
            // 如果是 directExecutor，则使用 SerializeReentrantCallsDirectExecutor，会使用当前线程直接执行
            if (executor == directExecutor()) {
                wrappedExecutor = new SerializeReentrantCallsDirectExecutor();
                stream.optimizeForDirectExecutor();
            } else {
                // 否则使用指定的 Executor 执行
                wrappedExecutor = new SerializingExecutor(executor);
            }

            // 如果 header 中包含消息编码的 key， 则根据 encode 类型查找相应的解压器
            if (headers.containsKey(MESSAGE_ENCODING_KEY)) {
                String encoding = headers.get(MESSAGE_ENCODING_KEY);
                // 根据 encode 类型查找解压器
                Decompressor decompressor = decompressorRegistry.lookupDecompressor(encoding);
                if (decompressor == null) {
                    stream.setListener(NOOP_LISTENER);
                    stream.close(Status.UNIMPLEMENTED.withDescription(String.format("Can't find decompressor for %s", encoding)), new Metadata());
                    return;
                }
                stream.setDecompressor(decompressor);
            }

            final StatsTraceContext statsTraceCtx = Preconditions.checkNotNull(stream.statsTraceContext(), "statsTraceCtx not present from stream");

            // 创建可以取消的上下文
            final Context.CancellableContext context = createContext(headers, statsTraceCtx);

            final Link link = PerfMark.linkOut();

            // 将回调调度到应用程序的执行程序上
            final JumpToApplicationThreadServerStreamListener jumpListener = new JumpToApplicationThreadServerStreamListener(wrappedExecutor, executor, stream, context, tag);
            stream.setListener(jumpListener);
            // Run in wrappedExecutor so jumpListener.setListener() is called before any callbacks
            // are delivered, including any errors. Callbacks can still be triggered, but they will be
            // queued.

            // 流创建任务处理
            final class StreamCreated extends ContextRunnable {
                StreamCreated() {
                    super(context);
                }

                @Override
                public void runInContext() {
                    PerfMark.startTask("ServerTransportListener$StreamCreated.startCall", tag);
                    PerfMark.linkIn(link);
                    try {
                        runInternal();
                    } finally {
                        PerfMark.stopTask("ServerTransportListener$StreamCreated.startCall", tag);
                    }
                }

                private void runInternal() {
                    ServerStreamListener listener = NOOP_LISTENER;
                    try {
                        // 根据方法名称获取方法定义
                        ServerMethodDefinition<?, ?> method = registry.lookupMethod(methodName);
                        // 如果没有则从回退的方法注册器中查找
                        if (method == null) {
                            method = fallbackRegistry.lookupMethod(methodName, stream.getAuthority());
                        }
                        // 如果没有则方法不存在，返回 UNIMPLEMENTED，关闭流，取消上下文
                        if (method == null) {
                            Status status = Status.UNIMPLEMENTED.withDescription("Method not found: " + methodName);
                            // TODO(zhangkun83): this error may be recorded by the tracer, and if it's kept in
                            // memory as a map whose key is the method name, this would allow a misbehaving
                            // client to blow up the server in-memory stats storage by sending large number of
                            // distinct unimplemented method
                            // names. (https://github.com/grpc/grpc-java/issues/2285)
                            stream.close(status, new Metadata());
                            context.cancel(null);
                            return;
                        }
                        // 如果方法存在，则开始调用
                        listener = startCall(stream, methodName, method, headers, context, statsTraceCtx, tag);
                    } catch (Throwable t) {
                        stream.close(Status.fromThrowable(t), new Metadata());
                        context.cancel(null);
                        throw t;
                    } finally {
                        jumpListener.setListener(listener);
                    }

                    // An extremely short deadline may expire before stream.setListener(jumpListener).
                    // This causes NPE as in issue: https://github.com/grpc/grpc-java/issues/6300
                    // Delay of setting cancellationListener to context will fix the issue.
                    final class ServerStreamCancellationListener implements Context.CancellationListener {
                        @Override
                        public void cancelled(Context context) {
                            Status status = statusFromCancelled(context);
                            if (DEADLINE_EXCEEDED.getCode().equals(status.getCode())) {
                                // This should rarely get run, since the client will likely cancel the stream
                                // before the timeout is reached.
                                stream.cancel(status);
                            }
                        }
                    }

                    context.addListener(new ServerStreamCancellationListener(), directExecutor());
                }
            }

            wrappedExecutor.execute(new StreamCreated());
        }

        /**
         * 创建可以取消的上下文
         */
        private Context.CancellableContext createContext(Metadata headers,
                                                         StatsTraceContext statsTraceCtx) {
            // 从 Header 中获取超时配置
            Long timeoutNanos = headers.get(TIMEOUT_KEY);

            Context baseContext = statsTraceCtx.serverFilterContext(rootContext)
                                               .withValue(io.grpc.InternalServer.SERVER_CONTEXT_KEY, ServerImpl.this);

            // 如果没有设置超时，则直接创建可取消的上下文
            if (timeoutNanos == null) {
                return baseContext.withCancellation();
            }

            // 如果有设置超时，则根据超时创建可取消的上下文
            Context.CancellableContext context = baseContext.withDeadline(Deadline.after(timeoutNanos, NANOSECONDS, ticker), transport.getScheduledExecutorService());

            return context;
        }

        /**
         * Never returns {@code null}.
         * 开始处理请求
         */
        private <ReqT, RespT> ServerStreamListener startCall(ServerStream stream,
                                                             String fullMethodName,
                                                             ServerMethodDefinition<ReqT, RespT> methodDef,
                                                             Metadata headers,
                                                             Context.CancellableContext context,
                                                             StatsTraceContext statsTraceCtx,
                                                             Tag tag) {
            // TODO(ejona86): should we update fullMethodName to have the canonical path of the method?
            // 记录开始处理请求
            statsTraceCtx.serverCallStarted(new ServerCallInfoImpl<>(methodDef.getMethodDescriptor(), // notify with original method descriptor
                    stream.getAttributes(),
                    stream.getAuthority()));

            // 从方法描述获取调用处理器
            ServerCallHandler<ReqT, RespT> handler = methodDef.getServerCallHandler();
            // 遍历拦截器，为处理器添加拦截器
            for (ServerInterceptor interceptor : interceptors) {
                handler = InternalServerInterceptors.interceptCallHandler(interceptor, handler);
            }
            // 使用添加了拦截器后的处理器创建新的方法定义
            ServerMethodDefinition<ReqT, RespT> interceptedDef = methodDef.withServerCallHandler(handler);

            // 如果 binlog 不为空，即需要记录binlog，则添加请求监听器和方法处理器记录 binlog
            ServerMethodDefinition<?, ?> wMethodDef = binlog == null ? interceptedDef : binlog.wrapMethodDefinition(interceptedDef);

            // 处理封装后的调用
            return startWrappedCall(fullMethodName, wMethodDef, stream, headers, context, tag);
        }

        /**
         * 开始处理请求
         *
         * @param fullMethodName 方法名称
         * @param methodDef      方法定义
         * @param stream         请求
         * @param headers        请求头
         * @param context        可取消的上下午我
         * @param tag            用于性能记录的 tag
         * @return 流监听器
         */
        private <WReqT, WRespT> ServerStreamListener startWrappedCall(String fullMethodName,
                                                                      ServerMethodDefinition<WReqT, WRespT> methodDef,
                                                                      ServerStream stream,
                                                                      Metadata headers,
                                                                      Context.CancellableContext context,
                                                                      Tag tag) {

            // 创建请求处理器
            ServerCallImpl<WReqT, WRespT> call = new ServerCallImpl<>(stream,
                    methodDef.getMethodDescriptor(),
                    headers,
                    context,
                    decompressorRegistry,
                    compressorRegistry,
                    serverCallTracer,
                    tag);

            // 调用方法处理器，真正调用实现逻辑的方法
            ServerCall.Listener<WReqT> listener = methodDef.getServerCallHandler().startCall(call, headers);
            if (listener == null) {
                throw new NullPointerException("startCall() returned a null listener for method " + fullMethodName);
            }

            // 根据调用监听器创建新的流监听器
            return call.newServerStreamListener(listener);
        }
    }

    @Override
    public InternalLogId getLogId() {
        return logId;
    }

    /**
     * 获取统计数据
     */
    @Override
    public ListenableFuture<ServerStats> getStats() {
        ServerStats.Builder builder = new ServerStats.Builder();
        for (InternalServer ts : transportServers) {
            // TODO(carl-mastrangelo): remove the list and just add directly.
            InternalInstrumented<SocketStats> stats = ts.getListenSocketStats();
            if (stats != null) {
                builder.addListenSockets(Collections.singletonList(stats));
            }
        }
        serverCallTracer.updateBuilder(builder);
        SettableFuture<ServerStats> ret = SettableFuture.create();
        ret.set(builder.build());
        return ret;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("logId", logId.getId())
                          .add("transportServers", transportServers)
                          .toString();
    }

    /**
     * 不做操作的流监听器
     */
    private static final class NoopListener implements ServerStreamListener {
        @Override
        public void messagesAvailable(MessageProducer producer) {
            InputStream message;
            while ((message = producer.next()) != null) {
                try {
                    message.close();
                } catch (IOException e) {
                    // Close any remaining messages
                    while ((message = producer.next()) != null) {
                        try {
                            message.close();
                        } catch (IOException ioException) {
                            // just log additional exceptions as we are already going to throw
                            log.log(Level.WARNING, "Exception closing stream", ioException);
                        }
                    }
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public void halfClosed() {
        }

        @Override
        public void closed(Status status) {
        }

        @Override
        public void onReady() {
        }
    }

    /**
     * Dispatches callbacks onto an application-provided executor and correctly propagates
     * exceptions.
     * 将回调调度到应用程序提供的执行程序上，并正确传播异常
     */
    @VisibleForTesting
    static final class JumpToApplicationThreadServerStreamListener implements ServerStreamListener {
        private final Executor callExecutor;
        private final Executor cancelExecutor;
        private final Context.CancellableContext context;
        private final ServerStream stream;
        private final Tag tag;
        // Only accessed from callExecutor.
        private ServerStreamListener listener;

        public JumpToApplicationThreadServerStreamListener(Executor executor,
                                                           Executor cancelExecutor,
                                                           ServerStream stream,
                                                           Context.CancellableContext context,
                                                           Tag tag) {
            this.callExecutor = executor;
            this.cancelExecutor = cancelExecutor;
            this.stream = stream;
            this.context = context;
            this.tag = tag;
        }

        /**
         * This call MUST be serialized on callExecutor to avoid races.
         * 此调用必须在callExecutor上进行序列化，以避免发生争用
         */
        private ServerStreamListener getListener() {
            if (listener == null) {
                throw new IllegalStateException("listener unset");
            }
            return listener;
        }

        @VisibleForTesting
        void setListener(ServerStreamListener listener) {
            Preconditions.checkNotNull(listener, "listener must not be null");
            Preconditions.checkState(this.listener == null, "Listener already set");
            this.listener = listener;
        }

        /**
         * Like {@link ServerCall#close(Status, Metadata)}, but thread-safe for internal use.
         */
        private void internalClose(Throwable t) {
            // TODO(ejona86): this is not thread-safe :)
            stream.close(Status.UNKNOWN.withCause(t), new Metadata());
        }

        @Override
        public void messagesAvailable(final MessageProducer producer) {
            PerfMark.startTask("ServerStreamListener.messagesAvailable", tag);
            final Link link = PerfMark.linkOut();

            final class MessagesAvailable extends ContextRunnable {

                MessagesAvailable() {
                    super(context);
                }

                @Override
                public void runInContext() {
                    PerfMark.startTask("ServerCallListener(app).messagesAvailable", tag);
                    PerfMark.linkIn(link);
                    try {
                        getListener().messagesAvailable(producer);
                    } catch (Throwable t) {
                        internalClose(t);
                        throw t;
                    } finally {
                        PerfMark.stopTask("ServerCallListener(app).messagesAvailable", tag);
                    }
                }
            }

            try {
                callExecutor.execute(new MessagesAvailable());
            } finally {
                PerfMark.stopTask("ServerStreamListener.messagesAvailable", tag);
            }
        }

        @Override
        public void halfClosed() {
            PerfMark.startTask("ServerStreamListener.halfClosed", tag);
            final Link link = PerfMark.linkOut();

            final class HalfClosed extends ContextRunnable {
                HalfClosed() {
                    super(context);
                }

                @Override
                public void runInContext() {
                    PerfMark.startTask("ServerCallListener(app).halfClosed", tag);
                    PerfMark.linkIn(link);
                    try {
                        getListener().halfClosed();
                    } catch (Throwable t) {
                        internalClose(t);
                        throw t;
                    } finally {
                        PerfMark.stopTask("ServerCallListener(app).halfClosed", tag);
                    }
                }
            }

            try {
                callExecutor.execute(new HalfClosed());
            } finally {
                PerfMark.stopTask("ServerStreamListener.halfClosed", tag);
            }
        }

        @Override
        public void closed(final Status status) {
            PerfMark.startTask("ServerStreamListener.closed", tag);
            try {
                closedInternal(status);
            } finally {
                PerfMark.stopTask("ServerStreamListener.closed", tag);
            }
        }

        private void closedInternal(final Status status) {
            // For cancellations, promptly inform any users of the context that their work should be
            // aborted. Otherwise, we can wait until pending work is done.
            if (!status.isOk()) {
                // The callExecutor might be busy doing user work. To avoid waiting, use an executor that
                // is not serializing.
                cancelExecutor.execute(new ContextCloser(context, status.getCause()));
            }
            final Link link = PerfMark.linkOut();

            final class Closed extends ContextRunnable {
                Closed() {
                    super(context);
                }

                @Override
                public void runInContext() {
                    PerfMark.startTask("ServerCallListener(app).closed", tag);
                    PerfMark.linkIn(link);
                    try {
                        getListener().closed(status);
                    } finally {
                        PerfMark.stopTask("ServerCallListener(app).closed", tag);
                    }
                }
            }

            callExecutor.execute(new Closed());
        }

        @Override
        public void onReady() {
            PerfMark.startTask("ServerStreamListener.onReady", tag);
            final Link link = PerfMark.linkOut();
            final class OnReady extends ContextRunnable {
                OnReady() {
                    super(context);
                }

                @Override
                public void runInContext() {
                    PerfMark.startTask("ServerCallListener(app).onReady", tag);
                    PerfMark.linkIn(link);
                    try {
                        getListener().onReady();
                    } catch (Throwable t) {
                        internalClose(t);
                        throw t;
                    } finally {
                        PerfMark.stopTask("ServerCallListener(app).onReady", tag);
                    }
                }
            }

            try {
                callExecutor.execute(new OnReady());
            } finally {
                PerfMark.stopTask("ServerStreamListener.onReady", tag);
            }
        }
    }

    @VisibleForTesting
    static final class ContextCloser implements Runnable {
        private final Context.CancellableContext context;
        private final Throwable cause;

        ContextCloser(Context.CancellableContext context, Throwable cause) {
            this.context = context;
            this.cause = cause;
        }

        @Override
        public void run() {
            context.cancel(cause);
        }
    }
}
