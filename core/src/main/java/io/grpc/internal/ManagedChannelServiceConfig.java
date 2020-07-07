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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import io.grpc.MethodDescriptor;
import io.grpc.internal.RetriableStream.Throttle;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link ManagedChannelServiceConfig} is a fully parsed and validated representation of service
 * configuration data.
 * 服务配置对象，校验配置
 */
final class ManagedChannelServiceConfig {

    @Nullable
    private final MethodInfo defaultMethodConfig;
    private final Map<String, MethodInfo> serviceMethodMap;
    private final Map<String, MethodInfo> serviceMap;
    @Nullable
    private final Throttle retryThrottling;
    @Nullable
    private final Object loadBalancingConfig;
    @Nullable
    private final Map<String, ?> healthCheckingConfig;

    /**
     * 根据配置获取到的策略，构造 ManagedChannelServiceConfig 对象
     *
     * @param defaultMethodConfig
     * @param serviceMethodMap
     * @param serviceMap
     * @param retryThrottling
     * @param loadBalancingConfig
     * @param healthCheckingConfig
     */
    ManagedChannelServiceConfig(
            @Nullable MethodInfo defaultMethodConfig,
            Map<String, MethodInfo> serviceMethodMap,
            Map<String, MethodInfo> serviceMap,
            @Nullable Throttle retryThrottling,
            @Nullable Object loadBalancingConfig,
            @Nullable Map<String, ?> healthCheckingConfig) {
        this.defaultMethodConfig = defaultMethodConfig;
        this.serviceMethodMap = Collections.unmodifiableMap(new HashMap<>(serviceMethodMap));
        this.serviceMap = Collections.unmodifiableMap(new HashMap<>(serviceMap));
        this.retryThrottling = retryThrottling;
        this.loadBalancingConfig = loadBalancingConfig;
        this.healthCheckingConfig = healthCheckingConfig != null ? Collections.unmodifiableMap(new HashMap<>(healthCheckingConfig)) : null;
    }

    /**
     * Returns an empty {@link ManagedChannelServiceConfig}.
     * 返回空的配置
     */
    static ManagedChannelServiceConfig empty() {
        return new ManagedChannelServiceConfig(
                        null,
                        new HashMap<String, MethodInfo>(),
                        new HashMap<String, MethodInfo>(),
                        /* retryThrottling= */ null,
                        /* loadBalancingConfig= */ null,
                        /* healthCheckingConfig= */ null);
    }

    /**
     * Parses the Channel level config values (e.g. excludes load balancing)
     * 解析 Channel 级别的配置
     */
    static ManagedChannelServiceConfig fromServiceConfig(
            Map<String, ?> serviceConfig,
            boolean retryEnabled,
            int maxRetryAttemptsLimit,
            int maxHedgedAttemptsLimit,
            @Nullable Object loadBalancingConfig) {

        Throttle retryThrottling = null;
        // 如果开启了重试，则加载节流配置
        if (retryEnabled) {
            retryThrottling = ServiceConfigUtil.getThrottlePolicy(serviceConfig);
        }

        Map<String, MethodInfo> serviceMethodMap = new HashMap<>();
        Map<String, MethodInfo> serviceMap = new HashMap<>();
        // 健康检查配置
        Map<String, ?> healthCheckingConfig = ServiceConfigUtil.getHealthCheckedService(serviceConfig);

        // Try and do as much validation here before we swap out the existing configuration.  In case
        // the input is invalid, we don't want to lose the existing configuration.
        // 获取方法配置
        List<Map<String, ?>> methodConfigs = ServiceConfigUtil.getMethodConfigFromServiceConfig(serviceConfig);

        // 如果 methodConfig 为空，则构建并返回服务的配置
        if (methodConfigs == null) {
            // this is surprising, but possible.
            return new ManagedChannelServiceConfig(
                    null,
                    serviceMethodMap,
                    serviceMap,
                    retryThrottling,
                    loadBalancingConfig,
                    healthCheckingConfig);
        }

        MethodInfo defaultMethodConfig = null;
        // 遍历所有的方法配置
        for (Map<String, ?> methodConfig : methodConfigs) {
            // 构建方法配置
            MethodInfo info = new MethodInfo(methodConfig, retryEnabled, maxRetryAttemptsLimit, maxHedgedAttemptsLimit);

            // 获取 name 数组
            List<Map<String, ?>> nameList = ServiceConfigUtil.getNameListFromMethodConfig(methodConfig);

            if (nameList == null || nameList.isEmpty()) {
                continue;
            }

            // 遍历所有的 name 元素
            for (Map<String, ?> name : nameList) {
                // 服务名
                String serviceName = ServiceConfigUtil.getServiceFromName(name);
                // 方法名
                String methodName = ServiceConfigUtil.getMethodFromName(name);

                // 如果服务名为空，则检查方法名，并设置默认配置
                if (Strings.isNullOrEmpty(serviceName)) {
                    checkArgument(Strings.isNullOrEmpty(methodName), "missing service name for method %s", methodName);
                    checkArgument(defaultMethodConfig == null, "Duplicate default method config in service config %s", serviceConfig);
                    defaultMethodConfig = info;
                } else if (Strings.isNullOrEmpty(methodName)) {
                    // Service scoped config
                    // 如果服务名不为空，但方法名为空，则检查服务名是否有重复，并加入 map 中
                    checkArgument(!serviceMap.containsKey(serviceName), "Duplicate service %s", serviceName);
                    serviceMap.put(serviceName, info);
                } else {
                    // Method scoped config
                    // 如果都不为空，则构建完整的方法名
                    String fullMethodName = MethodDescriptor.generateFullMethodName(serviceName, methodName);
                    // 如果不重复，则加入 map 中
                    checkArgument(!serviceMethodMap.containsKey(fullMethodName), "Duplicate method name %s", fullMethodName);
                    serviceMethodMap.put(fullMethodName, info);
                }
            }
        }

        return new ManagedChannelServiceConfig(
                defaultMethodConfig,
                serviceMethodMap,
                serviceMap,
                retryThrottling,
                loadBalancingConfig,
                healthCheckingConfig);
    }

