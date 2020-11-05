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
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.ForOverride;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.ChannelStats;
import io.grpc.InternalInstrumented;
import io.grpc.InternalLogId;
import io.grpc.InternalWithLogId;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

/**
 * Transports for a single {@link SocketAddress}.
 * 用于单个 SocketAddress 的 Transport
 */
@ThreadSafe
final class InternalSubchannel implements InternalInstrumented<ChannelStats>, TransportProvider {

    private final InternalLogId logId;
    private final String authority;
    private final String userAgent;
    private final BackoffPolicy.Provider backoffPolicyProvider;
    private final Callback callback;
    private final ClientTransportFactory transportFactory;
    private final ScheduledExecutorService scheduledExecutor;
    private final InternalChannelz channelz;
    private final CallTracer callsTracer;
    private final ChannelTracer channelTracer;
    private final ChannelLogger channelLogger;

    /**
     * All field must be mutated in the syncContext.
     */
    private final SynchronizationContext syncContext;

    /**
     * The index of the address corresponding to pendingTransport/activeTransport, or at beginning if
     * both are null.
     * pendingTransport/activeTransport 的地址序列，如果两个都是 null 则在开头
     *
     * <p>Note: any {@link Index#updateAddresses(List)} should also update {@link #addressGroups}.
     */
    private final Index addressIndex;

    /**
     * A volatile accessor to {@link Index#getAddressGroups()}. There are few methods ({@link
     * #getAddressGroups()} and {@link #toString()} access this value where they supposed to access
     * in the {@link #syncContext}. Ideally {@link Index#getAddressGroups()} can be volatile, so we
     * don't need to maintain this volatile accessor. Although, having this accessor can reduce
     * unnecessary volatile reads while it delivers clearer intention of why .
     * <p>
     * Index#getAddressGroups 的同步访问器，有很多方法如 getAddressGroups toString 通过 syncContext 访问这个值，
     * 理想的是 Index#getAddressGroups() 可以是同步的，所以不需要保持访问器同步，尽管通过这个访问器可以减少不必要的
     * 同步读，同时可以清楚地说明为什么
     */
    private volatile List<EquivalentAddressGroup> addressGroups;

    /**
     * The policy to control back off between reconnects. Non-{@code null} when a reconnect task is
     * scheduled.
     * 控制重新连接的控制策略，当调度了一个非空的重连任务时不为空
     */
    private BackoffPolicy reconnectPolicy;

    /**
     * Timer monitoring duration since entering CONNECTING state.
     * 进入 CONNECTING 状态后的时间监控器
     */
    private final Stopwatch connectingTimer;

    @Nullable
    private ScheduledHandle reconnectTask;

    @Nullable
    private ScheduledHandle shutdownDueToUpdateTask;

    @Nullable
    private ManagedClientTransport shutdownDueToUpdateTransport;

    /**
     * All transports that are not terminated. At the very least the value of {@link #activeTransport}
     * will be present, but previously used transports that still have streams or are stopping may
     * also be present.
     * 所有没有终止的 Transport 的集合，至少 activeTransport 会在里面，之前使用过的依然有流的或者处于停止中的也可能在里面
     */
    private final Collection<ConnectionClientTransport> transports = new ArrayList<>();

    // Must only be used from syncContext
    private final InUseStateAggregator<ConnectionClientTransport> inUseStateAggregator =
            new InUseStateAggregator<ConnectionClientTransport>() {
                @Override
                protected void handleInUse() {
                    callback.onInUse(InternalSubchannel.this);
                }

                @Override
                protected void handleNotInUse() {
                    callback.onNotInUse(InternalSubchannel.this);
                }
            };

    /**
     * The to-be active transport, which is not ready yet.
     * 准备激活的 Transport，即没有 ready 的
     */
    @Nullable
    private ConnectionClientTransport pendingTransport;

