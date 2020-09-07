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

package io.grpc.util;

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;

import java.util.Map;

/**
 * Provider for the "round_robin" balancing policy.
 * 提供 round_robin 负载均衡策略
 */
// Make it package-private so that it cannot be directly referenced by users.  Java service loader
// requires the provider to be public, but we can hide it under a package-private class.
// 设置为包级别私有，防止用户直接使用，Java 服务加载机制要求提供器是 public 的，但是可以隐藏在包级别私有的类中
final class SecretRoundRobinLoadBalancerProvider {

    private SecretRoundRobinLoadBalancerProvider() {
    }

    public static final class Provider extends LoadBalancerProvider {

        private static final String NO_CONFIG = "no service config";


        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int getPriority() {
            return 5;
        }

        @Override
        public String getPolicyName() {
            return "round_robin";
        }

        /**
         * 创建负载均衡实例
         */
        @Override
        public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
            return new RoundRobinLoadBalancer(helper);
        }


        /**
         * 解析负载均衡策略的配置
         *
         * @param rawLoadBalancingPolicyConfig The {@link Map} representation of the load balancing
         *                                     policy choice.
         *                                     代表负载均衡策略
         * @return 配置
         */
        @Override
        public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingPolicyConfig) {
            return ConfigOrError.fromConfig(NO_CONFIG);
        }
    }
}