    /**
     * Returns the per-service configuration for the channel.
     */
    Map<String, MethodInfo> getServiceMap() {
        return serviceMap;
    }

    @Nullable
    Map<String, ?> getHealthCheckingConfig() {
        return healthCheckingConfig;
    }

    /**
     * Returns the per-method configuration for the channel.
     */
    Map<String, MethodInfo> getServiceMethodMap() {
        return serviceMethodMap;
    }

    @Nullable
    MethodInfo getDefaultMethodConfig() {
        return defaultMethodConfig;
    }

    @VisibleForTesting
    @Nullable
    Object getLoadBalancingConfig() {
        return loadBalancingConfig;
    }

    @Nullable
    Throttle getRetryThrottling() {
        return retryThrottling;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ManagedChannelServiceConfig that = (ManagedChannelServiceConfig) o;
        return Objects.equal(serviceMethodMap, that.serviceMethodMap)
                && Objects.equal(serviceMap, that.serviceMap)
                && Objects.equal(retryThrottling, that.retryThrottling)
                && Objects.equal(loadBalancingConfig, that.loadBalancingConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(serviceMethodMap, serviceMap, retryThrottling, loadBalancingConfig);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serviceMethodMap", serviceMethodMap)
                          .add("serviceMap", serviceMap)
                          .add("retryThrottling", retryThrottling)
                          .add("loadBalancingConfig", loadBalancingConfig)
                          .toString();
    }

    /**
     * Equivalent of MethodConfig from a ServiceConfig with restrictions from Channel setting.
     * 等效于ServiceConfig中的MethodConfig，但具有Channel设置的限制
     */
    static final class MethodInfo {
        // TODO(carl-mastrangelo): add getters for these fields and make them private.
        final Long timeoutNanos;
        final Boolean waitForReady;
        final Integer maxInboundMessageSize;
        final Integer maxOutboundMessageSize;
        final RetryPolicy retryPolicy;
        final HedgingPolicy hedgingPolicy;

        /**
         * Constructor.
         *
         * @param retryEnabled when false, the argument maxRetryAttemptsLimit will have no effect.
         *                     当 retryEnabled 是 false 时，maxRetryAttemptsLimit 无效
         */
        MethodInfo(Map<String, ?> methodConfig,
                   boolean retryEnabled,
                   int maxRetryAttemptsLimit,
                   int maxHedgedAttemptsLimit) {
            // 超时时间
            timeoutNanos = ServiceConfigUtil.getTimeoutFromMethodConfig(methodConfig);
            // 是否等待就绪
            waitForReady = ServiceConfigUtil.getWaitForReadyFromMethodConfig(methodConfig);
            // 最大返回消息字节
            maxInboundMessageSize = ServiceConfigUtil.getMaxResponseMessageBytesFromMethodConfig(methodConfig);
            if (maxInboundMessageSize != null) {
                checkArgument(maxInboundMessageSize >= 0, "maxInboundMessageSize %s exceeds bounds", maxInboundMessageSize);
            }

            // 最大发送消息字节
            maxOutboundMessageSize = ServiceConfigUtil.getMaxRequestMessageBytesFromMethodConfig(methodConfig);
            if (maxOutboundMessageSize != null) {
                checkArgument(maxOutboundMessageSize >= 0, "maxOutboundMessageSize %s exceeds bounds", maxOutboundMessageSize);
            }

            // 重试配置，如果重试没有开启或者没有配置就使用默认的
            Map<String, ?> retryPolicyMap = retryEnabled ? ServiceConfigUtil.getRetryPolicyFromMethodConfig(methodConfig) : null;
            retryPolicy = retryPolicyMap == null ? RetryPolicy.DEFAULT : retryPolicy(retryPolicyMap, maxRetryAttemptsLimit);

            // 对冲策略，如果重试没有开启或没有配置，就使用默认的
            Map<String, ?> hedgingPolicyMap = retryEnabled ? ServiceConfigUtil.getHedgingPolicyFromMethodConfig(methodConfig) : null;
            hedgingPolicy = hedgingPolicyMap == null ? HedgingPolicy.DEFAULT : hedgingPolicy(hedgingPolicyMap, maxHedgedAttemptsLimit);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(
                    timeoutNanos,
                    waitForReady,
                    maxInboundMessageSize,
                    maxOutboundMessageSize,
                    retryPolicy,
                    hedgingPolicy);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MethodInfo)) {
                return false;
            }
            MethodInfo that = (MethodInfo) other;
            return Objects.equal(this.timeoutNanos, that.timeoutNanos)
                    && Objects.equal(this.waitForReady, that.waitForReady)
                    && Objects.equal(this.maxInboundMessageSize, that.maxInboundMessageSize)
                    && Objects.equal(this.maxOutboundMessageSize, that.maxOutboundMessageSize)
                    && Objects.equal(this.retryPolicy, that.retryPolicy)
                    && Objects.equal(this.hedgingPolicy, that.hedgingPolicy);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("timeoutNanos", timeoutNanos)
                              .add("waitForReady", waitForReady)
                              .add("maxInboundMessageSize", maxInboundMessageSize)
                              .add("maxOutboundMessageSize", maxOutboundMessageSize)
                              .add("retryPolicy", retryPolicy)
                              .add("hedgingPolicy", hedgingPolicy)
                              .toString();
        }

