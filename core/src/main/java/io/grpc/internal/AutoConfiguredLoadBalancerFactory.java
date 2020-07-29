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
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.LoadBalancer.ATTR_LOAD_BALANCING_CONFIG;

// TODO(creamsoup) fully deprecate LoadBalancer.ATTR_LOAD_BALANCING_CONFIG
@SuppressWarnings("deprecation")
public final class AutoConfiguredLoadBalancerFactory {

  private final LoadBalancerRegistry registry;
  private final String defaultPolicy;

  static final Logger logger = Logger.getLogger(AutoConfiguredLoadBalancerFactory.class.getName());

  public AutoConfiguredLoadBalancerFactory(String defaultPolicy) {
    this(LoadBalancerRegistry.getDefaultRegistry(), defaultPolicy);
  }

  @VisibleForTesting
  AutoConfiguredLoadBalancerFactory(LoadBalancerRegistry registry, String defaultPolicy) {
    this.registry = checkNotNull(registry, "registry");
    this.defaultPolicy = checkNotNull(defaultPolicy, "defaultPolicy");
  }

  /**
   * 构建负载均衡配置器
   *
   * @param helper
   * @return
   */
  public AutoConfiguredLoadBalancer newLoadBalancer(Helper helper) {
    return new AutoConfiguredLoadBalancer(helper);
  }

  private static final class NoopLoadBalancer extends LoadBalancer {

    @Override
    @Deprecated
    public void handleResolvedAddressGroups(List<EquivalentAddressGroup> s, Attributes a) {}

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {}

    @Override
    public void handleNameResolutionError(Status error) {}

    @Override
    public void shutdown() {}
  }

  /**
   * 自动配置负载均衡
   */
  @VisibleForTesting
  public final class AutoConfiguredLoadBalancer {
    private final Helper helper;
    private LoadBalancer delegate;
    private LoadBalancerProvider delegateProvider;

    AutoConfiguredLoadBalancer(Helper helper) {
      logger.warning("==> io.grpc.internal.AutoConfiguredLoadBalancerFactory.AutoConfiguredLoadBalancer.AutoConfiguredLoadBalancer");
      this.helper = helper;
      // 从注册器中获取默认的负载均衡策略提供器
      logger.info("从注册器中获取默认的负载均衡策略提供器");
      delegateProvider = registry.getProvider(defaultPolicy);
      if (delegateProvider == null) {
        throw new IllegalStateException("Could not find policy '" + defaultPolicy
                + "'. Make sure its implementation is either registered to LoadBalancerRegistry or"
                + " included in META-INF/services/io.grpc.LoadBalancerProvider from your jar files.");
      }
      // 创建新的
      logger.info("创建新的负载均衡器");
      delegate = delegateProvider.newLoadBalancer(helper);
    }

    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      tryHandleResolvedAddresses(resolvedAddresses);
    }

    /**
     * 更新负载均衡算法，处理未处理的请求
     * Returns non-OK status if resolvedAddresses is empty and delegate lb requires address ({@link
     * LoadBalancer#canHandleEmptyAddressListFromNameResolution()} returns {@code false}). {@code
     * AutoConfiguredLoadBalancer} doesn't expose {@code
     * canHandleEmptyAddressListFromNameResolution} because it depends on the delegated LB.
     * <p>
     * 如果 resolvedAddresses 是空的且代理的负载均衡调用 LoadBalancer#canHandleEmptyAddressListFromNameResolution
     * 返回 false，则返回不是 ok 的状态
     */
    Status tryHandleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
      Attributes attributes = resolvedAddresses.getAttributes();

      if (attributes.get(ATTR_LOAD_BALANCING_CONFIG) != null) {
        throw new IllegalArgumentException("Unexpected ATTR_LOAD_BALANCING_CONFIG from upstream: " + attributes.get(ATTR_LOAD_BALANCING_CONFIG));
      }

      // 负载均衡选择
      PolicySelection policySelection = (PolicySelection) resolvedAddresses.getLoadBalancingPolicyConfig();

