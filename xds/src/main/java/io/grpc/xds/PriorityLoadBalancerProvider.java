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

import com.google.common.base.MoreObjects;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Provider for priority load balancing policy.
 * 提供优先级负载均衡策略
 */
final class PriorityLoadBalancerProvider extends LoadBalancerProvider {

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public int getPriority() {
        return 5;
    }

    /**
     * 策略名称
     */
    @Override
    public String getPolicyName() {
        return "priority_experimental";
    }

    /**
     * 构造新的 LB
     */
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
        return new PriorityLoadBalancer(helper);
    }

    @Override
    public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
        throw new UnsupportedOperationException();
    }

    /**
     * 优先级策略配置
     */
    static final class PriorityLbConfig {

        final Map<String, PolicySelection> childConfigs;

        final List<String> priorities;

        PriorityLbConfig(Map<String, PolicySelection> childConfigs, List<String> priorities) {
            this.childConfigs = Collections.unmodifiableMap(checkNotNull(childConfigs, "childConfigs"));
            this.priorities = Collections.unmodifiableList(checkNotNull(priorities, "priorities"));

            checkArgument(!priorities.isEmpty(), "priority list is empty");
            checkArgument(childConfigs.keySet().containsAll(priorities), "missing child config for at lease one of the priorities");
            checkArgument(priorities.size() == new HashSet<>(priorities).size(), "duplicate names in priorities");
            checkArgument(priorities.size() == childConfigs.keySet().size(), "some names in childConfigs are not referenced by priorities");
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("childConfigs", childConfigs)
                              .add("priorities", priorities)
                              .toString();
        }
    }
}
