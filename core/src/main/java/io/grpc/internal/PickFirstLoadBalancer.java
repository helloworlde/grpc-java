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

import com.google.common.base.MoreObjects;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

/**
 * A {@link LoadBalancer} that provides no load-balancing over the addresses from the {@link
 * io.grpc.NameResolver}.  The channel's default behavior is used, which is walking down the address
 * list and sticking to the first that works.
 * 不对来自 NameResolver 的地址负载均衡，channel 使用 channel 默认的行为，即查找地址，并使用第一个
 * <p>
 * 在 RoundRobinLoadBalancer 中，一个 EquivalentAddressGroup 对应一个 Subchannel，这个 EquivalentAddressGroup
 * 中可能有多个 IP:PORT，取决于如何构建 EquivalentAddressGroup
 * <p>
 * 在 PickFirstLoadBalancer 中，所有的 EquivalentAddressGroup 只有一个 Subchannel
 * <p>
 * 底层的 Transport 都可以有多个 IP:PORT 地址，使用轮询的方式访问不同 EquivalentAddressGroup 下的 SocketAddress 地址，
 * 实现方法在 io.grpc.internal.InternalSubchannel.Index#getCurrentAddress()
 */
final class PickFirstLoadBalancer extends LoadBalancer {

    private final Helper helper;

    private Subchannel subchannel;

    /**
     * 构建负载均衡器
     *
     * @param helper
     */
    PickFirstLoadBalancer(Helper helper) {
        this.helper = checkNotNull(helper, "helper");
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {

        List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();

        if (subchannel == null) {
            // 创建 Subchannel
            final Subchannel subchannel = helper.createSubchannel(CreateSubchannelArgs.newBuilder()
                                                                                      .setAddresses(servers)
                                                                                      .build());
            // 启动 Subchannel 状态监听器
            subchannel.start(new SubchannelStateListener() {
                @Override
                public void onSubchannelState(ConnectivityStateInfo stateInfo) {
                    // 处理连接状态更新
                    processSubchannelState(subchannel, stateInfo);
                }
            });
            this.subchannel = subchannel;

            // The channel state does not get updated when doing name resolving today, so for the moment
            // let LB report CONNECTION and call subchannel.requestConnection() immediately.
            // Channel 的状态在解析服务过程中没有发生变化，所以返回 CONNECTING 状态，并立即调用 subchannel.requestConnection()
            helper.updateBalancingState(CONNECTING, new Picker(PickResult.withSubchannel(subchannel)));
            // 建立连接
            subchannel.requestConnection();
        } else {
            // 如果 Subchannel 已经存在，则更新地址
            subchannel.updateAddresses(servers);
        }
    }

    /**
     * 处理命名解析错误
     *
     * @param error a non-OK status 非 OK 的状态码
     */
    @Override
    public void handleNameResolutionError(Status error) {
        // 如果 Subchannel 不为 null，则关闭
        if (subchannel != null) {
            subchannel.shutdown();
            subchannel = null;
        }
        // NB(lukaszx0) Whether we should propagate the error unconditionally is arguable. It's fine
        // for time being.
        // 更新 LB 状态为 TRANSIENT_FAILURE
        helper.updateBalancingState(TRANSIENT_FAILURE, new Picker(PickResult.withError(error)));
    }

    /**
     * 处理 Subchannel 状态更新事件
     *
     * @param subchannel Subchannel
     * @param stateInfo  连接状态
     */
    private void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
        ConnectivityState currentState = stateInfo.getState();
        // 如果是 SHUTDOWN，则直接返回
        if (currentState == SHUTDOWN) {
            return;
        }

        SubchannelPicker picker;
        switch (currentState) {
            // 如果是 IDLE 状态，则使用建立连接的 picker
            case IDLE:
                picker = new RequestConnectionPicker(subchannel);
                break;
            // 如果是 CONNECTING 状态，则返回无结果的 Picker
            case CONNECTING:
                // It's safe to use RequestConnectionPicker here, so when coming from IDLE we could leave
                // the current picker in-place. But ignoring the potential optimization is simpler.
                picker = new Picker(PickResult.withNoResult());
                break;
            // 如果是 READY 状态，则使用当前 Subchannel 的 picker
            case READY:
                picker = new Picker(PickResult.withSubchannel(subchannel));
                break;
            // 如果是 TRANSIENT_FAILURE 状态，则使用返回错误的 Picker
            case TRANSIENT_FAILURE:
                picker = new Picker(PickResult.withError(stateInfo.getStatus()));
                break;
            default:
                throw new IllegalArgumentException("Unsupported state:" + currentState);
        }
        // 更新 LoadBalancer 状态
        helper.updateBalancingState(currentState, picker);
    }

    @Override
    public void shutdown() {
        if (subchannel != null) {
            subchannel.shutdown();
        }
    }

    @Override
    public void requestConnection() {
        if (subchannel != null) {
            subchannel.requestConnection();
        }
    }

    /**
     * No-op picker which doesn't add any custom picking logic. It just passes already known result
     * received in constructor.
     * 不添加任何选择逻辑的 picker，仅传递构造器中接收的结果
     */
    private static final class Picker extends SubchannelPicker {
        private final PickResult result;

        Picker(PickResult result) {
            this.result = checkNotNull(result, "result");
        }

        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            return result;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(Picker.class).add("result", result).toString();
        }
    }

    /**
     * Picker that requests connection during the first pick, and returns noResult.
     * 在第一次选择期间返回建立连接的 Picker，返回无结果
     */
    private final class RequestConnectionPicker extends SubchannelPicker {
        private final Subchannel subchannel;
        private final AtomicBoolean connectionRequested = new AtomicBoolean(false);

        RequestConnectionPicker(Subchannel subchannel) {
            this.subchannel = checkNotNull(subchannel, "subchannel");
        }

        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            if (connectionRequested.compareAndSet(false, true)) {
                helper.getSynchronizationContext()
                      .execute(new Runnable() {
                          @Override
                          public void run() {
                              // 建立连接
                              subchannel.requestConnection();
                          }
                      });
            }
            return PickResult.withNoResult();
        }
    }
}
