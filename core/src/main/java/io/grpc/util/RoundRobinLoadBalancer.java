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

package io.grpc.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.NameResolver;
import io.grpc.Status;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

/**
 * A {@link LoadBalancer} that provides round-robin load-balancing over the {@link
 * EquivalentAddressGroup}s from the {@link NameResolver}.
 * <p>
 * 根据 NameResolver 提供的 EquivalentAddressGroup 提供轮询的负载均衡
 */
final class RoundRobinLoadBalancer extends LoadBalancer {

    @VisibleForTesting
    static final Attributes.Key<Ref<ConnectivityStateInfo>> STATE_INFO = Attributes.Key.create("state-info");

    private final Helper helper;

    private final Map<EquivalentAddressGroup, Subchannel> subchannels = new HashMap<>();

    private final Random random;

    private ConnectivityState currentState;

    private RoundRobinPicker currentPicker = new EmptyPicker(EMPTY_OK);

    RoundRobinLoadBalancer(Helper helper) {
        this.helper = checkNotNull(helper, "helper");
        this.random = new Random();
    }

    /**
     * 处理服务名称解析获取的服务地址和数据
     *
     * @param resolvedAddresses the resolved server addresses, attributes, and config.
     *                          解析的 server 的地址、属性和配置
     */
    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {

        // 获取地址列表
        List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();

        // 当前的地址
        Set<EquivalentAddressGroup> currentAddrs = subchannels.keySet();

        // 将地址 List 转为 Map
        Map<EquivalentAddressGroup, EquivalentAddressGroup> latestAddrs = stripAttrs(servers);

        // 根据当前的地址，获取需要移除的地址，返回的地址是现有地址中有，新的地址中没有的
        Set<EquivalentAddressGroup> removedAddrs = setsDifference(currentAddrs, latestAddrs.keySet());


        for (Map.Entry<EquivalentAddressGroup, EquivalentAddressGroup> latestEntry : latestAddrs.entrySet()) {

            // 不含 Attributes 的 EquivalentAddressGroup
            EquivalentAddressGroup strippedAddressGroup = latestEntry.getKey();
            // 包含 Attributes 的 EquivalentAddressGroup
            EquivalentAddressGroup originalAddressGroup = latestEntry.getValue();

            // 根据地址获取对应的已经存在的 Subchannel
            Subchannel existingSubchannel = subchannels.get(strippedAddressGroup);

            // 如果存在已有的 Subchannel，则更新地址并跳出
            if (existingSubchannel != null) {
                // EAG's Attributes may have changed.
                // 更新地址
                existingSubchannel.updateAddresses(Collections.singletonList(originalAddressGroup));
                continue;
            }
            // 根据地址创建新的 Subchannel
            // Create new subchannels for new addresses.

            // NB(lukaszx0): we don't merge `attributes` with `subchannelAttr` because subchannel
            // doesn't need them. They're describing the resolved server list but we're not taking
            // any action based on this information.
            // 设置新的连接状态是 IDLE
            Attributes.Builder subchannelAttrs = Attributes.newBuilder()
                                                           // NB(lukaszx0): because attributes are immutable we can't set new value for the key
                                                           // after creation but since we can mutate the values we leverage that and set
                                                           // AtomicReference which will allow mutating state info for given channel.
                                                           .set(STATE_INFO, new Ref<>(ConnectivityStateInfo.forNonError(IDLE)));

            // 创建新 Subchannel
            final Subchannel subchannel = checkNotNull(helper.createSubchannel(CreateSubchannelArgs.newBuilder()
                                                                                                   .setAddresses(originalAddressGroup)
                                                                                                   .setAttributes(subchannelAttrs.build())
                                                                                                   .build()),
                    "subchannel");

            // 启动 Subchannel 状态监听器
            subchannel.start(new SubchannelStateListener() {
                @Override
                public void onSubchannelState(ConnectivityStateInfo state) {
                    // 处理状态变化
                    processSubchannelState(subchannel, state);
                }
            });

            // 将新创建的 Subchannel 放在 Subchannel 的 Map 中
            subchannels.put(strippedAddressGroup, subchannel);
            // 要求建立连接
            subchannel.requestConnection();
        }

        // 移除不包含的地址
        ArrayList<Subchannel> removedSubchannels = new ArrayList<>();
        for (EquivalentAddressGroup addressGroup : removedAddrs) {
            removedSubchannels.add(subchannels.remove(addressGroup));
        }

        // Update the picker before shutting down the subchannels, to reduce the chance of the race
        // between picking a subchannel and shutting it down.
        // 在关闭 Subchannel 之前更新 picker，减少关闭期间的风险
        updateBalancingState();

        // Shutdown removed subchannels
        // 关闭被移除的 Subchannel
        for (Subchannel removedSubchannel : removedSubchannels) {
            shutdownSubchannel(removedSubchannel);
        }
    }

