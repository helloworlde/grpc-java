/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.Status;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * A load balancer that gracefully swaps to a new lb policy. If the channel is currently in a state
 * other than READY, the new policy will be swapped into place immediately.  Otherwise, the channel
 * will keep using the old policy until the new policy reports READY or the old policy exits READY.
 * <p>
 * 可以优雅替换为新的负载均衡策略的负载均衡器，如果当前 Channel 的状态不是 READY，会立即使用新的策略；除此之外，
 * Channel 会继续使用原有的策略，直到新的策略 READY 或者原有的策略 READY
 *
 * <p>The balancer must {@link #switchTo(LoadBalancer.Factory) switch to} a policy prior to {@link
 * LoadBalancer#handleResolvedAddresses(ResolvedAddresses) handling resolved addresses} for the
 * first time.
 * 负载均衡器第一次调用 handleResolvedAddresses 之前必须先调用 switchTo(LoadBalancer.Factory) 切换到一个策略
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/5999")
@NotThreadSafe // Must be accessed in SynchronizationContext
public final class GracefulSwitchLoadBalancer extends ForwardingLoadBalancer {

    /**
     * 默认的负载均衡器
     */
    private final LoadBalancer defaultBalancer = new LoadBalancer() {

        /**
         * 处理地址之前需要切换策略
         * @param resolvedAddresses the resolved server addresses, attributes, and config.
         *                          解析的 server 的地址、属性和配置
         */
        @Override
        public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
            //  Most LB policies using this class will receive child policy configuration within the
            //  service config, so they are naturally calling switchTo() just before
            //  handleResolvedAddresses(), within their own handleResolvedAddresses(). If switchTo() is
            //  not called immediately after construction that does open up potential for bugs in the
            //  parent policies, where they fail to call switchTo(). So we will use the exception to try
            //  to notice those bugs quickly, as it will fail very loudly.
            throw new IllegalStateException("GracefulSwitchLoadBalancer must switch to a load balancing policy before handling"
                    + " ResolvedAddresses");
        }

        /**
         * 处理解析失败
         *
         * @param error a non-OK status 非 OK 的状态码
         */
        @Override
        public void handleNameResolutionError(final Status error) {
            class ErrorPicker extends SubchannelPicker {
                /**
                 * 根据状态返回错误
                 *
                 * @param args the pick arguments 选择参数
                 * @return 错误结果
                 */
                @Override
                public PickResult pickSubchannel(PickSubchannelArgs args) {
                    return PickResult.withError(error);
                }

                @Override
                public String toString() {
                    return MoreObjects.toStringHelper(ErrorPicker.class).add("error", error).toString();
                }
            }

            // 更新连接状态为失败
            helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new ErrorPicker());
        }

        @Override
        public void shutdown() {
        }
    };

    /**
     * 无结果的选择器
     */
    @VisibleForTesting
    static final SubchannelPicker BUFFER_PICKER = new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            return PickResult.withNoResult();
        }

        @Override
        public String toString() {
            return "BUFFER_PICKER";
        }
    };

    private final Helper helper;

    // While the new policy is not fully switched on, the pendingLb is handling new updates from name
    // resolver, and the currentLb is updating channel state and picker for the given helper.
    // The current fields are guaranteed to be set after the initial swapTo().
    // The pending fields are cleared when it becomes current.
    // 在新的策略完全切换完成之前，pendingLb 会处理命名解析的更新，currentLb 会根据 Helper 更新 Channel 状态和 picker
    // 确保在初始化 swapTo 之后设置 current 属性，在 current 初始化之后，pending 属性会被清除
    @Nullable
    private LoadBalancer.Factory currentBalancerFactory;

    private LoadBalancer currentLb = defaultBalancer;

    @Nullable
    private LoadBalancer.Factory pendingBalancerFactory;

    private LoadBalancer pendingLb = defaultBalancer;

    private ConnectivityState pendingState;

    private SubchannelPicker pendingPicker;

    private boolean currentLbIsReady;

    /**
     * 根据 Helper 构建 LB
     */
    public GracefulSwitchLoadBalancer(Helper helper) {
        this.helper = checkNotNull(helper, "helper");
    }

    /**
     * Gracefully switch to a new policy defined by the given factory, if the given factory isn't
     * equal to the current one.
     * 根据所给的工厂，当所给的工厂和当前的不同时，优雅的切换到新的策略
     */
    public void switchTo(LoadBalancer.Factory newBalancerFactory) {
        checkNotNull(newBalancerFactory, "newBalancerFactory");

        // 如果新的工程是当前的 pending 工厂，则不切换
        if (newBalancerFactory.equals(pendingBalancerFactory)) {
            return;
        }

        // 清除 pending 的属性
        pendingLb.shutdown();
        pendingLb = defaultBalancer;
        pendingBalancerFactory = null;
        pendingState = ConnectivityState.CONNECTING;
        pendingPicker = BUFFER_PICKER;

        // 如果新的工厂是当前的工厂，则不切换
        if (newBalancerFactory.equals(currentBalancerFactory)) {
            return;
        }

        // 代理 Helper
        class PendingHelper extends ForwardingLoadBalancerHelper {
            LoadBalancer lb;

            @Override
            protected Helper delegate() {
                return helper;
            }

            /**
             * 更新连接状态和 picker
             *
             * @param newState 新的连接状态
             * @param newPicker 新的 picker
             */
            @Override
            public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {

                // 如果 LB 是 pending 的 LB
                if (lb == pendingLb) {
                    checkState(currentLbIsReady, "there's pending lb while current lb has been out of READY");
                    pendingState = newState;
                    pendingPicker = newPicker;
                    // 如果新的状态是 READY，则调用 swap 交换 LB，用 pending 替换 current 的属性
                    if (newState == ConnectivityState.READY) {
                        swap();
                    }
                } else if (lb == currentLb) {
                    // 如果 LB 是当前的 LB，则更新状态
                    currentLbIsReady = newState == ConnectivityState.READY;

                    // 如果新的状态不是 READY，且 pending 不是当前的LB，则将 current 属性替换为 pending 的
                    if (!currentLbIsReady && pendingLb != defaultBalancer) {
                        swap(); // current policy exits READY, so swap
                    } else {
                        // 更新状态
                        helper.updateBalancingState(newState, newPicker);
                    }
                }
            }
        }

        PendingHelper pendingHelper = new PendingHelper();

        // 根据 Helper 创建 LB
        pendingHelper.lb = newBalancerFactory.newLoadBalancer(pendingHelper);

        // 将 pending LB 替换为当前的 LB
        pendingLb = pendingHelper.lb;
        pendingBalancerFactory = newBalancerFactory;
        // 如果当前的状态不是 READY，则将 current 属性替换为 pending 的属性
        if (!currentLbIsReady) {
            swap(); // the old policy is not READY at the moment, so swap to the new one right now
        }
    }

    /**
     * 交换 LB，用 pending 替换 current 的属性
     */
    private void swap() {
        // 更新状态
        helper.updateBalancingState(pendingState, pendingPicker);
        // 关闭当前的 LB，替换为 pending 属性
        currentLb.shutdown();
        currentLb = pendingLb;
        currentBalancerFactory = pendingBalancerFactory;
        pendingLb = defaultBalancer;
        pendingBalancerFactory = null;
    }

    @Override
    protected LoadBalancer delegate() {
        return pendingLb == defaultBalancer ? currentLb : pendingLb;
    }

    @Override
    @Deprecated
    public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
        throw new UnsupportedOperationException(
                "handleSubchannelState() is not supported by " + this.getClass().getName());
    }

    @Override
    public void shutdown() {
        pendingLb.shutdown();
        currentLb.shutdown();
    }
}