    /**
     * The transport for new outgoing requests. Non-null only in READY state.
     * 准备执行新的请求调用的 Transport，当处于 READY 状态时非空
     */
    @Nullable
    private volatile ManagedClientTransport activeTransport;

    // 连接状态
    private volatile ConnectivityStateInfo state = ConnectivityStateInfo.forNonError(IDLE);

    // 关闭原因
    private Status shutdownReason;

    InternalSubchannel(List<EquivalentAddressGroup> addressGroups,
                       String authority,
                       String userAgent,
                       BackoffPolicy.Provider backoffPolicyProvider,
                       ClientTransportFactory transportFactory,
                       ScheduledExecutorService scheduledExecutor,
                       Supplier<Stopwatch> stopwatchSupplier,
                       SynchronizationContext syncContext,
                       Callback callback,
                       InternalChannelz channelz,
                       CallTracer callsTracer,
                       ChannelTracer channelTracer,
                       InternalLogId logId,
                       ChannelLogger channelLogger) {

        Preconditions.checkNotNull(addressGroups, "addressGroups");
        Preconditions.checkArgument(!addressGroups.isEmpty(), "addressGroups is empty");
        checkListHasNoNulls(addressGroups, "addressGroups contains null entry");

        List<EquivalentAddressGroup> unmodifiableAddressGroups = Collections.unmodifiableList(new ArrayList<>(addressGroups));
        this.addressGroups = unmodifiableAddressGroups;
        this.addressIndex = new Index(unmodifiableAddressGroups);
        this.authority = authority;
        this.userAgent = userAgent;
        this.backoffPolicyProvider = backoffPolicyProvider;
        this.transportFactory = transportFactory;
        this.scheduledExecutor = scheduledExecutor;
        this.connectingTimer = stopwatchSupplier.get();
        this.syncContext = syncContext;
        this.callback = callback;
        this.channelz = channelz;
        this.callsTracer = callsTracer;
        this.channelTracer = Preconditions.checkNotNull(channelTracer, "channelTracer");
        this.logId = Preconditions.checkNotNull(logId, "logId");
        this.channelLogger = Preconditions.checkNotNull(channelLogger, "channelLogger");
    }

    ChannelLogger getChannelLogger() {
        return channelLogger;
    }

