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

package io.grpc.services;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ClientCall;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Factory;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.ForwardingSubchannel;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;

/**
 * Wraps a {@link LoadBalancer} and implements the client-side health-checking
 * (https://github.com/grpc/proposal/blob/master/A17-client-side-health-checking.md).  The
 * Subchannel received by the states wrapped LoadBalancer will be determined by health-checking.
 * 封装 LoadBalancer 并实现客户端的健康检查，会根据健康检查的结果返回决定是否返回 Subchannel
 *
 * <p>Note the original LoadBalancer must call {@code Helper.createSubchannel()} from the
 * SynchronizationContext, or it will throw.
 * 注意原始的 LoadBalancer 调用 Helper.createSubchannel() 必须通过 SynchronizationContext，否则会抛出异常
 */
final class HealthCheckingLoadBalancerFactory extends Factory {

    private static final Logger logger = Logger.getLogger(HealthCheckingLoadBalancerFactory.class.getName());

    private final Factory delegateFactory;
    private final BackoffPolicy.Provider backoffPolicyProvider;
    private final Supplier<Stopwatch> stopwatchSupplier;

    /**
     * 创建工厂
     */
    public HealthCheckingLoadBalancerFactory(Factory delegateFactory,
                                             BackoffPolicy.Provider backoffPolicyProvider,
                                             Supplier<Stopwatch> stopwatchSupplier) {
        this.delegateFactory = checkNotNull(delegateFactory, "delegateFactory");
        this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
        this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    }