    /**
     * 处理命名解析错误
     *
     * @param error a non-OK status 非 OK 的状态
     */
    @Override
    public void handleNameResolutionError(Status error) {
        // ready pickers aren't affected by status changes
        // 准备就绪的 picker 不受影响
        updateBalancingState(TRANSIENT_FAILURE, currentPicker instanceof ReadyPicker ? currentPicker : new EmptyPicker(error));
    }

    /**
     * 处理 Subchannel 连接状态变化
     *
     * @param subchannel Subchannel
     * @param stateInfo  连接状态
     */
    private void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {

        // 如果地址不是当前 SubChannel，则不处理
        if (subchannels.get(stripAttrs(subchannel.getAddresses())) != subchannel) {
            return;
        }

        // 如果当前是 IDLE 状态，则建立连接
        if (stateInfo.getState() == IDLE) {
            subchannel.requestConnection();
        }

        // 获取 Subchannel 连接状态
        Ref<ConnectivityStateInfo> subchannelStateRef = getSubchannelStateInfoRef(subchannel);

        // 如果连接状态是 TRANSIENT_FAILURE
        if (subchannelStateRef.value.getState().equals(TRANSIENT_FAILURE)) {
            // 如果 Subchannel 状态是 CONNECTING 或者 IDLE，则返回
            if (stateInfo.getState().equals(CONNECTING) || stateInfo.getState().equals(IDLE)) {
                return;
            }
        }
        // 更新为 Subchannel 状态
        subchannelStateRef.value = stateInfo;

        // 更新负载均衡状态
        updateBalancingState();
    }

    /**
     * 关闭 Subchannel
     */
    private void shutdownSubchannel(Subchannel subchannel) {
        // 关闭
        subchannel.shutdown();
        // 更新连接状态
        getSubchannelStateInfoRef(subchannel).value = ConnectivityStateInfo.forNonError(SHUTDOWN);
    }

    /**
     * 关闭负载均衡器
     */
    @Override
    public void shutdown() {
        // 遍历关闭所有的 Subchannel
        for (Subchannel subchannel : getSubchannels()) {
            shutdownSubchannel(subchannel);
        }
    }

    /**
     * 空的状态
     */
    private static final Status EMPTY_OK = Status.OK.withDescription("no subchannels ready");

    /**
     * Updates picker with the list of active subchannels (state == READY).
     * 根据 READY 状态的 Subchannel 集合更新 picker
     */
    @SuppressWarnings("ReferenceEquality")
    private void updateBalancingState() {
        // 过滤没有 ready 的 Subchannel
        List<Subchannel> activeList = filterNonFailingSubchannels(getSubchannels());

        // 如果过滤后的集合是空的
        if (activeList.isEmpty()) {

            // No READY subchannels, determine aggregate state and error status
            // 没有 READY 的 Subchannel，决定汇总状态或者错误状态
            boolean isConnecting = false;

            Status aggStatus = EMPTY_OK;

            // 遍历所有的 Subchannel
            for (Subchannel subchannel : getSubchannels()) {
                // 获取连接状态
                ConnectivityStateInfo stateInfo = getSubchannelStateInfoRef(subchannel).value;

                // This subchannel IDLE is not because of channel IDLE_TIMEOUT,
                // in which case LB is already shutdown.
                // RRLB will request connection immediately on subchannel IDLE.
                // 此 Subchannel 处于 IDLE 状态不是因为 IDLE_TIMEOUT，这种情况下 LB 已经关闭了
                // PRLB 会要求 Subchannel 立即建立连接
                if (stateInfo.getState() == CONNECTING || stateInfo.getState() == IDLE) {
                    isConnecting = true;
                }

                if (aggStatus == EMPTY_OK || !aggStatus.isOk()) {
                    aggStatus = stateInfo.getStatus();
                }

            }

            // 更新 LB 的状态
            // 如果是要求建立连接，则来进阶状态是 CONNECTING，否则是 TRANSIENT_FAILURE
            updateBalancingState(isConnecting ? CONNECTING : TRANSIENT_FAILURE,
                    // If all subchannels are TRANSIENT_FAILURE, return the Status associated with
                    // an arbitrary subchannel, otherwise return OK.
                    // 如果所有的 Subchannel 都是 TRANSIENT_FAILURE，返回状态关联的任意 Subchannel，否则返回 OK
                    new EmptyPicker(aggStatus));
        } else {
            // initialize the Picker to a random start index to ensure that a high frequency of Picker
            // churn does not skew subchannel selection.
            // 随机初始化 picker 的 index，确保频繁变动不会导致 Subchannel 倾斜
            int startIndex = random.nextInt(activeList.size());
            // 更新状态为 READY，并创建 ReadyPicker
            updateBalancingState(READY, new ReadyPicker(activeList, startIndex));
        }
    }