        /**
         * 校验参数，构建重试策略对象
         *
         * @param retryPolicy
         * @param maxAttemptsLimit
         * @return
         */
        private static RetryPolicy retryPolicy(Map<String, ?> retryPolicy, int maxAttemptsLimit) {
            // 最大重试数量
            int maxAttempts = checkNotNull(ServiceConfigUtil.getMaxAttemptsFromRetryPolicy(retryPolicy), "maxAttempts cannot be empty");
            checkArgument(maxAttempts >= 2, "maxAttempts must be greater than 1: %s", maxAttempts);
            maxAttempts = Math.min(maxAttempts, maxAttemptsLimit);

            // 初始延迟时间
            long initialBackoffNanos = checkNotNull(ServiceConfigUtil.getInitialBackoffNanosFromRetryPolicy(retryPolicy), "initialBackoff cannot be empty");
            checkArgument(initialBackoffNanos > 0, "initialBackoffNanos must be greater than 0: %s", initialBackoffNanos);

            // 最大延迟时间
            long maxBackoffNanos = checkNotNull(ServiceConfigUtil.getMaxBackoffNanosFromRetryPolicy(retryPolicy), "maxBackoff cannot be empty");
            checkArgument(maxBackoffNanos > 0, "maxBackoff must be greater than 0: %s", maxBackoffNanos);

            // 退避指数
            double backoffMultiplier = checkNotNull(ServiceConfigUtil.getBackoffMultiplierFromRetryPolicy(retryPolicy), "backoffMultiplier cannot be empty");
            checkArgument(backoffMultiplier > 0, "backoffMultiplier must be greater than 0: %s", backoffMultiplier);

            // 构建重试策略对象
            return new RetryPolicy(maxAttempts,
                    initialBackoffNanos,
                    maxBackoffNanos,
                    backoffMultiplier,
                    ServiceConfigUtil.getRetryableStatusCodesFromRetryPolicy(retryPolicy));
        }

        /**
         * 校验参数，构建对冲策略
         *
         * @param hedgingPolicy
         * @param maxAttemptsLimit
         * @return
         */
        private static HedgingPolicy hedgingPolicy(Map<String, ?> hedgingPolicy, int maxAttemptsLimit) {
            // 最大重试次数
            int maxAttempts = checkNotNull(ServiceConfigUtil.getMaxAttemptsFromHedgingPolicy(hedgingPolicy), "maxAttempts cannot be empty");
            checkArgument(maxAttempts >= 2, "maxAttempts must be greater than 1: %s", maxAttempts);
            maxAttempts = Math.min(maxAttempts, maxAttemptsLimit);

            // 对冲延迟时间
            long hedgingDelayNanos = checkNotNull(ServiceConfigUtil.getHedgingDelayNanosFromHedgingPolicy(hedgingPolicy), "hedgingDelay cannot be empty");
            checkArgument(hedgingDelayNanos >= 0, "hedgingDelay must not be negative: %s", hedgingDelayNanos);

            return new HedgingPolicy(maxAttempts,
                    hedgingDelayNanos,
                    ServiceConfigUtil.getNonFatalStatusCodesFromHedgingPolicy(hedgingPolicy));
        }
    }
}