    /**
     * 创建新的 LoadBalancer
     */
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
        // 代理 Helper
        HelperImpl wrappedHelper = new HelperImpl(helper);
        // 创建 LoadBalancer
        LoadBalancer delegateBalancer = delegateFactory.newLoadBalancer(wrappedHelper);
        return new HealthCheckingLoadBalancer(wrappedHelper, delegateBalancer);
    }

    /**
     * Helper
     */
    private final class HelperImpl extends ForwardingLoadBalancerHelper {
        private final Helper delegate;
        private final SynchronizationContext syncContext;

        @Nullable
        String healthCheckedService;

        final HashSet<HealthCheckState> hcStates = new HashSet<>();

        /**
         * 代理的 Helper
         *
         * @param delegate 被代理的 Helper
         */
        HelperImpl(Helper delegate) {
            this.delegate = checkNotNull(delegate, "delegate");
            this.syncContext = checkNotNull(delegate.getSynchronizationContext(), "syncContext");
        }

        @Override
        protected Helper delegate() {
            return delegate;
        }

        /**
         * 创建 Subchannel
         *
         * @param args 参数
         * @return Subchannel
         */
        @Override
        public Subchannel createSubchannel(CreateSubchannelArgs args) {
            // HealthCheckState is not thread-safe, we are requiring the original LoadBalancer calls
            // createSubchannel() from the SynchronizationContext.
            // HealthCheckState 不是线程安全的，要求原始的 LoadBalancer 通过 SynchronizationContext 调用 createSubchannel()
            syncContext.throwIfNotInThisSynchronizationContext();

            // 调用父类创建 Subchannel
            Subchannel originalSubchannel = super.createSubchannel(args);
            // 根据 Subchannel 创建状态监听器
            HealthCheckState hcState = new HealthCheckState(this, originalSubchannel, syncContext, delegate.getScheduledExecutorService());
            hcStates.add(hcState);

            // 创建代理的 Subchannel
            Subchannel subchannel = new SubchannelImpl(originalSubchannel, hcState);
            if (healthCheckedService != null) {
                hcState.setServiceName(healthCheckedService);
            }
            return subchannel;
        }

        /**
         * 配置服务健康检查
         *
         * @param service 服务名
         */
        void setHealthCheckedService(@Nullable String service) {
            healthCheckedService = service;
            // 遍历所有的 Subchannel 状态监听器，设置服务名
            for (HealthCheckState hcState : hcStates) {
                hcState.setServiceName(service);
            }
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
        }
    }

    /**
     * Subchannel 代理
     */
    @VisibleForTesting
    static final class SubchannelImpl extends ForwardingSubchannel {

        // 代理
        final Subchannel delegate;
        // Subchannel 状态监听器
        final HealthCheckState hcState;

        SubchannelImpl(Subchannel delegate, HealthCheckState hcState) {
            this.delegate = checkNotNull(delegate, "delegate");
            this.hcState = checkNotNull(hcState, "hcState");
        }

        @Override
        protected Subchannel delegate() {
            return delegate;
        }

        @Override
        public void start(final SubchannelStateListener listener) {
            // 检查是否初始化了
            hcState.init(listener);
            // 调用被代理的初始化方法
            delegate().start(hcState);
        }
    }

    /**
     * 支持健康检查的 LoadBalancer
     */
    private static final class HealthCheckingLoadBalancer extends ForwardingLoadBalancer {

        final LoadBalancer delegate;
        final HelperImpl helper;
        final SynchronizationContext syncContext;
        final ScheduledExecutorService timerService;

        HealthCheckingLoadBalancer(HelperImpl helper, LoadBalancer delegate) {
            this.helper = checkNotNull(helper, "helper");
            this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
            this.timerService = checkNotNull(helper.getScheduledExecutorService(), "timerService");
            this.delegate = checkNotNull(delegate, "delegate");
        }

        @Override
        protected LoadBalancer delegate() {
            return delegate;
        }

        /**
         * 处理地址
         *
         * @param resolvedAddresses 获取的地址
         */
        @Override
        public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
            // 获取健康检查配置
            Map<String, ?> healthCheckingConfig = resolvedAddresses.getAttributes()
                                                                   .get(LoadBalancer.ATTR_HEALTH_CHECKING_CONFIG);
            // 获取服务的健康检查配置
            String serviceName = ServiceConfigUtil.getHealthCheckedServiceName(healthCheckingConfig);

            // 配置服务健康检查
            helper.setHealthCheckedService(serviceName);
            // 调用被代理的类处理地址
            super.handleResolvedAddresses(resolvedAddresses);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
        }
    }


    /**
     * Subchannel 状态监听器
     */
    // All methods are run from syncContext
    private final class HealthCheckState implements SubchannelStateListener {

        private final Runnable retryTask = new Runnable() {
            @Override
            public void run() {
                startRpc();
            }
        };

        private final SynchronizationContext syncContext;
        private final ScheduledExecutorService timerService;
        private final HelperImpl helperImpl;
        private final Subchannel subchannel;
        private final ChannelLogger subchannelLogger;
        private SubchannelStateListener stateListener;

        // Set when RPC started. Cleared when the RPC has closed or abandoned.
        // 请求监听器，当请求开始的时候设置，被关闭或者禁止的时候清除
        @Nullable
        private HcStream activeRpc;

        // The service name that should be used for health checking
        // 被用于健康检查的服务名
        private String serviceName;

        private BackoffPolicy backoffPolicy;

        // The state from the underlying Subchannel
        // 底层的 Subchannel 的状态
        private ConnectivityStateInfo rawState = ConnectivityStateInfo.forNonError(IDLE);

        // The state concluded from health checking
        // 健康检查的结论
        private ConnectivityStateInfo concludedState = ConnectivityStateInfo.forNonError(IDLE);

        // true if a health check stream should be kept.  When true, either there is an active RPC, or a
        // retry is pending.
        // 如果健康检查应当保持，则为 true，当为 true 的时候，有一个活跃的请求，或者等待重试的请求
        private boolean running;

        // true if server returned UNIMPLEMENTED
        // 如果 server 返回 UNIMPLEMENTED 则为 true
        private boolean disabled;
        private ScheduledHandle retryTimer;

        HealthCheckState(HelperImpl helperImpl,
                         Subchannel subchannel,
                         SynchronizationContext syncContext,
                         ScheduledExecutorService timerService) {
            this.helperImpl = checkNotNull(helperImpl, "helperImpl");
            this.subchannel = checkNotNull(subchannel, "subchannel");
            this.subchannelLogger = checkNotNull(subchannel.getChannelLogger(), "subchannelLogger");
            this.syncContext = checkNotNull(syncContext, "syncContext");
            this.timerService = checkNotNull(timerService, "timerService");
        }

        /**
         * 检查 Subchannel 是否初始化
         *
         * @param listener Subchannel 监听器
         */
        void init(SubchannelStateListener listener) {
            checkState(this.stateListener == null, "init() already called");
            this.stateListener = checkNotNull(listener, "listener");
        }

        /**
         * 为服务配置健康检查
         *
         * @param newServiceName 服务名
         */
        void setServiceName(@Nullable String newServiceName) {
            if (Objects.equal(newServiceName, serviceName)) {
                return;
            }
            serviceName = newServiceName;
            // If service name has changed while there is active RPC, cancel it so that
            // a new call will be made with the new name.
            // 如果在 RPC 请求期间服务名称更改，请取消该服务，以便用新名称进行新的调用
            String cancelMsg = serviceName == null ? "Health check disabled by service config"
                    : "Switching to new service name: " + newServiceName;

            // 停止调用
            stopRpc(cancelMsg);
            // 调整健康检查
            adjustHealthCheck();
        }

        /**
         * 更新 Subchannel 的状态
         *
         * @param rawState 新的状态
         */
        @Override
        public void onSubchannelState(ConnectivityStateInfo rawState) {
            // 如果当前的状态是 READY，且新的状态不是 READY，则更新 disabled 为 false
            if (Objects.equal(this.rawState.getState(), READY)
                    && !Objects.equal(rawState.getState(), READY)) {
                // A connection was lost.  We will reset disabled flag because health check
                // may be available on the new connection.
                // 断开连接，将重置已禁用标志，因为健康检查在新连接上可能可用
                disabled = false;
            }

            // 如果是 SHUTDOWN，则移除
            if (Objects.equal(rawState.getState(), SHUTDOWN)) {
                helperImpl.hcStates.remove(this);
            }
            this.rawState = rawState;
            // 调整健康检查状态
            adjustHealthCheck();
        }

        private boolean isRetryTimerPending() {
            return retryTimer != null && retryTimer.isPending();
        }

        // Start or stop health check according to the current states.
        // 根据当前状态开始或者停止健康检查
        private void adjustHealthCheck() {
            // 如果没有禁止，且服务名不为空，且状态是 READY
            if (!disabled && serviceName != null && Objects.equal(rawState.getState(), READY)) {
                running = true;
                // 如果没有活跃的 RPC，且重试计时器没有等待，则开始 RPC
                if (activeRpc == null && !isRetryTimerPending()) {
                    // 执行健康检查，并根据结果发送请求
                    startRpc();
                }
            } else {
                running = false;
                // Prerequisites for health checking not met.
                // Make sure it's stopped.
                stopRpc("Client stops health check");
                backoffPolicy = null;
                gotoState(rawState);
            }
        }

        /**
         * 执行健康检查，并根据结果发送请求
         */
        private void startRpc() {
            checkState(serviceName != null, "serviceName is null");
            checkState(activeRpc == null, "previous health-checking RPC has not been cleaned up");
            checkState(subchannel != null, "init() not called");
            // Optimization suggested by @markroth: if we are already READY and starting the health
            // checking RPC, either because health check is just enabled or has switched to a new service
            // name, we don't go to CONNECTING, otherwise there will be artificial delays on RPCs
            // waiting for the health check to respond.
            if (!Objects.equal(concludedState.getState(), READY)) {
                subchannelLogger.log(ChannelLogLevel.INFO, "CONNECTING: Starting health-check for \"{0}\"", serviceName);
                // 修改连接状态
                gotoState(ConnectivityStateInfo.forNonError(CONNECTING));
            }
            // 创建新的调用监视器
            activeRpc = new HcStream();
            // 开始调用，发出请求
            activeRpc.start();
        }

        /**
         * 停止 RPC 调用
         *
         * @param msg 信息
         */
        private void stopRpc(String msg) {
            // 取消当前调用
            if (activeRpc != null) {
                // 客户端监听器取消调用
                activeRpc.cancel(msg);
                // Abandon this RPC.  We are not interested in anything from this RPC any more.
                // 放弃这个请求
                activeRpc = null;
            }
            // 取消重试计时
            if (retryTimer != null) {
                retryTimer.cancel();
                retryTimer = null;
            }
        }

        /**
         * 修改 Subchannel 连接状态
         *
         * @param newState 新的连接状态
         */
        private void gotoState(ConnectivityStateInfo newState) {
            checkState(subchannel != null, "init() not called");
            if (!Objects.equal(concludedState, newState)) {
                concludedState = newState;
                stateListener.onSubchannelState(concludedState);
            }
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("running", running)
                              .add("disabled", disabled)
                              .add("activeRpc", activeRpc)
                              .add("serviceName", serviceName)
                              .add("rawState", rawState)
                              .add("concludedState", concludedState)
                              .toString();
        }

        /**
         * 请求监听器
         */
        private class HcStream extends ClientCall.Listener<HealthCheckResponse> {
            private final ClientCall<HealthCheckRequest, HealthCheckResponse> call;
            private final String callServiceName;
            private final Stopwatch stopwatch;
            private boolean callHasResponded;

            /**
             * 开始计时器，开始新的调用
             */
            HcStream() {
                stopwatch = stopwatchSupplier.get().start();
                callServiceName = serviceName;
                // 开始新的调用
                call = subchannel.asChannel().newCall(HealthGrpc.getWatchMethod(), CallOptions.DEFAULT);
            }

            /**
             * 发出请求
             */
            void start() {
                // 开始调用
                call.start(this, new Metadata());
                // 发送服务健康检查消息
                call.sendMessage(HealthCheckRequest.newBuilder().setService(serviceName).build());
                call.halfClose();
                call.request(1);
            }

            /**
             * 取消调用
             *
             * @param msg 信息
             */
            void cancel(String msg) {
                call.cancel(msg, null);
            }

            /**
             * 监听响应消息
             *
             * @param response 响应
             */
            @Override
            public void onMessage(final HealthCheckResponse response) {
                syncContext.execute(new Runnable() {
                    @Override
                    public void run() {
                        // 如果是当前的请求，则进行处理
                        if (activeRpc == HcStream.this) {
                            // 根据响应更新连接状态
                            handleResponse(response);
                        }
                    }
                });
            }

            /**
             * 监听关闭消息
             *
             * @param status   the result of the remote call. 调用的结果
             * @param trailers metadata provided at call completion. 调用完成时提供的元数据
             */
            @Override
            public void onClose(final Status status, Metadata trailers) {
                syncContext.execute(new Runnable() {
                    @Override
                    public void run() {
                        // 如果是当前的请求，则进行处理
                        if (activeRpc == HcStream.this) {
                            activeRpc = null;
                            //
                            handleStreamClosed(status);
                        }
                    }
                });
            }

            /**
             * 处理健康检查的响应
             *
             * @param response 响应
             */
            void handleResponse(HealthCheckResponse response) {
                callHasResponded = true;
                backoffPolicy = null;

                // 获取返回的状态
                ServingStatus status = response.getStatus();

                // 如果是服务中，则更新连接状态为 READY
                // running == true means the Subchannel's state (rawState) is READY
                if (Objects.equal(status, ServingStatus.SERVING)) {
                    subchannelLogger.log(ChannelLogLevel.INFO, "READY: health-check responded SERVING");
                    gotoState(ConnectivityStateInfo.forNonError(READY));
                } else {
                    // 更新连接状态为 UNAVAILABLE
                    subchannelLogger.log(ChannelLogLevel.INFO, "TRANSIENT_FAILURE: health-check responded {0}", status);
                    gotoState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE.withDescription("Health-check service responded " + status + " for '" + callServiceName + "'")));
                }
                call.request(1);
            }

            /**
             * 处理流关闭
             *
             * @param status 关闭状态
             */
            void handleStreamClosed(Status status) {
                // 如果状态是 UNIMPLEMENTED，则更新状态并返回
                if (Objects.equal(status.getCode(), Code.UNIMPLEMENTED)) {
                    disabled = true;
                    logger.log(Level.SEVERE, "Health-check with {0} is disabled. Server returned: {1}", new Object[]{subchannel.getAllAddresses(), status});
                    subchannelLogger.log(ChannelLogLevel.ERROR, "Health-check disabled: {0}", status);
                    subchannelLogger.log(ChannelLogLevel.INFO, "{0} (no health-check)", rawState);
                    gotoState(rawState);
                    return;
                }

                long delayNanos = 0;
                subchannelLogger.log(ChannelLogLevel.INFO, "TRANSIENT_FAILURE: health-check stream closed with {0}", status);

                // 更新状态为 UNAVAILABLE
                gotoState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE.withDescription("Health-check stream unexpectedly closed with " + status + " for '" + callServiceName + "'")));

                // Use backoff only when server has not responded for the previous call
                // 仅当服务器未响应上一个请求时才使用退避
                if (!callHasResponded) {
                    // 如果没有响应且没有退避策略，则获取
                    if (backoffPolicy == null) {
                        backoffPolicy = backoffPolicyProvider.get();
                    }
                    // 计算退避时间
                    delayNanos = backoffPolicy.nextBackoffNanos() - stopwatch.elapsed(TimeUnit.NANOSECONDS);
                }

                // 如果退避时间小于等于0，则开始请求
                if (delayNanos <= 0) {
                    startRpc();
                } else {
                    checkState(!isRetryTimerPending(), "Retry double scheduled");
                    subchannelLogger.log(ChannelLogLevel.DEBUG, "Will retry health-check after {0} ns", delayNanos);

                    // 有退避时间，则提交延时任务执行
                    retryTimer = syncContext.schedule(retryTask, delayNanos, TimeUnit.NANOSECONDS, timerService);
                }
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                                  .add("callStarted", call != null)
                                  .add("serviceName", callServiceName)
                                  .add("hasResponded", callHasResponded)
                                  .toString();
            }
        }
    }
}