    /**
     * 返回一个 READY 的 Transport，用于创建新的流
     *
     * @return
     */
    @Override
    public ClientTransport obtainActiveTransport() {
        // 如果有活跃的，则直接返回
        ClientTransport savedTransport = activeTransport;
        if (savedTransport != null) {
            return savedTransport;
        }
        // 提交一个新的开始 Transport 的任务
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
                // 如果处于 IDLE 状态，则记录日志，并重新连接
                if (state.getState() == IDLE) {
                    channelLogger.log(ChannelLogLevel.INFO, "CONNECTING as requested");
                    gotoNonErrorState(CONNECTING);
                    // 开始新的 Transport，建立连接
                    startNewTransport();
                }
            }
        });
        return null;
    }

    /**
     * Returns a READY transport if there is any, without trying to connect.
     * 如果有 READY 的 Transport 则返回
     */
    @Nullable
    ClientTransport getTransport() {
        return activeTransport;
    }

    /**
     * Returns the authority string associated with this Subchannel.
     * 返回关联的 Subchannel 的服务名
     */
    String getAuthority() {
        return authority;
    }

    /**
     * 开始一个新的 Transport
     */
    private void startNewTransport() {
        syncContext.throwIfNotInThisSynchronizationContext();

        Preconditions.checkState(reconnectTask == null, "Should have no reconnectTask scheduled");

        // 如果是第一个地址，则重置计时器
        if (addressIndex.isAtBeginning()) {
            connectingTimer.reset().start();
        }
        // 获取当前地址
        SocketAddress address = addressIndex.getCurrentAddress();

        // 如果是代理的地址，则使用真正被代理的地址
        HttpConnectProxiedSocketAddress proxiedAddr = null;
        if (address instanceof HttpConnectProxiedSocketAddress) {
            proxiedAddr = (HttpConnectProxiedSocketAddress) address;
            address = proxiedAddr.getTargetAddress();
        }

        Attributes currentEagAttributes = addressIndex.getCurrentEagAttributes();
        String eagChannelAuthority = currentEagAttributes.get(EquivalentAddressGroup.ATTR_AUTHORITY_OVERRIDE);

        // 设置地址，UserAgent 等信息
        ClientTransportFactory.ClientTransportOptions options =
                new ClientTransportFactory.ClientTransportOptions()
                        .setAuthority(eagChannelAuthority != null ? eagChannelAuthority : authority)
                        .setEagAttributes(currentEagAttributes)
                        .setUserAgent(userAgent)
                        .setHttpConnectProxiedSocketAddress(proxiedAddr);

        TransportLogger transportLogger = new TransportLogger();
        // In case the transport logs in the constructor, use the subchannel logId
        transportLogger.logId = getLogId();

        // 创建 Transport，并使用 CallTracingTransport 封装
        ConnectionClientTransport transport = new CallTracingTransport(transportFactory.newClientTransport(address, options, transportLogger), callsTracer);
        transportLogger.logId = transport.getLogId();

        // 将 Transport 添加到 channel 中
        channelz.addClientSocket(transport);
        pendingTransport = transport;
        transports.add(transport);

        // 创建 Transport 监听器，建立连接
        Runnable runnable = transport.start(new TransportListener(transport, address));
        if (runnable != null) {
            syncContext.executeLater(runnable);
        }
        channelLogger.log(ChannelLogLevel.INFO, "Started transport {0}", transportLogger.logId);
    }

    /**
     * Only called after all addresses attempted and failed (TRANSIENT_FAILURE).
     * 只有当所有的地址尝试都失败后调用
     *
     * @param status the causal status when the channel begins transition to
     *               TRANSIENT_FAILURE.
     *               造成 Channel 转为 TRANSIENT_FAILURE 的状态
     */
    private void scheduleBackoff(final Status status) {
        syncContext.throwIfNotInThisSynchronizationContext();

        class EndOfCurrentBackoff implements Runnable {
            @Override
            public void run() {
                // 进入 CONNECTING 状态并开始一个新的 Transport
                reconnectTask = null;
                channelLogger.log(ChannelLogLevel.INFO, "CONNECTING after backoff");
                gotoNonErrorState(CONNECTING);
                startNewTransport();
            }
        }

        // 将状态改为 TRANSIENT_FAILURE
        gotoState(ConnectivityStateInfo.forTransientFailure(status));

        // 如果重新连接的策略是空的，则获取
        if (reconnectPolicy == null) {
            reconnectPolicy = backoffPolicyProvider.get();
        }
        // 延迟执行时间
        long delayNanos = reconnectPolicy.nextBackoffNanos() - connectingTimer.elapsed(TimeUnit.NANOSECONDS);
        channelLogger.log(ChannelLogLevel.INFO, "TRANSIENT_FAILURE ({0}). Will reconnect after {1} ns", printShortStatus(status), delayNanos);

        Preconditions.checkState(reconnectTask == null, "previous reconnectTask is not done");
        // 调度重新创建任务，
        reconnectTask = syncContext.schedule(new EndOfCurrentBackoff(), delayNanos, TimeUnit.NANOSECONDS, scheduledExecutor);
    }

    /**
     * Immediately attempt to reconnect if the current state is TRANSIENT_FAILURE. Otherwise this
     * method has no effect.
     * 如果当前的状态是 TRANSIENT_FAILURE，则立即尝试重新连接，其他状态没有任何效果
     */
    void resetConnectBackoff() {
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
                if (state.getState() != TRANSIENT_FAILURE) {
                    return;
                }
                // 取消重新连接的任务
                cancelReconnectTask();
                channelLogger.log(ChannelLogLevel.INFO, "CONNECTING; backoff interrupted");
                // 状态修改为 CONNECTING
                gotoNonErrorState(CONNECTING);
                // 开始一个新的 Transport
                startNewTransport();
            }
        });
    }

    /**
     * 更新连接状态
     *
     * @param newState
     */
    private void gotoNonErrorState(final ConnectivityState newState) {
        syncContext.throwIfNotInThisSynchronizationContext();

        gotoState(ConnectivityStateInfo.forNonError(newState));
    }

    /**
     * 当状态发生变化时修改状态
     *
     * @param newState 新的状态
     */
    private void gotoState(final ConnectivityStateInfo newState) {
        syncContext.throwIfNotInThisSynchronizationContext();

        if (state.getState() != newState.getState()) {
            Preconditions.checkState(state.getState() != SHUTDOWN, "Cannot transition out of SHUTDOWN to " + newState);
            state = newState;
            callback.onStateChange(InternalSubchannel.this, newState);
        }
    }

    /**
     * Replaces the existing addresses, avoiding unnecessary reconnects.
     * 替换已经存在的地址，防止不必要的重新连接
     * 1. 替换 Group
     * 2. 如果是 READY 或者 CONNECTING 状态，地址不存在，则将 Transport 改为 IDLE 状态，存在则 SHUTDOWN 并创建一个新的
     * 3. 如果有计划关闭的任务，则取消任务直接关闭，然后提交一个新的关闭任务(因为关闭期间发生调用，可能存在失败情况)
     */
    public void updateAddresses(final List<EquivalentAddressGroup> newAddressGroups) {
        Preconditions.checkNotNull(newAddressGroups, "newAddressGroups");
        checkListHasNoNulls(newAddressGroups, "newAddressGroups contains null entry");
        Preconditions.checkArgument(!newAddressGroups.isEmpty(), "newAddressGroups is empty");

        syncContext.execute(new Runnable() {
            @Override
            public void run() {
                List<EquivalentAddressGroup> newImmutableAddressGroups = Collections.unmodifiableList(new ArrayList<>(newAddressGroups));
                ManagedClientTransport savedTransport = null;
                // 获取当前地址
                SocketAddress previousAddress = addressIndex.getCurrentAddress();
                // 更新地址集合
                addressIndex.updateGroups(newImmutableAddressGroups);
                addressGroups = newImmutableAddressGroups;
                // 如果是 READY 或者 CONNECTING 状态
                if (state.getState() == READY || state.getState() == CONNECTING) {
                    // 如果这个地址不存在，
                    if (!addressIndex.seekTo(previousAddress)) {
                        // Forced to drop the connection
                        // 如果是 READY 状态，则将状态改为空闲
                        if (state.getState() == READY) {
                            savedTransport = activeTransport;
                            activeTransport = null;
                            addressIndex.reset();
                            gotoNonErrorState(IDLE);
                        } else {
                            //如果存在，则以 UNAVAILABLE 状态关闭，并开始一个新的 Transport
                            pendingTransport.shutdown(Status.UNAVAILABLE.withDescription("InternalSubchannel closed pending transport due to address change"));
                            pendingTransport = null;
                            addressIndex.reset();
                            startNewTransport();
                        }
                    }
                }

                // 如果之前的 active 的 Transport 不为 null
                if (savedTransport != null) {
                    // 如果关闭的任务不为空，则将需要关闭的 Transport 直接关闭，并取消关闭任务
                    if (shutdownDueToUpdateTask != null) {
                        // Keeping track of multiple shutdown tasks adds complexity, and shouldn't generally be
                        // necessary. This transport has probably already had plenty of time.
                        shutdownDueToUpdateTransport.shutdown(Status.UNAVAILABLE.withDescription("InternalSubchannel closed transport early due to address change"));
                        shutdownDueToUpdateTask.cancel();
                        shutdownDueToUpdateTask = null;
                        shutdownDueToUpdateTransport = null;
                    }

                    // Avoid needless RPC failures by delaying the shutdown. See
                    // https://github.com/grpc/grpc-java/issues/2562
                    shutdownDueToUpdateTransport = savedTransport;
                    // 提交关闭任务
                    shutdownDueToUpdateTask = syncContext.schedule(
                            new Runnable() {
                                @Override
                                public void run() {
                                    ManagedClientTransport transport = shutdownDueToUpdateTransport;
                                    shutdownDueToUpdateTask = null;
                                    shutdownDueToUpdateTransport = null;
                                    transport.shutdown(Status.UNAVAILABLE.withDescription("InternalSubchannel closed transport due to address change"));
                                }
                            },
                            ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS,
                            TimeUnit.SECONDS,
                            scheduledExecutor);
                }
            }
        });
    }

    /**
     * 关闭 Subchannel
     *
     * @param reason 关闭的状态
     */
    public void shutdown(final Status reason) {
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
                ManagedClientTransport savedActiveTransport;
                ConnectionClientTransport savedPendingTransport;
                // 如果已经是 SHUTDOWN 状态，则不做操作
                if (state.getState() == SHUTDOWN) {
                    return;
                }
                shutdownReason = reason;
                savedActiveTransport = activeTransport;
                savedPendingTransport = pendingTransport;
                activeTransport = null;
                pendingTransport = null;

                // 进入 SHUTDOWN 状态
                gotoNonErrorState(SHUTDOWN);
                // 重置 Index
                addressIndex.reset();
                // 如果 Transport 集合是空的
                if (transports.isEmpty()) {
                    // 执行回调(当所有的 Transport 都终止的时候才会调用回调)
                    handleTermination();
                }  // else: the callback will be run once all transports have been terminated

                // 取消重连任务
                cancelReconnectTask();
                // 如果有关闭的任务，则取消并关闭 Transport
                if (shutdownDueToUpdateTask != null) {
                    shutdownDueToUpdateTask.cancel();
                    shutdownDueToUpdateTransport.shutdown(reason);
                    shutdownDueToUpdateTask = null;
                    shutdownDueToUpdateTransport = null;
                }
                // 如果有 active 的Transport，则关闭
                if (savedActiveTransport != null) {
                    savedActiveTransport.shutdown(reason);
                }
                // 如果有等待的 Transport，则关闭
                if (savedPendingTransport != null) {
                    savedPendingTransport.shutdown(reason);
                }
            }
        });
    }

    @Override
    public String toString() {
        // addressGroupsCopy being a little stale is fine, just avoid calling toString with the lock
        // since there may be many addresses.
        return MoreObjects.toStringHelper(this)
                          .add("logId", logId.getId())
                          .add("addressGroups", addressGroups)
                          .toString();
    }

    /**
     * 执行终止回调
     */
    private void handleTermination() {
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
                channelLogger.log(ChannelLogLevel.INFO, "Terminated");
                // 执行终止回调，如关闭 Subchannel，OobChannel 等
                callback.onTerminated(InternalSubchannel.this);
            }
        });
    }

    /**
     * 修改 Transport 的使用中状态
     */
    private void handleTransportInUseState(final ConnectionClientTransport transport, final boolean inUse) {
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
                inUseStateAggregator.updateObjectInUse(transport, inUse);
            }
        });
    }

    /**
     * 立即关闭
     *
     * @param reason 关闭状态
     */
    void shutdownNow(final Status reason) {
        shutdown(reason);
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
                // 复制 Transport 集合，循环调用立即关闭
                Collection<ManagedClientTransport> transportsCopy = new ArrayList<ManagedClientTransport>(transports);
                for (ManagedClientTransport transport : transportsCopy) {
                    transport.shutdownNow(reason);
                }
            }
        });
    }

    List<EquivalentAddressGroup> getAddressGroups() {
        return addressGroups;
    }

    /**
     * 取消重连的任务
     */
    private void cancelReconnectTask() {
        syncContext.throwIfNotInThisSynchronizationContext();

        if (reconnectTask != null) {
            reconnectTask.cancel();
            reconnectTask = null;
            reconnectPolicy = null;
        }
    }

    @Override
    public InternalLogId getLogId() {
        return logId;
    }

    /**
     * 获取调用数据
     */
    @Override
    public ListenableFuture<ChannelStats> getStats() {
        final SettableFuture<ChannelStats> channelStatsFuture = SettableFuture.create();
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
                ChannelStats.Builder builder = new ChannelStats.Builder();
                List<EquivalentAddressGroup> addressGroupsSnapshot = addressIndex.getGroups();
                List<InternalWithLogId> transportsSnapshot = new ArrayList<InternalWithLogId>(transports);
                builder.setTarget(addressGroupsSnapshot.toString()).setState(getState());
                builder.setSockets(transportsSnapshot);
                callsTracer.updateBuilder(builder);
                channelTracer.updateBuilder(builder);
                channelStatsFuture.set(builder.build());
            }
        });
        return channelStatsFuture;
    }

    /**
     * 获取连接状态
     */
    ConnectivityState getState() {
        return state.getState();
    }

    private static void checkListHasNoNulls(List<?> list, String msg) {
        for (Object item : list) {
            Preconditions.checkNotNull(item, msg);
        }
    }

    /**
     * Listener for real transports.
     * 真正的 Transport 的监听器
     */
    private class TransportListener implements ManagedClientTransport.Listener {

        final ConnectionClientTransport transport;
        final SocketAddress address;

        boolean shutdownInitiated = false;

        TransportListener(ConnectionClientTransport transport, SocketAddress address) {
            this.transport = transport;
            this.address = address;
        }

        /**
         * Transport 已经 READY
         */
        @Override
        public void transportReady() {
            channelLogger.log(ChannelLogLevel.INFO, "READY");

            syncContext.execute(new Runnable() {
                @Override
                public void run() {
                    reconnectPolicy = null;
                    // 如果是 SHUTDOWN 则关闭 Transport
                    if (shutdownReason != null) {
                        // activeTransport should have already been set to null by shutdown(). We keep it null.
                        Preconditions.checkState(activeTransport == null, "Unexpected non-null activeTransport");
                        transport.shutdown(shutdownReason);
                    } else if (pendingTransport == transport) {
                        activeTransport = transport;
                        pendingTransport = null;
                        // 如果是等待 READY，则更新状态为 READY
                        gotoNonErrorState(READY);
                    }
                }
            });
        }

        /**
         * 修改 Transport 使用中的状态
         */
        @Override
        public void transportInUse(boolean inUse) {
            handleTransportInUseState(transport, inUse);
        }

        /**
         * 关闭 Transport
         * 如果已经是 SHUTDOWN，则不做操作
         * 如果已经是 READY 或者 active 状态，则进入空闲状态
         * 如果是等待调度，则创建新的
         *
         * @param s the reason for the shutdown. 关闭的原因
         */
        @Override
        public void transportShutdown(final Status s) {
            channelLogger.log(ChannelLogLevel.INFO, "{0} SHUTDOWN with {1}", transport.getLogId(), printShortStatus(s));
            shutdownInitiated = true;

            syncContext.execute(new Runnable() {
                @Override
                public void run() {
                    // 如果已经是 SHUTDOWN 状态，则返回
                    if (state.getState() == SHUTDOWN) {
                        return;
                    }

                    // 如果已经是 READY，则重置地址的序列，进入空闲状态
                    if (activeTransport == transport) {
                        activeTransport = null;
                        addressIndex.reset();
                        gotoNonErrorState(IDLE);
                    } else if (pendingTransport == transport) {
                        // 如果是等待状态，则增加 index
                        Preconditions.checkState(state.getState() == CONNECTING, "Expected state is CONNECTING, actual state is %s", state.getState());
                        addressIndex.increment();
                        // Continue reconnect if there are still addresses to try.
                        // 如果是无效的，则重置并修改状态
                        if (!addressIndex.isValid()) {
                            pendingTransport = null;
                            addressIndex.reset();
                            // Initiate backoff
                            // Transition to TRANSIENT_FAILURE
                            // 将当前的 Transport 状态改为 TRANSIENT_FAILURE，重新调度新的 Transport
                            scheduleBackoff(s);
                        } else {
                            // 如果是有效的，则开始一个新的
                            startNewTransport();
                        }
                    }
                }
            });
        }

        /**
         * Transport 终止
         */
        @Override
        public void transportTerminated() {
            Preconditions.checkState(shutdownInitiated, "transportShutdown() must be called before transportTerminated().");

            channelLogger.log(ChannelLogLevel.INFO, "{0} Terminated", transport.getLogId());
            // 从 channelz 中移除
            channelz.removeClientSocket(transport);
            // 修改使用中状态
            handleTransportInUseState(transport, false);
            syncContext.execute(new Runnable() {
                @Override
                public void run() {
                    // 从接种中移除
                    transports.remove(transport);
                    if (state.getState() == SHUTDOWN && transports.isEmpty()) {
                        // 执行终止的回调
                        handleTermination();
                    }
                }
            });
        }
    }

    // All methods are called in syncContext
    // Subchannel 操作回调
    abstract static class Callback {
        /**
         * Called when the subchannel is terminated, which means it's shut down and all transports
         * have been terminated.
         * 当 Subchannel 关闭时调用，意味着 Subchannel 关闭，所有的 Transport 被终止
         */
        @ForOverride
        void onTerminated(InternalSubchannel is) {
        }

        /**
         * Called when the subchannel's connectivity state has changed.
         * 当 Subchannel 连接状态变化时调用
         */
        @ForOverride
        void onStateChange(InternalSubchannel is, ConnectivityStateInfo newState) {
        }

        /**
         * Called when the subchannel's in-use state has changed to true, which means at least one
         * transport is in use.
         * 当 Subchannel in-use 状态变为 true 时调用，意味着至少有一个 Transport 被使用
         */
        @ForOverride
        void onInUse(InternalSubchannel is) {
        }

        /**
         * Called when the subchannel's in-use state has changed to false, which means no transport is
         * in use.
         * 当 Subchannel in-use 状态变为 false 时调用，意味着 Transport 不再使用
         */
        @ForOverride
        void onNotInUse(InternalSubchannel is) {
        }
    }

    /**
     * 用于记录调用的代理 Transport
     */
    @VisibleForTesting
    static final class CallTracingTransport extends ForwardingConnectionClientTransport {
        private final ConnectionClientTransport delegate;
        private final CallTracer callTracer;

        private CallTracingTransport(ConnectionClientTransport delegate, CallTracer callTracer) {
            this.delegate = delegate;
            this.callTracer = callTracer;
        }

        @Override
        protected ConnectionClientTransport delegate() {
            return delegate;
        }

        /**
         * 创建新的流，用于给服务端发送消息
         *
         * @param method      调用的方法信息
         * @param headers     请求 header 信息
         * @param callOptions 调用参数
         * @return 客户端流
         */
        @Override
        public ClientStream newStream(MethodDescriptor<?, ?> method,
                                      Metadata headers,
                                      CallOptions callOptions) {
            // 先调用 io.grpc.internal.CallCredentialsApplyingTransportFactory.CallCredentialsApplyingTransport.newStream 添加鉴权信息
            // 然后调用 io.grpc.netty.NettyClientTransport.newStream
            final ClientStream streamDelegate = super.newStream(method, headers, callOptions);

            // 返回 ForwardingClientStream 包装后的 ClientStream，用于统计
            return new ForwardingClientStream() {
                @Override
                protected ClientStream delegate() {
                    return streamDelegate;
                }

                /**
                 * 开始流
                 */
                @Override
                public void start(final ClientStreamListener listener) {
                    callTracer.reportCallStarted();
                    // 创建用于统计的监听器
                    super.start(new ForwardingClientStreamListener() {
                        @Override
                        protected ClientStreamListener delegate() {
                            return listener;
                        }

                        @Override
                        public void closed(Status status, Metadata trailers) {
                            callTracer.reportCallEnded(status.isOk());
                            super.closed(status, trailers);
                        }

                        @Override
                        public void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
                            callTracer.reportCallEnded(status.isOk());
                            super.closed(status, rpcProgress, trailers);
                        }
                    });
                }
            };
        }
    }

    /**
     * Index as in 'i', the pointer to an entry. Not a "search index."
     * 指向实体的序列，不是查询索引
     */
    @VisibleForTesting
    static final class Index {
        // 地址集合
        private List<EquivalentAddressGroup> addressGroups;
        // 集合的 index
        private int groupIndex;
        // 地址的 index
        private int addressIndex;

        /**
         * 根据地址集合构建 index
         */
        public Index(List<EquivalentAddressGroup> groups) {
            this.addressGroups = groups;
        }

        /**
         * 是否有效
         */
        public boolean isValid() {
            // addressIndex will never be invalid
            return groupIndex < addressGroups.size();
        }

        /**
         * 是否在开头
         */
        public boolean isAtBeginning() {
            return groupIndex == 0 && addressIndex == 0;
        }

        /**
         * 增加序列
         */
        public void increment() {
            EquivalentAddressGroup group = addressGroups.get(groupIndex);
            addressIndex++;
            // 当当前组的地址超出地址集合大小，则使用下一个组，并把地址序列置为 0
            if (addressIndex >= group.getAddresses().size()) {
                groupIndex++;
                addressIndex = 0;
            }
        }

        /**
         * 重置
         */
        public void reset() {
            groupIndex = 0;
            addressIndex = 0;
        }

        /**
         * 获取当前的地址
         */
        public SocketAddress getCurrentAddress() {
            return addressGroups.get(groupIndex).getAddresses().get(addressIndex);
        }

        /**
         * 获取当前组的地址
         */
        public Attributes getCurrentEagAttributes() {
            return addressGroups.get(groupIndex).getAttributes();
        }

        /**
         * 获取组
         */
        public List<EquivalentAddressGroup> getGroups() {
            return addressGroups;
        }

        /**
         * Update to new groups, resetting the current index.
         * 更新组，重置地址
         */
        public void updateGroups(List<EquivalentAddressGroup> newGroups) {
            addressGroups = newGroups;
            reset();
        }

        /**
         * Returns false if the needle was not found and the current index was left unchanged.
         * 如果找不到指针，并且当前索引保持不变，则返回false，用于判断指定地址是否在集合中
         */
        public boolean seekTo(SocketAddress needle) {
            for (int i = 0; i < addressGroups.size(); i++) {
                EquivalentAddressGroup group = addressGroups.get(i);
                int j = group.getAddresses().indexOf(needle);
                if (j == -1) {
                    continue;
                }
                this.groupIndex = i;
                this.addressIndex = j;
                return true;
            }
            return false;
        }
    }

    /**
     * 输出状态的 CODE 和描述
     */
    private String printShortStatus(Status status) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(status.getCode());
        if (status.getDescription() != null) {
            buffer.append("(").append(status.getDescription()).append(")");
        }
        return buffer.toString();
    }

    /**
     * Transport 日志
     */
    @VisibleForTesting
    static final class TransportLogger extends ChannelLogger {
        // Changed just after construction to break a cyclic dependency.
        InternalLogId logId;

        @Override
        public void log(ChannelLogLevel level, String message) {
            ChannelLoggerImpl.logOnly(logId, level, message);
        }

        @Override
        public void log(ChannelLogLevel level, String messageFormat, Object... args) {
            ChannelLoggerImpl.logOnly(logId, level, messageFormat, args);
        }
    }
}