      if (policySelection == null) {
        LoadBalancerProvider defaultProvider;
        try {
          // 更新负载均衡提供器
          defaultProvider = getProviderOrThrow(defaultPolicy, "using default policy");
        } catch (PolicyException e) {
          Status s = Status.INTERNAL.withDescription(e.getMessage());
          helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new FailingPicker(s));
          delegate.shutdown();
          delegateProvider = null;
          delegate = new NoopLoadBalancer();
          return Status.OK;
        }
        policySelection = new PolicySelection(defaultProvider,
                /* rawConfig= */ null,
                /* config= */ null);
      }

      // 如果负载均衡提供器为空，或者负载均衡算法发生变化
      if (delegateProvider == null
              || !policySelection.provider.getPolicyName().equals(delegateProvider.getPolicyName())) {
        // 更新负载均衡状态和选择器，处理待处理的流
        helper.updateBalancingState(ConnectivityState.CONNECTING, new EmptyPicker());
        // 关闭旧的负载均衡
        delegate.shutdown();
        delegateProvider = policySelection.provider;
        LoadBalancer old = delegate;
        // 更新负载均衡
        delegate = delegateProvider.newLoadBalancer(helper);

        helper.getChannelLogger().log(
                ChannelLogLevel.INFO, "Load balancer changed from {0} to {1}",
                old.getClass().getSimpleName(), delegate.getClass().getSimpleName());
      }
      Object lbConfig = policySelection.config;

      // 设置负载均衡算法参数
      if (lbConfig != null) {
        helper.getChannelLogger().log(ChannelLogLevel.DEBUG, "Load-balancing config: {0}", policySelection.config);
        attributes = attributes.toBuilder()
                               .set(ATTR_LOAD_BALANCING_CONFIG, policySelection.rawConfig)
                               .build();
      }

      // 负载均衡器
      LoadBalancer delegate = getDelegate();
      // 如果地址是空的，或者处理失败，则返回错误
      if (resolvedAddresses.getAddresses().isEmpty() &&
              !delegate.canHandleEmptyAddressListFromNameResolution()) {
        return Status.UNAVAILABLE.withDescription("NameResolver returned no usable address. addrs=" + servers + ", attrs=" + attributes);
      } else {
        // 返回处理成功
        delegate.handleResolvedAddresses(
                ResolvedAddresses.newBuilder()
                                 .setAddresses(resolvedAddresses.getAddresses())
                                 .setAttributes(attributes)
                                 .setLoadBalancingPolicyConfig(lbConfig)
                                 .build());
        return Status.OK;
      }
    }

    void handleNameResolutionError(Status error) {
      getDelegate().handleNameResolutionError(error);
    }

    @Deprecated
    void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
      getDelegate().handleSubchannelState(subchannel, stateInfo);
    }

    void requestConnection() {
      getDelegate().requestConnection();
    }

    void shutdown() {
      delegate.shutdown();
      delegate = null;
    }

    @VisibleForTesting
    public LoadBalancer getDelegate() {
      return delegate;
    }

    @VisibleForTesting
    void setDelegate(LoadBalancer lb) {
      delegate = lb;
    }

    @VisibleForTesting
    LoadBalancerProvider getDelegateProvider() {
      return delegateProvider;
    }
  }

  private LoadBalancerProvider getProviderOrThrow(String policy, String choiceReason)
      throws PolicyException {
    LoadBalancerProvider provider = registry.getProvider(policy);
    if (provider == null) {
      throw new PolicyException(
          "Trying to load '" + policy + "' because " + choiceReason + ", but it's unavailable");
    }
    return provider;
  }

  /**
   * Parses first available LoadBalancer policy from service config. Available LoadBalancer should
   * be registered to {@link LoadBalancerRegistry}. If the first available LoadBalancer policy is
   * invalid, it doesn't fall-back to next available policy, instead it returns error. This also
   * means, it ignores LoadBalancer policies after the first available one even if any of them are
   * invalid.
   *
   * 从服务配种中解析第一个可用的负载均衡策略，可用的负载均衡策略应当在 LoadBalancerRegistry 中注册，如果策略是无效的，
   * 它不会回退到下一个策略，而是返回错误，这意味着如果第一个策略无效，即使后面的配置有效也会被忽略
   *
   * <p>Order of policy preference:
   *
   * <ol>
   *    <li>Policy from "loadBalancingConfig" if present</li>
   *    <li>The policy from deprecated "loadBalancingPolicy" if present</li>
   * </ol>
   * </p>
   *
   * <p>Unlike a normal {@link LoadBalancer.Factory}, this accepts a full service config rather than
   * the LoadBalancingConfig.
   * 和 LoadBalancer.Factory 不同的是这个方法可以接受整个服务配置而不是 LoadBalancingConfig
   *
   * @return the parsed {@link PolicySelection}, or {@code null} if no selection could be made.
   */
  @Nullable
  ConfigOrError parseLoadBalancerPolicy(Map<String, ?> serviceConfig, ChannelLogger channelLogger) {
    try {
      List<LbConfig> loadBalancerConfigs = null;
      if (serviceConfig != null) {
        // 获取配置
        List<Map<String, ?>> rawLbConfigs = ServiceConfigUtil.getLoadBalancingConfigsFromServiceConfig(serviceConfig);
        // 将 Map 解析为 LbConfig 对象
        loadBalancerConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(rawLbConfigs);
      }
      // 如果有策略就返回
      if (loadBalancerConfigs != null && !loadBalancerConfigs.isEmpty()) {
        return ServiceConfigUtil.selectLbPolicyFromList(loadBalancerConfigs, registry);
      }
      return null;
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.UNKNOWN.withDescription("can't parse load balancer configuration").withCause(e));
    }
  }

  @VisibleForTesting
  static final class PolicyException extends Exception {
    private static final long serialVersionUID = 1L;

    private PolicyException(String msg) {
      super(msg);
    }
  }

  private static final class EmptyPicker extends SubchannelPicker {

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withNoResult();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(EmptyPicker.class).toString();
    }
  }

  private static final class FailingPicker extends SubchannelPicker {
    private final Status failure;

    FailingPicker(Status failure) {
      this.failure = failure;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withError(failure);
    }
  }
}
