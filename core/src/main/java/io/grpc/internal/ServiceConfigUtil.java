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
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.VerifyException;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.RetriableStream.Throttle;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;

/**
 * Helper utility to work with service configs.
 *
 * <p>This class contains helper methods to parse service config JSON values into Java types.
 */
public final class ServiceConfigUtil {

  private ServiceConfigUtil() {}

  /**
   * 从服务配置中获取健康检查配置
   * Fetches the health-checked service config from service config. {@code null} if can't find one.
   */
  @Nullable
  public static Map<String, ?> getHealthCheckedService(@Nullable  Map<String, ?> serviceConfig) {
    if (serviceConfig == null) {
      return null;
    }

    /* schema as follows
    {
      "healthCheckConfig": {
        // Service name to use in the health-checking request.
        "serviceName": string
      }
    }
    */
    return JsonUtil.getObject(serviceConfig, "healthCheckConfig");
  }

  /**
   * Fetches the health-checked service name from health-checked service config. {@code null} if
   * can't find one.
   * 从健康检查配置中获取服务名称，如果没有则返回 null
   */
  @Nullable
  public static String getHealthCheckedServiceName(@Nullable Map<String, ?> healthCheckedServiceConfig) {
    if (healthCheckedServiceConfig == null) {
      return null;
    }

    return JsonUtil.getString(healthCheckedServiceConfig, "serviceName");
  }

  /**
   * 解析节流策略
   *
   * @param serviceConfig
   * @return
   */
  @Nullable
  static Throttle getThrottlePolicy(@Nullable Map<String, ?> serviceConfig) {
    // 如果配置不存在，则返回 null
    if (serviceConfig == null) {
      return null;
    }

    /* schema as follows
    {
      "retryThrottling": {
        // The number of tokens starts at maxTokens. The token_count will always be
        // between 0 and maxTokens.
        //
        // This field is required and must be greater than zero.
        "maxTokens": number,

        // The amount of tokens to add on each successful RPC. Typically this will
        // be some number between 0 and 1, e.g., 0.1.
        //
        // This field is required and must be greater than zero. Up to 3 decimal
        // places are supported.
        "tokenRatio": number
      }
    }
    */

    // 从 Map 中获取节流策略
    Map<String, ?> throttling = JsonUtil.getObject(serviceConfig, "retryThrottling");
    if (throttling == null) {
      return null;
    }

    // TODO(dapengzhang0): check if this is null.
    // 获取 maxTokens 和 tokenRatio，构建节流配置对象
    float maxTokens = JsonUtil.getNumber(throttling, "maxTokens").floatValue();
    float tokenRatio = JsonUtil.getNumber(throttling, "tokenRatio").floatValue();
    checkState(maxTokens > 0f, "maxToken should be greater than zero");
    checkState(tokenRatio > 0f, "tokenRatio should be greater than zero");
    return new Throttle(maxTokens, tokenRatio);
  }

  @Nullable
  static Integer getMaxAttemptsFromRetryPolicy(Map<String, ?> retryPolicy) {
    return JsonUtil.getNumberAsInteger(retryPolicy, "maxAttempts");
  }

  @Nullable
  static Long getInitialBackoffNanosFromRetryPolicy(Map<String, ?> retryPolicy) {
    return JsonUtil.getStringAsDuration(retryPolicy, "initialBackoff");
  }

  @Nullable
  static Long getMaxBackoffNanosFromRetryPolicy(Map<String, ?> retryPolicy) {
    return JsonUtil.getStringAsDuration(retryPolicy, "maxBackoff");
  }

  @Nullable
  static Double getBackoffMultiplierFromRetryPolicy(Map<String, ?> retryPolicy) {
    return JsonUtil.getNumber(retryPolicy, "backoffMultiplier");
  }

  private static Set<Status.Code> getListOfStatusCodesAsSet(Map<String, ?> obj, String key) {
    List<?> statuses = JsonUtil.getList(obj, key);
    if (statuses == null) {
      return null;
    }
    return getStatusCodesFromList(statuses);
  }

