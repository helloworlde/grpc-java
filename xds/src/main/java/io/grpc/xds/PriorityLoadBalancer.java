/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds;

import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;

/**
 * Load balancer for priority policy.
 * 优先级策略的负载均衡
 */
final class PriorityLoadBalancer extends LoadBalancer {

    private final Helper helper;
    private final SynchronizationContext syncContext;
    private final ScheduledExecutorService executor;
    private final XdsLogger logger;

    // Includes all active and deactivated children. Mutable. New entries are only added from priority
    // 0 up to the selected priority. An entry is only deleted 15 minutes after the its deactivation.
    // 包含所有的活跃和非活跃的子 LB 状态，可变的，新的实例加入时优先级为 0，在停用 15 分钟后删除
    private final Map<String, ChildLbState> children = new HashMap<>();

    // Following fields are only null initially.
    private ResolvedAddresses resolvedAddresses;
    private List<String> priorityNames;
    private Map<String, Integer> priorityNameToIndex;
    private ConnectivityState currentConnectivityState;
    private SubchannelPicker currentPicker;

    /**
     * 根据 helper 构建实例
     */
    PriorityLoadBalancer(Helper helper) {
        this.helper = checkNotNull(helper, "helper");
        syncContext = helper.getSynchronizationContext();
        executor = helper.getScheduledExecutorService();
        InternalLogId logId = InternalLogId.allocate("priority-lb", helper.getAuthority());
        logger = XdsLogger.withLogId(logId);
        logger.log(XdsLogLevel.INFO, "Created");
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);

        this.resolvedAddresses = resolvedAddresses;

        // LB 配置
        PriorityLbConfig config = (PriorityLbConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
        checkNotNull(config, "missing priority lb config");
        priorityNames = config.priorities;

        // 将优先级的名称和序号保存到 Map 中
        Map<String, Integer> pToI = new HashMap<>();
        for (int i = 0; i < priorityNames.size(); i++) {
            pToI.put(priorityNames.get(i), i);
        }
        priorityNameToIndex = Collections.unmodifiableMap(pToI);

        // 遍历子优先级
        for (String priority : children.keySet()) {
            // 如果优先级队列中没有这个优先级，则将其置为不活跃
            if (!priorityNameToIndex.containsKey(priority)) {
                children.get(priority).deactivate();
            }
        }

        // 遍历优先级名称
        for (String priority : priorityNames) {
            // 如果包含则更新地址
            if (children.containsKey(priority)) {
                children.get(priority).updateResolvedAddresses();
            }
        }
        // Not to report connecting in case a pending priority bumps up on top of the current READY priority.
        // 如果在当前 READY 优先级之上有待处理的优先级升高，则不报告连接
        tryNextPriority(false);
    }

    /**
     * 处理解析错误
     *
     * @param error a non-OK status 错误状态
     */
    @Override
    public void handleNameResolutionError(Status error) {
        logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);

        // 更新 LB 状态
        if (children.isEmpty()) {
            updateOverallState(TRANSIENT_FAILURE, new ErrorPicker(error));
        }

