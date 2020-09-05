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

package io.grpc.services.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.services.HealthCheckingLoadBalancerUtil;

import java.util.Map;

/**
 * The health-check-capable provider for the "round_robin" balancing policy.  This overrides
 * the "round_robin" provided by grpc-core.
 * 具有健康检查能力的 round_robin 策略的负载均衡提供器，覆盖了 grpc-core 提供的 round_robin
 */
@Internal
public final class HealthCheckingRoundRobinLoadBalancerProvider extends LoadBalancerProvider {

    private final LoadBalancerProvider rrProvider;


    /**
     * 创建负载均衡提供器
     */
    public HealthCheckingRoundRobinLoadBalancerProvider() {
        rrProvider = newRoundRobinProvider();
    }

    @Override
    public boolean isAvailable() {
        return rrProvider.isAvailable();
    }

    /**
     * 优先级
     */
    @Override
    public int getPriority() {
        return rrProvider.getPriority() + 1;
    }

    @Override
    public String getPolicyName() {
        return rrProvider.getPolicyName();
    }

    /**
     * 根据 Helper 创建负载均衡实例
     */
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
        return HealthCheckingLoadBalancerUtil.newHealthCheckingLoadBalancer(rrProvider, helper);
    }

    @Override
    public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingPolicyConfig) {
        return rrProvider.parseLoadBalancingPolicyConfig(rawLoadBalancingPolicyConfig);
    }

    /**
     * 创建负载均衡提供器
     *
     * @return 负载均衡提供器
     */
    @VisibleForTesting
    static LoadBalancerProvider newRoundRobinProvider() {
        try {
            // 加载提供器类
            Class<? extends LoadBalancerProvider> rrProviderClass = Class.forName("io.grpc.util.SecretRoundRobinLoadBalancerProvider$Provider")
                                                                         .asSubclass(LoadBalancerProvider.class);

            // 创建实例
            return rrProviderClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            Throwables.throwIfUnchecked(e);
            throw new RuntimeException(e);
        }
    }
}