  private static Set<Status.Code> getStatusCodesFromList(List<?> statuses) {
    EnumSet<Status.Code> codes = EnumSet.noneOf(Status.Code.class);
    for (Object status : statuses) {
      Status.Code code;
      if (status instanceof Double) {
        Double statusD = (Double) status;
        int codeValue = statusD.intValue();
        verify((double) codeValue == statusD, "Status code %s is not integral", status);
        code = Status.fromCodeValue(codeValue).getCode();
        verify(code.value() == statusD.intValue(), "Status code %s is not valid", status);
      } else if (status instanceof String) {
        try {
          code = Status.Code.valueOf((String) status);
        } catch (IllegalArgumentException iae) {
          throw new VerifyException("Status code " + status + " is not valid", iae);
        }
      } else {
        throw new VerifyException("Can not convert status code " + status + " to Status.Code, because its type is " + status.getClass());
      }
      codes.add(code);
    }
    return Collections.unmodifiableSet(codes);
  }

  // 获取可以重试的状态码
  static Set<Status.Code> getRetryableStatusCodesFromRetryPolicy(Map<String, ?> retryPolicy) {
    String retryableStatusCodesKey = "retryableStatusCodes";
    Set<Status.Code> codes = getListOfStatusCodesAsSet(retryPolicy, retryableStatusCodesKey);
    verify(codes != null, "%s is required in retry policy", retryableStatusCodesKey);
    verify(!codes.isEmpty(), "%s must not be empty", retryableStatusCodesKey);
    verify(!codes.contains(Status.Code.OK), "%s must not contain OK", retryableStatusCodesKey);
    return codes;
  }

  @Nullable
  static Integer getMaxAttemptsFromHedgingPolicy(Map<String, ?> hedgingPolicy) {
    return JsonUtil.getNumberAsInteger(hedgingPolicy, "maxAttempts");
  }

  @Nullable
  static Long getHedgingDelayNanosFromHedgingPolicy(Map<String, ?> hedgingPolicy) {
    return JsonUtil.getStringAsDuration(hedgingPolicy, "hedgingDelay");
  }

  /**
   * 获取对冲状态码
   *
   * @param hedgingPolicy
   * @return
   */
  static Set<Status.Code> getNonFatalStatusCodesFromHedgingPolicy(Map<String, ?> hedgingPolicy) {
    String nonFatalStatusCodesKey = "nonFatalStatusCodes";
    Set<Status.Code> codes = getListOfStatusCodesAsSet(hedgingPolicy, nonFatalStatusCodesKey);
    if (codes == null) {
      return Collections.unmodifiableSet(EnumSet.noneOf(Status.Code.class));
    }
    verify(!codes.contains(Status.Code.OK), "%s must not contain OK", nonFatalStatusCodesKey);
    return codes;
  }

  @Nullable
  static String getServiceFromName(Map<String, ?> name) {
    return JsonUtil.getString(name, "service");
  }

  @Nullable
  static String getMethodFromName(Map<String, ?> name) {
    return JsonUtil.getString(name, "method");
  }

