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

import com.google.common.collect.ImmutableSet;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.MethodDescriptor;
import io.grpc.Status.Code;
import io.grpc.internal.RetriableStream.Throttle;
import io.grpc.testing.TestMethodDescriptors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.internal.ServiceConfigInterceptor.RETRY_POLICY_KEY;
import static java.lang.Double.parseDouble;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for RetryPolicy.
 */
@RunWith(JUnit4.class)
public class RetryPolicyTest {

    /**
     * 重试策略配置
     *
     * @throws Exception
     */
    @Test
    public void getRetryPolicies() throws Exception {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(RetryPolicyTest.class.getResourceAsStream(
                    "/io/grpc/internal/test_retry_service_config.json"), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            Object serviceConfigObj = JsonParser.parse(sb.toString());
            assertTrue(serviceConfigObj instanceof Map);

            @SuppressWarnings("unchecked")
            Map<String, ?> serviceConfig = (Map<String, ?>) serviceConfigObj;

            ServiceConfigInterceptor serviceConfigInterceptor =
                    new ServiceConfigInterceptor(/* retryEnabled= */ true);

            serviceConfigInterceptor.handleUpdate(ManagedChannelServiceConfig
                    .fromServiceConfig(
                            serviceConfig,
                            /* retryEnabled= */ true,
                            /* maxRetryAttemptsLimit= */ 4,
                            /* maxHedgedAttemptsLimit= */ 3,
                            /* loadBalancingConfig= */ null));

            MethodDescriptor.Builder<Void, Void> builder = TestMethodDescriptors.voidMethod().toBuilder();

            // 服务和方法不存在，使用默认策略
            MethodDescriptor<Void, Void> method = builder.setFullMethodName("not/exist").build();
            assertEquals(RetryPolicy.DEFAULT, serviceConfigInterceptor.getRetryPolicyFromConfig(method));

            // 服务不存在，使用默认配置
            method = builder.setFullMethodName("not_exist/Foo1").build();
            assertEquals(RetryPolicy.DEFAULT, serviceConfigInterceptor.getRetryPolicyFromConfig(method));

            // 服务存在，方法不存在，使用服务配置
            method = builder.setFullMethodName("SimpleService1/not_exist").build();
            assertEquals(new RetryPolicy(
                            3,
                            TimeUnit.MILLISECONDS.toNanos(2100),
                            TimeUnit.MILLISECONDS.toNanos(2200),
                            parseDouble("3"),
                            ImmutableSet.of(Code.UNAVAILABLE, Code.RESOURCE_EXHAUSTED)),
                    serviceConfigInterceptor.getRetryPolicyFromConfig(method));

            // 服务和方法存在，使用有方法的指定配置
            method = builder.setFullMethodName("SimpleService1/Foo1").build();
            assertEquals(
                    new RetryPolicy(
                            4,
                            TimeUnit.MILLISECONDS.toNanos(100),
                            TimeUnit.MILLISECONDS.toNanos(1000),
                            parseDouble("2"),
                            ImmutableSet.of(Code.UNAVAILABLE)),
                    serviceConfigInterceptor.getRetryPolicyFromConfig(method));

            // 服务存在方法不存在，使用默认配置
            method = builder.setFullMethodName("SimpleService2/not_exist").build();
            assertEquals(RetryPolicy.DEFAULT, serviceConfigInterceptor.getRetryPolicyFromConfig(method));

            // 服务方法都存在，使用指定配置
            method = builder.setFullMethodName("SimpleService2/Foo2").build();
            assertEquals(new RetryPolicy(
                            4,
                            TimeUnit.MILLISECONDS.toNanos(100),
                            TimeUnit.MILLISECONDS.toNanos(1000),
                            parseDouble("2"),
                            ImmutableSet.of(Code.UNAVAILABLE)),
                    serviceConfigInterceptor.getRetryPolicyFromConfig(method));
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    /**
     * 关闭重试策略
     *
     * @throws Exception
     */
    @Test
    public void getRetryPolicies_retryDisabled() throws Exception {
        Channel channel = mock(Channel.class);
        ArgumentCaptor<CallOptions> callOptionsCap = ArgumentCaptor.forClass(CallOptions.class);
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(RetryPolicyTest.class.getResourceAsStream(
                    "/io/grpc/internal/test_retry_service_config.json"), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            Object serviceConfigObj = JsonParser.parse(sb.toString());
            assertTrue(serviceConfigObj instanceof Map);

            @SuppressWarnings("unchecked")
            Map<String, ?> serviceConfig = (Map<String, ?>) serviceConfigObj;

            ServiceConfigInterceptor serviceConfigInterceptor = new ServiceConfigInterceptor(/* retryEnabled= */ false);

            // 关闭重试
            serviceConfigInterceptor.handleUpdate(ManagedChannelServiceConfig
                    .fromServiceConfig(serviceConfig,
                            /* retryEnabled= */ false,
                            /* maxRetryAttemptsLimit= */ 4,
                            /* maxHedgedAttemptsLimit= */ 3,
                            /* loadBalancingConfig= */ null));

            MethodDescriptor.Builder<Void, Void> builder = TestMethodDescriptors.voidMethod().toBuilder();

            MethodDescriptor<Void, Void> method = builder.setFullMethodName("SimpleService1/Foo1").build();

            serviceConfigInterceptor.interceptCall(method, CallOptions.DEFAULT, channel);
            verify(channel).newCall(eq(method), callOptionsCap.capture());
            assertThat(callOptionsCap.getValue().getOption(RETRY_POLICY_KEY)).isNull();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    /**
     * 节流配置
     *
     * @throws Exception
     */
    @Test
    public void getThrottle() throws Exception {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(RetryPolicyTest.class.getResourceAsStream(
                    "/io/grpc/internal/test_retry_service_config.json"), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            Object serviceConfigObj = JsonParser.parse(sb.toString());
            assertTrue(serviceConfigObj instanceof Map);

            @SuppressWarnings("unchecked")
            Map<String, ?> serviceConfig = (Map<String, ?>) serviceConfigObj;
            Throttle throttle = ServiceConfigUtil.getThrottlePolicy(serviceConfig);

            assertEquals(new Throttle(10f, 0.1f), throttle);
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }
}
