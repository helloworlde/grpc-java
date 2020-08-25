/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.grpclb;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import io.grpc.Attributes;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.grpclb.GrpclbState.Mode;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * A {@link LoadBalancer} that uses the GRPCLB protocol.
 * 使用 GRPCLB 协议的负载均衡器
 *
 * <p>Optionally, when requested by the naming system, will delegate the work to a local pick-first
 * or round-robin balancer.
 * 当要求使用系统命名时，会被本地的 pick-first 或 round-robin 负载均衡代理
 */
class GrpclbLoadBalancer extends LoadBalancer {

    // 使用轮询策略创建默认的配置
    private static final GrpclbConfig DEFAULT_CONFIG = GrpclbConfig.create(Mode.ROUND_ROBIN);

    private final Helper helper;
    private final TimeProvider time;
    private final Stopwatch stopwatch;
    private final SubchannelPool subchannelPool;
    private final BackoffPolicy.Provider backoffPolicyProvider;

    // 配置
    private GrpclbConfig config = DEFAULT_CONFIG;

    // All mutable states in this class are mutated ONLY from Channel Executor
    // 这个类中所有可变的状态都是通过 Channel 的 Executor 修改的
    @Nullable
    private GrpclbState grpclbState;

    GrpclbLoadBalancer(Helper helper,
                       SubchannelPool subchannelPool,
                       TimeProvider time,
                       Stopwatch stopwatch,
                       BackoffPolicy.Provider backoffPolicyProvider) {
        this.helper = checkNotNull(helper, "helper");
        this.time = checkNotNull(time, "time provider");
        this.stopwatch = checkNotNull(stopwatch, "stopwatch");
        this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
        this.subchannelPool = checkNotNull(subchannelPool, "subchannelPool");
        // 重新创建状态
        recreateStates();
        checkNotNull(grpclbState, "grpclbState");
    }


    /**
     * 处理解析的地址
     *
     * @param resolvedAddresses the resolved server addresses, attributes, and config.
     *                          解析的 server 的地址、属性和配置
     */
    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        // 获取属性
        Attributes attributes = resolvedAddresses.getAttributes();
        // 获取地址列表
        List<EquivalentAddressGroup> newLbAddresses = attributes.get(GrpclbConstants.ATTR_LB_ADDRS);

        // 如果新的地址为空，且远离的地址也为空，则
        if ((newLbAddresses == null || newLbAddresses.isEmpty()) &&
                resolvedAddresses.getAddresses().isEmpty()) {
            handleNameResolutionError(Status.UNAVAILABLE.withDescription("No backend or balancer addresses found"));
            return;
        }

        List<LbAddressGroup> newLbAddressGroups = new ArrayList<>();

        // 遍历地址，转为 LbAddressGroup
        if (newLbAddresses != null) {
            for (EquivalentAddressGroup lbAddr : newLbAddresses) {
                String lbAddrAuthority = lbAddr.getAttributes().get(GrpclbConstants.ATTR_LB_ADDR_AUTHORITY);
                if (lbAddrAuthority == null) {
                    throw new AssertionError("This is a bug: LB address " + lbAddr + " does not have an authority.");
                }
                newLbAddressGroups.add(new LbAddressGroup(lbAddr, lbAddrAuthority));
            }
        }


        // 变为不可变的
        newLbAddressGroups = Collections.unmodifiableList(newLbAddressGroups);
        List<EquivalentAddressGroup> newBackendServers = Collections.unmodifiableList(resolvedAddresses.getAddresses());
        // 获取负载均衡配置
        GrpclbConfig newConfig = (GrpclbConfig) resolvedAddresses.getLoadBalancingPolicyConfig();

        // 如果配置为空则使用默认的配置
        if (newConfig == null) {
            newConfig = DEFAULT_CONFIG;
        }
        // 如果配置发生变化，则更新配置，重新创建状态
        if (!config.equals(newConfig)) {
            config = newConfig;
            helper.getChannelLogger().log(ChannelLogLevel.INFO, "Config: " + newConfig);
            recreateStates();
        }
        // 处理解析的地址，并创建连接
        grpclbState.handleAddresses(newLbAddressGroups, newBackendServers);
    }

    /**
     * 创建连接
     */
    @Override
    public void requestConnection() {
        if (grpclbState != null) {
            grpclbState.requestConnection();
        }
    }

    /**
     * 重置状态
     */
    private void resetStates() {
        if (grpclbState != null) {
            grpclbState.shutdown();
            grpclbState = null;
        }
    }


    /**
     * 重新创建状态
     */
    private void recreateStates() {
        // 重置状态
        resetStates();
        checkState(grpclbState == null, "Should've been cleared");
        // 重新创建
        grpclbState = new GrpclbState(config,
                helper,
                subchannelPool,
                time,
                stopwatch,
                backoffPolicyProvider);
    }

    /**
     * 关闭，重置状态
     */
    @Override
    public void shutdown() {
        resetStates();
    }

    /**
     * 处理解析失败
     *
     * @param error a non-OK status 非 OK 的状态
     */
    @Override
    public void handleNameResolutionError(Status error) {
        if (grpclbState != null) {
            grpclbState.propagateError(error);
        }
    }

    /**
     * 是否可以处理空的地址列表
     */
    @Override
    public boolean canHandleEmptyAddressListFromNameResolution() {
        return true;
    }

    /**
     * 获取状态
     */
    @VisibleForTesting
    @Nullable
    GrpclbState getGrpclbState() {
        return grpclbState;
    }
}