  /**
   * 获取方法的重试策略
   *
   * @param methodConfig
   * @return
   */
  @Nullable
  static Map<String, ?> getRetryPolicyFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getObject(methodConfig, "retryPolicy");
  }

  @Nullable
  static Map<String, ?> getHedgingPolicyFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getObject(methodConfig, "hedgingPolicy");
  }

  @Nullable
  static List<Map<String, ?>> getNameListFromMethodConfig(
      Map<String, ?> methodConfig) {
    return JsonUtil.getListOfObjects(methodConfig, "name");
  }

  /**
   * 返回方法的超时时间
   * Returns the number of nanoseconds of timeout for the given method config.
   *
   * @return duration nanoseconds, or {@code null} if it isn't present.
   */
  @Nullable
  static Long getTimeoutFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getStringAsDuration(methodConfig, "timeout");
  }

  @Nullable
  static Boolean getWaitForReadyFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getBoolean(methodConfig, "waitForReady");
  }

  @Nullable
  static Integer getMaxRequestMessageBytesFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getNumberAsInteger(methodConfig, "maxRequestMessageBytes");
  }

  @Nullable
  static Integer getMaxResponseMessageBytesFromMethodConfig(Map<String, ?> methodConfig) {
    return JsonUtil.getNumberAsInteger(methodConfig, "maxResponseMessageBytes");
  }

  /**
   * 获取所有的方法配置
   *
   * @param serviceConfig
   * @return
   */
  @Nullable
  static List<Map<String, ?>> getMethodConfigFromServiceConfig(Map<String, ?> serviceConfig) {
    return JsonUtil.getListOfObjects(serviceConfig, "methodConfig");
  }

  /**
   * Extracts load balancing configs from a service config.
   * 从服务配置中获取负载均衡配置
   */
  @VisibleForTesting
  public static List<Map<String, ?>> getLoadBalancingConfigsFromServiceConfig(Map<String, ?> serviceConfig) {
    /* schema as follows
    {
      "loadBalancingConfig": [
        {"xds" :
          {
            "childPolicy": [...],
            "fallbackPolicy": [...],
          }
        },
        {"round_robin": {}}
      ],
      "loadBalancingPolicy": "ROUND_ROBIN"  // The deprecated policy key
    }
    */
    List<Map<String, ?>> lbConfigs = new ArrayList<>();
    // 如果有相应的配置，则获取并添加
    String loadBalancingConfigKey = "loadBalancingConfig";
    if (serviceConfig.containsKey(loadBalancingConfigKey)) {
      lbConfigs.addAll(JsonUtil.getListOfObjects(serviceConfig, loadBalancingConfigKey));
    }
    if (lbConfigs.isEmpty()) {
      // No LoadBalancingConfig found.  Fall back to the deprecated LoadBalancingPolicy
      // 如果没有发现配置，则回退到使用 LoadBalancingPolicy
      String policy = JsonUtil.getString(serviceConfig, "loadBalancingPolicy");
      if (policy != null) {
        // Convert the policy to a config, so that the caller can handle them in the same way.
        // 将策略转换为配置(string -> map)，使用相同的处理逻辑
        policy = policy.toLowerCase(Locale.ROOT);
        Map<String, ?> fakeConfig = Collections.singletonMap(policy, Collections.emptyMap());
        lbConfigs.add(fakeConfig);
      }
    }
    return Collections.unmodifiableList(lbConfigs);
  }

    /**
     * Unwrap a LoadBalancingConfig JSON object into a {@link LbConfig}.  The input is a JSON object
     * (map) with exactly one entry, where the key is the policy name and the value is a config object
     * for that policy.
     * 将 LoadBalancingConfig JSON 对象转为 LbConfig
     */
    public static LbConfig unwrapLoadBalancingConfig(Map<String, ?> lbConfig) {
        if (lbConfig.size() != 1) {
            throw new RuntimeException(
                    "There are " + lbConfig.size() + " fields in a LoadBalancingConfig object. Exactly one"
                            + " is expected. Config=" + lbConfig);
        }
        String key = lbConfig.entrySet().iterator().next().getKey();
        return new LbConfig(key, JsonUtil.getObject(lbConfig, key));
    }

    /**
     * 将配置Map 转为 LbConfig 对象
     * <p>
     * Given a JSON list of LoadBalancingConfigs, and convert it into a list of LbConfig.
     */
    public static List<LbConfig> unwrapLoadBalancingConfigList(List<Map<String, ?>> list) {
        if (list == null) {
            return null;
        }
        ArrayList<LbConfig> result = new ArrayList<>();
        for (Map<String, ?> rawChildPolicy : list) {
            result.add(unwrapLoadBalancingConfig(rawChildPolicy));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Parses and selects a load balancing policy from a non-empty list of raw configs. If selection
     * is successful, the returned ConfigOrError object will include a {@link
     * ServiceConfigUtil.PolicySelection} as its config value.
     * 从配置中解析并选取一个负载均衡策略，如果继续成功，会返回一个包含 ServiceConfigUtil.PolicySelection 作为配置值
     * 的 ConfigOrError 对象
     */
    public static ConfigOrError selectLbPolicyFromList(List<LbConfig> lbConfigs, LoadBalancerRegistry lbRegistry) {
        List<String> policiesTried = new ArrayList<>();

        // 遍历配置，如果获取到就返回
        for (LbConfig lbConfig : lbConfigs) {
            String policy = lbConfig.getPolicyName();
            // 根据策略名称获取 Provider
            LoadBalancerProvider provider = lbRegistry.getProvider(policy);
            // 如果策略没有提供者，则加入尝试的列表
            if (provider == null) {
                policiesTried.add(policy);
            } else {
                // 如果有提供者，则根据 JSON 解析
                if (!policiesTried.isEmpty()) {
                    Logger.getLogger(ServiceConfigUtil.class.getName()).log(
                            Level.FINEST,
                            "{0} specified by Service Config are not available", policiesTried);
                }
                // 返回
                ConfigOrError parsedLbPolicyConfig = provider.parseLoadBalancingPolicyConfig(lbConfig.getRawConfigValue());
                if (parsedLbPolicyConfig.getError() != null) {
                    return parsedLbPolicyConfig;
                }
                return ConfigOrError.fromConfig(new PolicySelection(
                        provider, lbConfig.rawConfigValue, parsedLbPolicyConfig.getConfig()));
            }
        }
        return ConfigOrError.fromError(
                Status.UNKNOWN.withDescription(
                        "None of " + policiesTried + " specified by Service Config are available."));
    }

  /**
   * A LoadBalancingConfig that includes the policy name (the key) and its raw config value (parsed
   * JSON).
   */
  public static final class LbConfig {
    private final String policyName;
    private final Map<String, ?> rawConfigValue;

    public LbConfig(String policyName, Map<String, ?> rawConfigValue) {
      this.policyName = checkNotNull(policyName, "policyName");
      this.rawConfigValue = checkNotNull(rawConfigValue, "rawConfigValue");
    }

    public String getPolicyName() {
      return policyName;
    }

    public Map<String, ?> getRawConfigValue() {
      return rawConfigValue;
    }

    @Override
    public boolean equals(Object o) {
      if (o instanceof LbConfig) {
        LbConfig other = (LbConfig) o;
        return policyName.equals(other.policyName)
            && rawConfigValue.equals(other.rawConfigValue);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(policyName, rawConfigValue);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("policyName", policyName)
          .add("rawConfigValue", rawConfigValue)
          .toString();
    }
  }

  public static final class PolicySelection {
    final LoadBalancerProvider provider;
    @Deprecated
    @Nullable
    final Map<String, ?> rawConfig;
    @Nullable
    final Object config;

    /** Constructs a PolicySelection with selected LB provider, a copy of raw config and the deeply
     * parsed LB config. */
    public PolicySelection(
        LoadBalancerProvider provider,
        @Nullable Map<String, ?> rawConfig,
        @Nullable Object config) {
      this.provider = checkNotNull(provider, "provider");
      this.rawConfig = rawConfig;
      this.config = config;
    }

    public LoadBalancerProvider getProvider() {
      return provider;
    }

    @Nullable
    public Object getConfig() {
      return config;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      PolicySelection that = (PolicySelection) o;
      return Objects.equal(provider, that.provider)
          && Objects.equal(rawConfig, that.rawConfig)
          && Objects.equal(config, that.config);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(provider, rawConfig, config);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("provider", provider)
          .add("rawConfig", rawConfig)
          .add("config", config)
          .toString();
    }
  }
}