    /**
     * 更新负载均衡状态
     *
     * @param state  连接状态
     * @param picker 选择器
     */
    private void updateBalancingState(ConnectivityState state, RoundRobinPicker picker) {
        // 如果状态不同或者 picker 不同，则更新状态
        if (state != currentState || !picker.isEquivalentTo(currentPicker)) {
            helper.updateBalancingState(state, picker);
            currentState = state;
            currentPicker = picker;
        }
    }

    /**
     * Filters out non-ready subchannels.
     * 过滤没有 ready 的 Subchannel
     */
    private static List<Subchannel> filterNonFailingSubchannels(Collection<Subchannel> subchannels) {

        List<Subchannel> readySubchannels = new ArrayList<>(subchannels.size());

        // 遍历过滤 READY 状态 Subchannel
        for (Subchannel subchannel : subchannels) {
            if (isReady(subchannel)) {
                readySubchannels.add(subchannel);
            }
        }
        return readySubchannels;
    }

    /**
     * Converts list of {@link EquivalentAddressGroup} to {@link EquivalentAddressGroup} set and
     * remove all attributes. The values are the original EAGs.
     * <p>
     * 将 EquivalentAddressGroup List 转为 Map，移除所有属性，这些值是原始的 EquivalentAddressGroup
     * Map 的 key 是不包含 Attributes 的 EquivalentAddressGroup，value 是包含 Attributes 的 EquivalentAddressGroup
     */
    private static Map<EquivalentAddressGroup, EquivalentAddressGroup> stripAttrs(List<EquivalentAddressGroup> groupList) {
        Map<EquivalentAddressGroup, EquivalentAddressGroup> addrs = new HashMap<>(groupList.size() * 2);

        // 遍历，获取不包含 Attributes 属性的地址
        for (EquivalentAddressGroup group : groupList) {
            addrs.put(stripAttrs(group), group);
        }

        return addrs;
    }

    /**
     * 根据地址重新构建 EquivalentAddressGroup，移除 Attributes
     *
     * @param eag 原有的 EquivalentAddressGroup
     * @return 不包含 Attributes 的 EquivalentAddressGroup
     */
    private static EquivalentAddressGroup stripAttrs(EquivalentAddressGroup eag) {
        return new EquivalentAddressGroup(eag.getAddresses());
    }

    /**
     * 获取 Subchannel
     *
     * @return Subchannel 集合
     */
    @VisibleForTesting
    Collection<Subchannel> getSubchannels() {
        return subchannels.values();
    }

    /**
     * 获取 Subchannel 的连接状态
     *
     * @param subchannel Subchannel
     * @return 连接状态
     */
    private static Ref<ConnectivityStateInfo> getSubchannelStateInfoRef(Subchannel subchannel) {
        return checkNotNull(subchannel.getAttributes().get(STATE_INFO), "STATE_INFO");
    }

    /**
     * 返回 Subchannel 是否 READY
     */
    // package-private to avoid synthetic access
    static boolean isReady(Subchannel subchannel) {
        return getSubchannelStateInfoRef(subchannel).value.getState() == READY;
    }