        // 遍历更新 ChildLbState 状态
        for (ChildLbState child : children.values()) {
            child.lb.handleNameResolutionError(error);
        }
    }

    @Override
    public void shutdown() {
        logger.log(XdsLogLevel.INFO, "Shutdown");
        for (ChildLbState child : children.values()) {
            child.tearDown();
        }
    }

    /**
     * 使用下一个优先级
     *
     * @param reportConnecting
     */
    private void tryNextPriority(boolean reportConnecting) {

        for (int i = 0; i < priorityNames.size(); i++) {
            // 优先级
            String priority = priorityNames.get(i);

            // 如果 children 中不包含当前优先级，则创建一个并加入
            if (!children.containsKey(priority)) {
                // 创建 ChildLbState
                ChildLbState child = new ChildLbState(priority);
                children.put(priority, child);
                // 更新地址
                child.updateResolvedAddresses();
                // 更新连接状态和 SubchannelPicker
                updateOverallState(CONNECTING, BUFFER_PICKER);
                return; // Give priority i time to connect.
            }

            // 获取 ChildLbState
            ChildLbState child = children.get(priority);
            // 重新激活
            child.reactivate();

            // 如果连接状态是 READY 或者 IDLE 时
            if (child.connectivityState.equals(READY) || child.connectivityState.equals(IDLE)) {
                logger.log(XdsLogLevel.DEBUG, "Shifted to priority {0}", priority);

                // 更新状态和 SubchannelPicker
                updateOverallState(child.connectivityState, child.picker);

                // 遍历后面的优先级，状态修改为未激活
                for (int j = i + 1; j < priorityNames.size(); j++) {
                    String p = priorityNames.get(j);
                    if (children.containsKey(p)) {
                        children.get(p).deactivate();
                    }
                }
                return;
            }

            // 如果计时器不为 null，且计时器等待，则更新状态和 SubchannelPicker
            if (child.failOverTimer != null && child.failOverTimer.isPending()) {
                if (reportConnecting) {
                    updateOverallState(CONNECTING, BUFFER_PICKER);
                }
                return; // Give priority i time to connect.
            }
        }
        // TODO(zdapeng): Include error details of each priority.
        logger.log(XdsLogLevel.DEBUG, "All priority failed");
        // 当优先级失败时，更新连接状态为 TRANSIENT_FAILURE，SubchannelPicker 为 ErrorPicker
        updateOverallState(TRANSIENT_FAILURE, new ErrorPicker(Status.UNAVAILABLE));
    }

    /**
     * 当连接状态发生变化，且 SubchannelPicker 不同时，更新状态
     */
    private void updateOverallState(ConnectivityState state, SubchannelPicker picker) {
        if (!state.equals(currentConnectivityState) || !picker.equals(currentPicker)) {
            currentConnectivityState = state;
            currentPicker = picker;
            helper.updateBalancingState(state, picker);
        }
    }

    private final class ChildLbState {
        final String priority;

        final ChildHelper childHelper;

        final GracefulSwitchLoadBalancer lb;

        // Timer to fail over to the next priority if not connected in 10 sec. Scheduled only once at
        // child initialization.
        final ScheduledHandle failOverTimer;

        // Timer to delay shutdown and deletion of the priority. Scheduled whenever the child is
        // deactivated.
        @Nullable
        ScheduledHandle deletionTimer;

        @Nullable
        String policy;

        ConnectivityState connectivityState = CONNECTING;

        SubchannelPicker picker = BUFFER_PICKER;

        /**
         * 根据优先级构建
         *
         * @param priority 优先级
         */
        ChildLbState(final String priority) {
            this.priority = priority;

            childHelper = new ChildHelper();
            // 使用 GracefulSwitchLoadBalancer 作为默认的 LB
            lb = new GracefulSwitchLoadBalancer(childHelper);

            class FailOverTask implements Runnable {
                @Override
                public void run() {
                    if (deletionTimer != null && deletionTimer.isPending()) {
                        // The child is deactivated.
                        return;
                    }
                    logger.log(XdsLogLevel.DEBUG, "Priority {0} failed over to next", priority);
                    tryNextPriority(true);
                }
            }

            failOverTimer = syncContext.schedule(new FailOverTask(), 10, TimeUnit.SECONDS, executor);
            logger.log(XdsLogLevel.DEBUG, "Priority created: {0}", priority);
        }

        /**
         * Called when the child becomes a priority that is or appears before the first READY one in the
         * {@code priorities} list, due to either config update or balancing state update.
         * <p>
         * 当子项由于配置更新或平衡状态更新而成为优先级列表中的第一个 READY 优先级之前出现时，调用此方法
         */
        void reactivate() {
            if (deletionTimer != null && deletionTimer.isPending()) {
                deletionTimer.cancel();
                logger.log(XdsLogLevel.DEBUG, "Priority reactivated: {0}", priority);
            }
        }

        /**
         * Called when either the child is removed by config update, or a higher priority becomes READY.
         */
        void deactivate() {
            if (deletionTimer != null && deletionTimer.isPending()) {
                return;
            }

            class DeletionTask implements Runnable {
                @Override
                public void run() {
                    tearDown();
                    children.remove(priority);
                }
            }

            deletionTimer = syncContext.schedule(new DeletionTask(), 15, TimeUnit.MINUTES, executor);
            logger.log(XdsLogLevel.DEBUG, "Priority deactivated: {0}", priority);
        }

        /**
         * 关闭 LB
         */
        void tearDown() {
            if (failOverTimer.isPending()) {
                failOverTimer.cancel();
            }
            if (deletionTimer != null && deletionTimer.isPending()) {
                deletionTimer.cancel();
            }
            lb.shutdown();
            logger.log(XdsLogLevel.DEBUG, "Priority deleted: {0}", priority);
        }

        /**
         * Called either when the child is just created and in this case updated with the cached {@code
         * resolvedAddresses}, or when priority lb receives a new resolved addresses while the child
         * already exists.
         * <p>
         * 在刚刚创建子级并在这种情况下使用缓存更新时调用，或者当优先级 LB 收到新的地址且地址已经存在时更新
         */
        void updateResolvedAddresses() {
            final ResolvedAddresses addresses = resolvedAddresses;
            syncContext.execute(new Runnable() {
                @Override
                public void run() {
                    // 获取配置
                    PriorityLbConfig config = (PriorityLbConfig) addresses.getLoadBalancingPolicyConfig();
                    // 策略选择
                    PolicySelection childPolicySelection = config.childConfigs.get(priority);
                    // LB 提供器
                    LoadBalancerProvider lbProvider = childPolicySelection.getProvider();

                    // 新的策略名称
                    String newPolicy = lbProvider.getPolicyName();
                    // 如果策略名称不同，则切换到其他的策略
                    if (!newPolicy.equals(policy)) {
                        policy = newPolicy;
                        lb.switchTo(lbProvider);
                    }
                    // TODO(zdapeng): Implement address filtering.
                    // 处理地址更新
                    lb.handleResolvedAddresses(addresses.toBuilder()
                                                        // 设置 PathChain 属性
                                                        .setAddresses(AddressFilter.filter(addresses.getAddresses(), priority))
                                                        .setLoadBalancingPolicyConfig(childPolicySelection.getConfig())
                                                        .build());
                }
            });
        }

        /**
         * 继承 ForwardingLoadBalancerHelper 作为 ChildHelper
         */
        final class ChildHelper extends ForwardingLoadBalancerHelper {

            /**
             * 更新 LB 状态
             *
             * @param newState  连接状态
             * @param newPicker SubchannelPicker
             */
            @Override
            public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
                connectivityState = newState;
                picker = newPicker;

                if (deletionTimer != null && deletionTimer.isPending()) {
                    return;
                }

                if (failOverTimer.isPending()) {
                    if (newState.equals(READY) || newState.equals(TRANSIENT_FAILURE)) {
                        failOverTimer.cancel();
                    }
                }

                tryNextPriority(true);
            }

            @Override
            protected Helper delegate() {
                return helper;
            }
        }
    }
}
