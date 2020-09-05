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

import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Factory;
import io.grpc.LoadBalancer.Helper;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;

/**
 * Utility for enabling
 * <a href="https://github.com/grpc/proposal/blob/master/A17-client-side-health-checking.md">
 * client-side health checking</a> for {@link LoadBalancer}s.
 * <p>
 * 支持客户端健康检查的工具
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/5025")
public final class HealthCheckingLoadBalancerUtil {

    private HealthCheckingLoadBalancerUtil() {
    }

    /**
     * Creates a health-checking-capable LoadBalancer.  This method is used to implement
     * health-checking-capable {@link Factory}s, which will typically written this way:
     * 创建一个具有健康检查能力的负载均衡提供器
     *
     * <pre>
     * public class HealthCheckingFooLbFactory extends LoadBalancer.Factory {
     *   // This is the original balancer implementation that doesn't have health checking
     *   private final LoadBalancer.Factory fooLbFactory;
     *
     *   ...
     *
     *   // Returns the health-checking-capable version of FooLb
     *   public LoadBalancer newLoadBalancer(Helper helper) {
     *     return HealthCheckingLoadBalancerUtil.newHealthCheckingLoadBalancer(fooLbFactory, helper);
     *   }
     * }
     * </pre>
     *
     * <p>As a requirement for the original LoadBalancer, it must call
     * {@code Helper.createSubchannel()} from the {@link
     * io.grpc.LoadBalancer.Helper#getSynchronizationContext() Synchronization Context}, or
     * {@code createSubchannel()} will throw.
     * <p>
     * 必须通过 Synchronization Context 调用 Helper.createSubchannel() 创建实例
     *
     * @param factory the original factory that implements load-balancing logic without health
     *                checking
     *                实现了负载均衡但是没有实现健康检查的工厂
     * @param helper  the helper passed to the resulting health-checking LoadBalancer.
     *                Helper 将健康检查的结果传递给 LoadBalancer
     */
    public static LoadBalancer newHealthCheckingLoadBalancer(Factory factory, Helper helper) {
        // 创建工厂
        HealthCheckingLoadBalancerFactory hcFactory = new HealthCheckingLoadBalancerFactory(factory,
                new ExponentialBackoffPolicy.Provider(),
                GrpcUtil.STOPWATCH_SUPPLIER);

        // 使用工厂创建 LoadBalancer
        return hcFactory.newLoadBalancer(helper);
    }
}
