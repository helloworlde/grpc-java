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

import com.google.common.collect.ImmutableMap;
import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.WeightedRandomPicker.WeightedChildPicker;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;

/**
 * Load balancer for weighted_target policy.
 * <p>
 * 基于权重的负载均衡
 */
final class WeightedTargetLoadBalancer extends LoadBalancer {

    private final XdsLogger logger;
    private final Map<String, GracefulSwitchLoadBalancer> childBalancers = new HashMap<>();
    private final Map<String, ChildHelper> childHelpers = new HashMap<>();
    private final Helper helper;

    private Map<String, WeightedPolicySelection> targets = ImmutableMap.of();

    /**
     * 使用 Helper 构造
     */
    WeightedTargetLoadBalancer(Helper helper) {
        this.helper = helper;
        logger = XdsLogger.withLogId(InternalLogId.allocate("weighted-target-lb", helper.getAuthority()));
        logger.log(XdsLogLevel.INFO, "Created");
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
        // 获取配置
        Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
        checkNotNull(lbConfig, "missing weighted_target lb config");

        // 解析权重的配置
        WeightedTargetConfig weightedTargetConfig = (WeightedTargetConfig) lbConfig;
        Map<String, WeightedPolicySelection> newTargets = weightedTargetConfig.targets;

        for (String targetName : newTargets.keySet()) {
            // 根据名称获取策略选择器
            WeightedPolicySelection weightedChildLbConfig = newTargets.get(targetName);

            // 当 targets 中不包含当前 target 时，添加
            if (!targets.containsKey(targetName)) {
                // 构建支持切换的 LB
                ChildHelper childHelper = new ChildHelper();
                GracefulSwitchLoadBalancer childBalancer = new GracefulSwitchLoadBalancer(childHelper);
                // 根据策略，切换到配置的 LB
                childBalancer.switchTo(weightedChildLbConfig.policySelection.getProvider());
                // 根据名称保存 Helper 和 Balancer
                childHelpers.put(targetName, childHelper);
                childBalancers.put(targetName, childBalancer);
            } else if (!weightedChildLbConfig.policySelection.getProvider().equals(targets.get(targetName).policySelection.getProvider())) {
                // 当 target 策略发生变化时更新
                childBalancers.get(targetName)
                              .switchTo(weightedChildLbConfig.policySelection.getProvider());
            }
        }

        targets = newTargets;

        // 遍历 target，根据不同的策略处理地址
        for (String targetName : targets.keySet()) {
            childBalancers.get(targetName)
                          .handleResolvedAddresses(resolvedAddresses.toBuilder()
                                                                    .setAddresses(AddressFilter.filter(resolvedAddresses.getAddresses(), targetName))
                                                                    .setLoadBalancingPolicyConfig(targets.get(targetName).policySelection.getConfig())
                                                                    .build());
        }

        // Cleanup removed targets.
        // TODO(zdapeng): cache removed target for 15 minutes.
        // 清理已经被移除的 target
        for (String targetName : childBalancers.keySet()) {
            if (!targets.containsKey(targetName)) {
                childBalancers.get(targetName).shutdown();
            }
        }
        childBalancers.keySet().retainAll(targets.keySet());
        childHelpers.keySet().retainAll(targets.keySet());
    }

    /**
     * 处理解析错误
     */
    @Override
    public void handleNameResolutionError(Status error) {
        logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
        if (childBalancers.isEmpty()) {
            helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
        }
        for (LoadBalancer childBalancer : childBalancers.values()) {
            childBalancer.handleNameResolutionError(error);
        }
    }

    /**
     * 是否可以处理空地址
     */
    @Override
    public boolean canHandleEmptyAddressListFromNameResolution() {
        return true;
    }

    @Override
    public void shutdown() {
        logger.log(XdsLogLevel.INFO, "Shutdown");
        for (LoadBalancer childBalancer : childBalancers.values()) {
            childBalancer.shutdown();
        }
    }

    /**
     * 更新 LB 状态
     */
    private void updateOverallBalancingState() {
        List<WeightedChildPicker> childPickers = new ArrayList<>();

        ConnectivityState overallState = null;
        for (String name : targets.keySet()) {
            ChildHelper childHelper = childHelpers.get(name);
            ConnectivityState childState = childHelper.currentState;
            // 合并状态
            overallState = aggregateState(overallState, childState);
            // 如果是 READY 状态，则创建 Picker 并添加到集合中
            if (READY == childState) {
                int weight = targets.get(name).weight;
                childPickers.add(new WeightedChildPicker(weight, childHelper.currentPicker));
            }
        }

        SubchannelPicker picker;
        // 如果子 picker 为空
        if (childPickers.isEmpty()) {
            // 状态为 TRANSIENT_FAILURE 时返回错误
            if (overallState == TRANSIENT_FAILURE) {
                picker = new ErrorPicker(Status.UNAVAILABLE); // TODO: more details in status
            } else {
                // 否则返回没有地址的 Picker
                picker = XdsSubchannelPickers.BUFFER_PICKER;
            }
        } else {
            // 子 picker 不为空则构建返回子 picker
            picker = new WeightedRandomPicker(childPickers);
        }

        // 更新 LB 状态
        if (overallState != null) {
            helper.updateBalancingState(overallState, picker);
        }
    }

    /**
     * 合并状态
     *
     * @param overallState 整体状态
     * @param childState   子状态
     * @return 合并后的状态
     */
    @Nullable
    private static ConnectivityState aggregateState(@Nullable ConnectivityState overallState,
                                                    ConnectivityState childState) {
        // 如果整体状态为 null，则返回子状态
        if (overallState == null) {
            return childState;
        }
        // 有任意一个为 READY，则返回 READY
        if (overallState == READY || childState == READY) {
            return READY;
        }

        // 有任意一个为 CONNECTING，则返回 CONNECTING
        if (overallState == CONNECTING || childState == CONNECTING) {
            return CONNECTING;
        }

        // 有任意一个为 IDLE，则返回 IDLE
        if (overallState == IDLE || childState == IDLE) {
            return IDLE;
        }
        // 其他状态以整体状态为准
        return overallState;
    }

    /**
     * 支持更新状态和 Picker 的代理 Helper
     */
    private final class ChildHelper extends ForwardingLoadBalancerHelper {
        ConnectivityState currentState = CONNECTING;
        SubchannelPicker currentPicker = BUFFER_PICKER;

        @Override
        public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
            currentState = newState;
            currentPicker = newPicker;
            updateOverallBalancingState();
        }

        @Override
        protected Helper delegate() {
            return helper;
        }
    }
}
