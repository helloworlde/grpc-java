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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.MethodDescriptor;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Verify.verify;

/**
 * 方法配置拦截器
 * Modifies RPCs in conformance with a Service Config.
 */
final class ServiceConfigInterceptor implements ClientInterceptor {

    // Map from method name to MethodInfo
    // 保存相应方法的配置
    @VisibleForTesting
    final AtomicReference<ManagedChannelServiceConfig> managedChannelServiceConfig = new AtomicReference<>();

    // 是否开启重试
    private final boolean retryEnabled;

    // Setting this to true and observing this equal to true are run in different threads.
    // 是否初始化完成
    private volatile boolean initComplete;

    ServiceConfigInterceptor(boolean retryEnabled) {
        this.retryEnabled = retryEnabled;
    }

    // 更新服务配置
    void handleUpdate(@Nullable ManagedChannelServiceConfig serviceConfig) {
        managedChannelServiceConfig.set(serviceConfig);
        initComplete = true;
    }

    // 重试策略 key
    static final CallOptions.Key<RetryPolicy.Provider> RETRY_POLICY_KEY = CallOptions.Key.create("internal-retry-policy");
    // 对冲策略 key
    static final CallOptions.Key<HedgingPolicy.Provider> HEDGING_POLICY_KEY = CallOptions.Key.create("internal-hedging-policy");

    /**
     * BlockingStub 执行请求顺序: 4
     * 拦截器顺序: 1
     */
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(final MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        // 开启了重试
        if (retryEnabled) {
            // 已调用 handleUpdate 更新配置
            if (initComplete) {
                // 根据方法获取重试策略
                final RetryPolicy retryPolicy = getRetryPolicyFromConfig(method);
                final class ImmediateRetryPolicyProvider implements RetryPolicy.Provider {
                    @Override
                    public RetryPolicy get() {
                        return retryPolicy;
                    }
                }

                // 获取对冲策略
                final HedgingPolicy hedgingPolicy = getHedgingPolicyFromConfig(method);
                final class ImmediateHedgingPolicyProvider implements HedgingPolicy.Provider {
                    @Override
                    public HedgingPolicy get() {
                        return hedgingPolicy;
                    }
                }

                // 如果同时开启了重试和对冲 则校验失败
                verify(retryPolicy.equals(RetryPolicy.DEFAULT) || hedgingPolicy.equals(HedgingPolicy.DEFAULT),
                        "Can not apply both retry and hedging policy for the method '%s'", method);

                // 设置相应的策略
                callOptions = callOptions
                        .withOption(RETRY_POLICY_KEY, new ImmediateRetryPolicyProvider())
                        .withOption(HEDGING_POLICY_KEY, new ImmediateHedgingPolicyProvider());
            } else {
                final class DelayedRetryPolicyProvider implements RetryPolicy.Provider {
                    /**
                     * Returns RetryPolicy.DEFAULT if name resolving is not complete at the moment the method
                     * is invoked, otherwise returns the RetryPolicy computed from service config.
                     *
                     * 如果在调用方法时名称解析尚未完成，则返回 RetryPolicy.DEFAULT，否则返回根据服务配置获取策略
                     *
                     *  <p>Note that this method is used no more than once for each call.
                     */
                    @Override
                    public RetryPolicy get() {
                        if (!initComplete) {
                            return RetryPolicy.DEFAULT;
                        }
                        return getRetryPolicyFromConfig(method);
                    }
                }

                final class DelayedHedgingPolicyProvider implements HedgingPolicy.Provider {
                    /**
                     * Returns HedgingPolicy.DEFAULT if name resolving is not complete at the moment the
                     * method is invoked, otherwise returns the HedgingPolicy computed from service config.
                     *
                     *如果在调用方法时名称解析尚未完成，则返回 HedgingPolicy.DEFAULT，否则返回根据服务配置获取策略
                     * <p>Note that this method is used no more than once for each call.
                     */
                    @Override
                    public HedgingPolicy get() {
                        if (!initComplete) {
                            return HedgingPolicy.DEFAULT;
                        }
                        HedgingPolicy hedgingPolicy = getHedgingPolicyFromConfig(method);

                        verify(hedgingPolicy.equals(HedgingPolicy.DEFAULT) || getRetryPolicyFromConfig(method).equals(RetryPolicy.DEFAULT),
                                "Can not apply both retry and hedging policy for the method '%s'", method);
                        return hedgingPolicy;
                    }
                }

                callOptions = callOptions
                        .withOption(RETRY_POLICY_KEY, new DelayedRetryPolicyProvider())
                        .withOption(HEDGING_POLICY_KEY, new DelayedHedgingPolicyProvider());
            }
        }

        //  如果没有相应的方法配置，则直接调用
        MethodInfo info = getMethodInfo(method);
        if (info == null) {
            return next.newCall(method, callOptions);
        }

        // 如果有超时，则设置超时时间
        if (info.timeoutNanos != null) {
            Deadline newDeadline = Deadline.after(info.timeoutNanos, TimeUnit.NANOSECONDS);
            Deadline existingDeadline = callOptions.getDeadline();
            // If the new deadline is sooner than the existing deadline, swap them.
            if (existingDeadline == null || newDeadline.compareTo(existingDeadline) < 0) {
                callOptions = callOptions.withDeadline(newDeadline);
            }
        }
        // 如果开启了 waitForReady
        if (info.waitForReady != null) {
            callOptions = info.waitForReady ? callOptions.withWaitForReady() : callOptions.withoutWaitForReady();
        }

        // 设置最大请求字节数
        if (info.maxInboundMessageSize != null) {
            Integer existingLimit = callOptions.getMaxInboundMessageSize();
            if (existingLimit != null) {
                callOptions = callOptions.withMaxInboundMessageSize(Math.min(existingLimit, info.maxInboundMessageSize));
            } else {
                callOptions = callOptions.withMaxInboundMessageSize(info.maxInboundMessageSize);
            }
        }

        // 设置最大接收字节数
        if (info.maxOutboundMessageSize != null) {
            Integer existingLimit = callOptions.getMaxOutboundMessageSize();
            if (existingLimit != null) {
                callOptions = callOptions.withMaxOutboundMessageSize(
                        Math.min(existingLimit, info.maxOutboundMessageSize));
            } else {
                callOptions = callOptions.withMaxOutboundMessageSize(info.maxOutboundMessageSize);
            }
        }

        // 继续后续调用
        return next.newCall(method, callOptions);
    }