    /**
     * 获取需要移除的地址
     *
     * @param a 当前地址
     * @param b 新的地址
     * @return 新地址中不包含的原有地址
     */
    private static <T> Set<T> setsDifference(Set<T> a, Set<T> b) {
        Set<T> aCopy = new HashSet<>(a);
        aCopy.removeAll(b);
        return aCopy;
    }

    // Only subclasses are ReadyPicker or EmptyPicker

    /**
     * 轮询策略的 Picker
     */
    private abstract static class RoundRobinPicker extends SubchannelPicker {
        abstract boolean isEquivalentTo(RoundRobinPicker picker);
    }

    /**
     * 已经 READY 的 Picker
     */
    @VisibleForTesting
    static final class ReadyPicker extends RoundRobinPicker {

        private static final AtomicIntegerFieldUpdater<ReadyPicker> indexUpdater = AtomicIntegerFieldUpdater.newUpdater(ReadyPicker.class, "index");

        private final List<Subchannel> list; // non-empty
        @SuppressWarnings("unused")
        private volatile int index;

        /**
         * 构造可用的 subchannel picker
         *
         * @param list       可用的 Subchannel
         * @param startIndex 开始的下标
         */
        ReadyPicker(List<Subchannel> list, int startIndex) {
            Preconditions.checkArgument(!list.isEmpty(), "empty list");
            this.list = list;
            this.index = startIndex - 1;
        }

        /**
         * 选取要调用的 Subchannel
         * 直接选取下一个
         *
         * @param args the pick arguments
         * @return
         */
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            return PickResult.withSubchannel(nextSubchannel());
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(ReadyPicker.class).add("list", list).toString();
        }

        /**
         * 轮询算法，选取下一个 Subchannel
         *
         * @return
         */
        private Subchannel nextSubchannel() {
            int size = list.size();
            int i = indexUpdater.incrementAndGet(this);
            if (i >= size) {
                int oldi = i;
                i %= size;
                indexUpdater.compareAndSet(this, oldi, i);
            }
            return list.get(i);
        }

        /**
         * 返回 Subchannel
         *
         * @return Subchannel 集合
         */
        @VisibleForTesting
        List<Subchannel> getList() {
            return list;
        }

        /**
         * 比较两个 Picker 是否相等
         *
         * @param picker 被比较的 picker
         * @return 是否相等
         */
        @Override
        boolean isEquivalentTo(RoundRobinPicker picker) {
            if (!(picker instanceof ReadyPicker)) {
                return false;
            }
            ReadyPicker other = (ReadyPicker) picker;
            // the lists cannot contain duplicate subchannels
            return other == this ||
                    (list.size() == other.list.size() &&
                            new HashSet<>(list).containsAll(other.list));
        }
    }

    /**
     * 空的 Picker
     */
    @VisibleForTesting
    static final class EmptyPicker extends RoundRobinPicker {

        // 状态
        private final Status status;

        /**
         * 根据状态构建空的 Picker
         *
         * @param status 状态
         */
        EmptyPicker(@Nonnull Status status) {
            this.status = Preconditions.checkNotNull(status, "status");
        }

        /**
         * 根据参数选择 Subchannel
         *
         * @param args 选择参数
         * @return 选择的结果
         */
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            // 如果状态是 OK，则返回没有结果的 Picker，否则返回错误
            return status.isOk() ? PickResult.withNoResult() : PickResult.withError(status);
        }

        /**
         * 判断两个 Picker 是否相等
         *
         * @param picker 被比较的 picker
         * @return 比较结果
         */
        @Override
        boolean isEquivalentTo(RoundRobinPicker picker) {
            // 如果 picker 状态相等
            return picker instanceof EmptyPicker &&
                    (Objects.equal(status, ((EmptyPicker) picker).status) ||
                            (status.isOk() && ((EmptyPicker) picker).status.isOk()));
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(EmptyPicker.class).add("status", status).toString();
        }
    }

    /**
     * A lighter weight Reference than AtomicReference.
     * 轻量的 AtomicReference
     */
    @VisibleForTesting
    static final class Ref<T> {
        T value;

        Ref(T value) {
            this.value = value;
        }
    }
}