    /**
     * 根据方法获取配置
     *
     * @param method
     * @return
     */
    @CheckForNull
    private MethodInfo getMethodInfo(MethodDescriptor<?, ?> method) {
        // 获取配置，如果没有则返回
        ManagedChannelServiceConfig mcsc = managedChannelServiceConfig.get();
        if (mcsc == null) {
            return null;
        }
        // 根据方法限定名称从 map 中获取
        MethodInfo info;
        info = mcsc.getServiceMethodMap().get(method.getFullMethodName());
        // 如果为空，则根据方法名称获取
        if (info == null) {
            String serviceName = method.getServiceName();
            info = mcsc.getServiceMap().get(serviceName);
        }
        // 如果依然为空，则使用默认的
        if (info == null) {
            info = mcsc.getDefaultMethodConfig();
        }
        return info;
    }

    /**
     * 根据方法获取相应的配置
     *
     * @param method
     * @return
     */
    @VisibleForTesting
    RetryPolicy getRetryPolicyFromConfig(MethodDescriptor<?, ?> method) {
        MethodInfo info = getMethodInfo(method);
        return info == null ? RetryPolicy.DEFAULT : info.retryPolicy;
    }

    /**
     * 根据方法获取对冲策略
     *
     * @param method
     * @return
     */
    @VisibleForTesting
    HedgingPolicy getHedgingPolicyFromConfig(MethodDescriptor<?, ?> method) {
        MethodInfo info = getMethodInfo(method);
        return info == null ? HedgingPolicy.DEFAULT : info.hedgingPolicy;
    }
}
