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

package io.grpc;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A pluggable component that receives resolved addresses from {@link NameResolver} and provides the
 * channel a usable subchannel when asked.
 * 一个可插拔的组件，用于接收 NameResolver 解析的地址，并给 Channel 提供可用的 Subchannel
 *
 * <h3>Overview</h3>
 *
 * <p>A LoadBalancer typically implements three interfaces:
 * <ol>
 *   <li>{@link LoadBalancer} is the main interface.  All methods on it are invoked sequentially
 *       in the same <strong>synchronization context</strong> (see next section) as returned by
 *       {@link io.grpc.LoadBalancer.Helper#getSynchronizationContext}.  It receives the results
 *       from the {@link NameResolver}, updates of subchannels' connectivity states, and the
 *       channel's request for the LoadBalancer to shutdown.</li>
 *   <li>{@link SubchannelPicker SubchannelPicker} does the actual load-balancing work.  It selects
 *       a {@link Subchannel Subchannel} for each new RPC.</li>
 *   <li>{@link Factory Factory} creates a new {@link LoadBalancer} instance.
 * </ol>
 * LoadBalancer 通常实现三个接口：
 * - LoadBalancer 是主要接口，所有的方法都通过 io.grpc.LoadBalancer.Helper#getSynchronizationContext 返回
 * 的 synchronization context 顺序调用，接收 NameResolver 提供的结果，更新 Subchannel 的连接状态，处理 Channel
 * 要求关闭的请求
 * - 由 SubchannelPicker 真正实现负载均衡工作，为每个请求选择 Subchannel
 * - Factory 创建 LoadBalancer 实例
 *
 *
 * <p>{@link Helper Helper} is implemented by gRPC library and provided to {@link Factory
 * Factory}. It provides functionalities that a {@code LoadBalancer} implementation would typically
 * need.
 * Helper 由 gRPC 库实现，并提供 Factory，实现了 LoadBalancer 通常需要实现的功能
 *
 * <h3>The Synchronization Context</h3>
 *
 * <p>All methods on the {@link LoadBalancer} interface are called from a Synchronization Context,
 * meaning they are serialized, thus the balancer implementation doesn't need to worry about
 * synchronization among them.  {@link io.grpc.LoadBalancer.Helper#getSynchronizationContext}
 * allows implementations to schedule tasks to be run in the same Synchronization Context, with or
 * without a delay, thus those tasks don't need to worry about synchronizing with the balancer
 * methods.
 * LoadBalancer 中所有的方法都由 LoadBalancer 调用，所以实现类不需要关心同步问题，
 * io.grpc.LoadBalancer.Helper#getSynchronizationContext 允许实现类通过相同的 Synchronization Context
 * 执行任务，支持延时和非延时，所以这些方法不需要关心同步的问题
 *
 * <p>However, the actual running thread may be the network thread, thus the following rules must be
 * followed to prevent blocking or even dead-locking in a network:
 * 然而，真正执行的可能是访问网络的线程，所以下列规则必须遵守，防止线程因为网络阻塞或者死锁
 * <ol>
 *
 *   <li><strong>Never block in the Synchronization Context</strong>.  The callback methods must
 *   return quickly.  Examples or work that must be avoided: CPU-intensive calculation, waiting on
 *   synchronization primitives, blocking I/O, blocking RPCs, etc.</li>
 *
 *   <li><strong>Avoid calling into other components with lock held</strong>.  The Synchronization
 *   Context may be under a lock, e.g., the transport lock of OkHttp.  If your LoadBalancer holds a
 *   lock in a callback method (e.g., {@link #handleResolvedAddresses handleResolvedAddresses()})
 *   while calling into another method that also involves locks, be cautious of deadlock.  Generally
 *   you wouldn't need any locking in the LoadBalancer if you follow the canonical implementation
 *   pattern below.</li>
 *
 * </ol>
 * <p>
 * 不要阻塞 Synchronization Context，回调方法必须快速返回，必须避免以下操作：CPU 敏感的计算，等待同步原语，
 * 阻塞 IO，阻塞调用等
 * 避免调用其他锁持有的组件，Synchronization Context 可能被锁住，如 OkHttp 的传输锁，如果 LoadBalancer
 * 在回调方法中持有锁，当调用一个持有锁的方法时，可能会造成死锁，如果规范实现 LoadBalancer 通常不会造成阻塞
 *
 * <h3>The canonical implementation pattern</h3>
 * 规范的实现
 *
 * <p>A {@link LoadBalancer} keeps states like the latest addresses from NameResolver, the
 * Subchannel(s) and their latest connectivity states.  These states are mutated within the
 * Synchronization Context,
 * LoadBalancer 保持 NameResolver 传递的最后的状态信息，Subchannel 和最后的连接状态，这些状态在同步
 * 上下文中发生了变化
 *
 * <p>A typical {@link SubchannelPicker SubchannelPicker} holds a snapshot of these states.  It may
 * have its own states, e.g., a picker from a round-robin load-balancer may keep a pointer to the
 * next Subchannel, which are typically mutated by multiple threads.  The picker should only mutate
 * its own state, and should not mutate or re-acquire the states of the LoadBalancer.  This way the
 * picker only needs to synchronize its own states, which is typically trivial to implement.
 * SubchannelPicker 通常持有这些状态的快照信息，可能有自己的状态；如轮询的负载均衡保持有指向下一个 Subchannel
 * 的指针，通常在多个线程中会发生变化，picker 应当仅保留自己的状态，而不应该变化或者重新获取 LoadBalancer 的状态，
 * picker 仅需要同步自己的状态，这通常很容易实现
 *
 * <p>When the LoadBalancer states changes, e.g., Subchannels has become or stopped being READY, and
 * we want subsequent RPCs to use the latest list of READY Subchannels, LoadBalancer would create a
 * new picker, which holds a snapshot of the latest Subchannel list.  Refer to the javadoc of {@link
 * io.grpc.LoadBalancer.SubchannelStateListener#onSubchannelState onSubchannelState()} how to do
 * this properly.
 * 当 LoadBalancer 状态变化时，如 Subchannel 变成停止或 READY，我们希望后续的请求使用最新的 READY 状态的
 * Subchannel 列表，LoadBalancer 会创建一个新的 picker，持有最新的 Subchannel 列表的快照，可以参考
 * io.grpc.LoadBalancer.SubchannelStateListener#onSubchannelState 如何适当的实现
 *
 * <p>No synchronization should be necessary between LoadBalancer and its pickers if you follow
 * the pattern above.  It may be possible to implement in a different way, but that would usually
 * result in more complicated threading.
 * 如果遵循上面的规范，不需要在 LoadBalancer 和它的 picker 之间保存同步，可以通过其他的方式实现，但这通常会
 * 导致更复杂的线程实现
 *
 * @since 1.2.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
@NotThreadSafe
public abstract class LoadBalancer {
  /**
   * The load-balancing config converted from an JSON object injected by the GRPC library.
   * gRPC 库注入的 JSON 对象转换的负载均衡配置
   *
   * <p>{@link NameResolver}s should not produce this attribute.
   * NameResolver 不应该产生此属性
   *
   * <p>Deprecated: LB implementations should use parsed object from {@link
   * LoadBalancerProvider#parseLoadBalancingPolicyConfig(Map)} instead of raw config.
   * 应当使用  LoadBalancerProvider#parseLoadBalancingPolicyConfig(Map) 代替原始配置
   */
  @Deprecated
  @NameResolver.ResolutionResultAttr
  public static final Attributes.Key<Map<String, ?>> ATTR_LOAD_BALANCING_CONFIG =
          Attributes.Key.create("io.grpc.LoadBalancer.loadBalancingConfig");

  @Internal
  @NameResolver.ResolutionResultAttr
  public static final Attributes.Key<Map<String, ?>> ATTR_HEALTH_CHECKING_CONFIG =
      Attributes.Key.create("health-checking-config");

  /**
   * 递归计数
   */
  private int recursionCount;

  /**
   * Handles newly resolved server groups and metadata attributes from name resolution system.
   * {@code servers} contained in {@link EquivalentAddressGroup} should be considered equivalent
   * but may be flattened into a single list if needed.
   * 处理新解析的服务地址组和元数据属性
   * servers 中的 EquivalentAddressGroup 应当认为是等效的，如果需要可以平铺为一个列表
   *
   * <p>Implementations should not modify the given {@code servers}.
   * 实现不应该修改所给的 severs
   *
   * @param servers    the resolved server addresses, never empty.
   *                   解析的 server 地址，不应该为空
   * @param attributes extra information from naming system.
   *                   命名服务获取的额外信息
   * @since 1.2.0
   * @deprecated override {@link #handleResolvedAddresses(ResolvedAddresses) instead}
   * 使用 handleResolvedAddresses(ResolvedAddresses) 代替
   */
  @Deprecated
  public void handleResolvedAddressGroups(List<EquivalentAddressGroup> servers,
                                          @NameResolver.ResolutionResultAttr Attributes attributes) {
    // 如果递归计数等于 0，则更新地址
    if (recursionCount++ == 0) {
      handleResolvedAddresses(ResolvedAddresses.newBuilder()
                                               .setAddresses(servers)
                                               .setAttributes(attributes)
                                               .build());
    }
    recursionCount = 0;
  }

  /**
   * Handles newly resolved server groups and metadata attributes from name resolution system.
   * {@code servers} contained in {@link EquivalentAddressGroup} should be considered equivalent
   * but may be flattened into a single list if needed.
   * 处理服务名称解析获取的服务地址和数据，EquivalentAddressGroup 包含的 server 应该是等效的，但是如果需要
   * 也可以平铺为一个列表
   *
   * <p>Implementations should not modify the given {@code servers}.
   * 实现不应该修改给定的 server 地址
   *
   * @param resolvedAddresses the resolved server addresses, attributes, and config.
   *                          解析的 server 的地址、属性和配置
   * @since 1.21.0
   */
  @SuppressWarnings("deprecation")
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    // 如果递归计数等于 0，则更新地址
    if (recursionCount++ == 0) {
      handleResolvedAddressGroups(resolvedAddresses.getAddresses(), resolvedAddresses.getAttributes());
    }
    recursionCount = 0;
  }

  /**
   * Represents a combination of the resolved server address, associated attributes and a load
   * balancing policy config.  The config is from the {@link
   * LoadBalancerProvider#parseLoadBalancingPolicyConfig(Map)}.
   * <p>
   * 代表合并的解析的 server 地址，连带属性和负载均衡策略配置，配置来自于
   * LoadBalancerProvider#parseLoadBalancingPolicyConfig(Map)
   *
   * @since 1.21.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public static final class ResolvedAddresses {

    private final List<EquivalentAddressGroup> addresses;

    @NameResolver.ResolutionResultAttr
    private final Attributes attributes;

    @Nullable
    private final Object loadBalancingPolicyConfig;
    // Make sure to update toBuilder() below!

    private ResolvedAddresses(List<EquivalentAddressGroup> addresses,
                              @NameResolver.ResolutionResultAttr Attributes attributes,
                              Object loadBalancingPolicyConfig) {
      this.addresses = Collections.unmodifiableList(new ArrayList<>(checkNotNull(addresses, "addresses")));
      this.attributes = checkNotNull(attributes, "attributes");
      this.loadBalancingPolicyConfig = loadBalancingPolicyConfig;
    }

    /**
     * Factory for constructing a new Builder.
     * 构建一个新的 Builder
     *
     * @since 1.21.0
     */
    public static Builder newBuilder() {
      return new Builder();
    }

    /**
     * Converts this back to a builder.
     * 转为 builder
     *
     * @since 1.21.0
     */
    public Builder toBuilder() {
      return newBuilder().setAddresses(addresses)
                         .setAttributes(attributes)
                         .setLoadBalancingPolicyConfig(loadBalancingPolicyConfig);
    }

    /**
     * Gets the server addresses.
     * 获取 Server 地址
     *
     * @since 1.21.0
     */
    public List<EquivalentAddressGroup> getAddresses() {
      return addresses;
    }

    /**
     * Gets the attributes associated with these addresses.  If this was not previously set,
     * {@link Attributes#EMPTY} will be returned.
     * 获取地址关联的属性，如果之前没有设置，那么会返回 Attributes#EMPTY
     *
     * @since 1.21.0
     */
    @NameResolver.ResolutionResultAttr
    public Attributes getAttributes() {
      return attributes;
    }

    /**
     * Gets the domain specific load balancing policy.  This is the config produced by
     * {@link LoadBalancerProvider#parseLoadBalancingPolicyConfig(Map)}.
     * 返回负载均衡配置，这个配置从 LoadBalancerProvider#parseLoadBalancingPolicyConfig(Map) 获取
     *
     * @since 1.21.0
     */
    @Nullable
    public Object getLoadBalancingPolicyConfig() {
      return loadBalancingPolicyConfig;
    }

    /**
     * Builder for {@link ResolvedAddresses}.
     * ResolvedAddress 的构建器
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
    public static final class Builder {

      private List<EquivalentAddressGroup> addresses;

      @NameResolver.ResolutionResultAttr
      private Attributes attributes = Attributes.EMPTY;

      @Nullable
      private Object loadBalancingPolicyConfig;

      Builder() {
      }

      /**
       * Sets the addresses.  This field is required.
       * 设置地址
       *
       * @return this.
       */
      public Builder setAddresses(List<EquivalentAddressGroup> addresses) {
        this.addresses = addresses;
        return this;
      }

      /**
       * Sets the attributes.  This field is optional; if not called, {@link Attributes#EMPTY}
       * will be used.
       * 设置属性，该属性是可选的，如果没有调用则会使用 Attributes#EMPTY
       *
       * @return this.
       */
      public Builder setAttributes(@NameResolver.ResolutionResultAttr Attributes attributes) {
        this.attributes = attributes;
        return this;
      }

      /**
       * Sets the load balancing policy config. This field is optional.
       * 设置负载均衡配置
       *
       * @return this.
       */
      public Builder setLoadBalancingPolicyConfig(@Nullable Object loadBalancingPolicyConfig) {
        this.loadBalancingPolicyConfig = loadBalancingPolicyConfig;
        return this;
      }

      /**
       * Constructs the {@link ResolvedAddresses}.
       * 构造 ResolvedAddresss
       */
      public ResolvedAddresses build() {
        return new ResolvedAddresses(addresses, attributes, loadBalancingPolicyConfig);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
                        .add("addresses", addresses)
                        .add("attributes", attributes)
                        .add("loadBalancingPolicyConfig", loadBalancingPolicyConfig)
                        .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(addresses, attributes, loadBalancingPolicyConfig);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof ResolvedAddresses)) {
        return false;
      }
      ResolvedAddresses that = (ResolvedAddresses) obj;
      return Objects.equal(this.addresses, that.addresses)
              && Objects.equal(this.attributes, that.attributes)
              && Objects.equal(this.loadBalancingPolicyConfig, that.loadBalancingPolicyConfig);
    }
  }

  /**
   * Handles an error from the name resolution system.
   * 处理命名解析错误
   *
   * @param error a non-OK status
   * @since 1.2.0
   */
  public abstract void handleNameResolutionError(Status error);

  /**
   * Handles a state change on a Subchannel.
   * 处理 Subchannel 状态变化
   *
   * <p>The initial state of a Subchannel is IDLE. You won't get a notification for the initial IDLE
   * state.
   * Subchannel 初始的状态是 IDLE，不能从初始的 IDLE 状态获取信息
   *
   * <p>If the new state is not SHUTDOWN, this method should create a new picker and call {@link
   * Helper#updateBalancingState Helper.updateBalancingState()}.  Failing to do so may result in
   * unnecessary delays of RPCs. Please refer to {@link PickResult#withSubchannel
   * PickResult.withSubchannel()}'s javadoc for more information.
   * 如果新的状态是 SHUTDOWN，这个方法应该创建一个新的 picker，并调用 Helper.updateBalancingState() 方法，
   * 如果操作失败可能会造成请求延时，参考 PickResult.withSubchannel()
   *
   * <p>SHUTDOWN can only happen in two cases.  One is that LoadBalancer called {@link
   * Subchannel#shutdown} earlier, thus it should have already discarded this Subchannel.  The other
   * is that Channel is doing a {@link ManagedChannel#shutdownNow forced shutdown} or has already
   * terminated, thus there won't be further requests to LoadBalancer.  Therefore, the LoadBalancer
   * usually don't need to react to a SHUTDOWN state.
   * SHUTDOWN 只有两种状态下会发生，一种是 LoadBalancer 调用 Subchannel#shutdown，因此应当已经丢弃这个 Subchannel，
   * 另一种是 Channel 调用 ManagedChannel#shutdownNow 强制关闭或者已经停止，所以 LoadBalancer 不会有新的请求，
   * 除此之外，LoadBalancer 通常不需要响应 SHUTDOWN 状态
   *
   * @param subchannel the involved Subchannel
   *                   涉及的 Subchannel
   * @param stateInfo  the new state
   *                   新的状态
   * @since 1.2.0
   * @deprecated This method will be removed.  Stop overriding it.  Instead, pass {@link
   * SubchannelStateListener} to {@link Subchannel#start} to receive Subchannel state
   * updates
   * 这个方法将被移除，使用 通过向 Subchannel#start 传入 SubchannelStateListener 接收状态更新
   */
  @Deprecated
  public void handleSubchannelState(Subchannel subchannel,
                                    ConnectivityStateInfo stateInfo) {
    // Do nothing.  If the implementation doesn't implement this, it will get subchannel states from
    // the new API.  We don't throw because there may be forwarding LoadBalancers still plumb this.
  }

  /**
   * The channel asks the load-balancer to shutdown.  No more methods on this class will be called
   * after this method.  The implementation should shutdown all Subchannels and OOB channels, and do
   * any other cleanup as necessary.
   *
   * Channel 要求关闭 LoadBalancer，当调用这个方法后，不会再调用其他的方法，实现类应当关闭所有的 Subchannel 和 OOB Channel，
   * 并且做其他必要的清除操作
   *
   * @since 1.2.0
   */
  public abstract void shutdown();

  /**
   * Whether this LoadBalancer can handle empty address group list to be passed to {@link
   * #handleResolvedAddresses(ResolvedAddresses)}.  The default implementation returns
   * {@code false}, meaning that if the NameResolver returns an empty list, the Channel will turn
   * that into an error and call {@link #handleNameResolutionError}.  LoadBalancers that want to
   * accept empty lists should override this method and return {@code true}.
   * 这个 LoadBalancer 是否能够处理 handleResolvedAddresses 传递的空地址组，默认的实现返回 false，意味着
   * 如果这个 NameResolver 返回了空列表，Channel 将会进入处理错误兵器调用 handleNameResolutionError
   * 如果 LoadBalancers 想接受空列表可以重写这个方法，返回 true
   *
   * <p>This method should always return a constant value.  It's not specified when this will be
   * called.
   * 这个方法应当返回一个常量值，将在何时调用未指定
   */
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return false;
  }

  /**
   * The channel asks the LoadBalancer to establish connections now (if applicable) so that the
   * upcoming RPC may then just pick a ready connection without waiting for connections.  This
   * is triggered by {@link ManagedChannel#getState ManagedChannel.getState(true)}.
   * Channel 要求 LoadBalancer 立即建立连接，以便后续的请求只需要选择 ready 的连接而不需要等待，是由
   * ManagedChannel#getState(true) 触发的
   *
   * <p>If LoadBalancer doesn't override it, this is no-op.  If it infeasible to create connections
   * given the current state, e.g. no Subchannel has been created yet, LoadBalancer can ignore this
   * request.
   * 如果 LoadBalancer 没有覆盖这个方法，则不会操作，如果当前的状态不允许创建连接，如没有 Subchannel 创建，则
   * LoadBalancer 可以忽略请求
   *
   * @since 1.22.0
   */
  public void requestConnection() {
  }

  /**
   * The main balancing logic.  It <strong>must be thread-safe</strong>. Typically it should only
   * synchronize on its own state, and avoid synchronizing with the LoadBalancer's state.
   * 实现负载均衡的主要逻辑，必须是线程安全的；通常只需要同步自己的状态，避免同步 LoadBalancer 的状态
   *
   * @since 1.2.0
   */
  @ThreadSafe
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class SubchannelPicker {
    /**
     * 为新的请求做负载均衡，选择一个 subchannel
     * Make a balancing decision for a new RPC.
     *
     * @param args the pick arguments
     * @since 1.3.0
     */
    public abstract PickResult pickSubchannel(PickSubchannelArgs args);

    /**
     * Tries to establish connections now so that the upcoming RPC may then just pick a ready
     * connection without having to connect first.
     * <p>
     * 尝试立即建立连接，以便请求只需要选择一个已经ready的连接，而不需要先建立连接
     *
     * <p>No-op if unsupported.
     * 如果不支持则不会操作
     *
     * @since 1.11.0
     * @deprecated override {@link LoadBalancer#requestConnection} instead.
     * 使用 LoadBalancer#requestConnection 代替
     */
    @Deprecated
    public void requestConnection() {
    }
  }

  /**
   * Provides arguments for a {@link SubchannelPicker#pickSubchannel(
   *LoadBalancer.PickSubchannelArgs)}.
   * <p>
   * 为 SubchannelPicker#pickSubchannel 提供参数
   *
   * @since 1.2.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class PickSubchannelArgs {

    /**
     * Call options.
     * 调用的选项
     *
     * @since 1.2.0
     */
    public abstract CallOptions getCallOptions();

    /**
     * Headers of the call. {@link SubchannelPicker#pickSubchannel} may mutate it before before
     * returning.
     * 调用的 Header，SubchannelPicker#pickSubchannel 在返回之前可能会改变 header
     *
     * @since 1.2.0
     */
    public abstract Metadata getHeaders();

    /**
     * Call method.
     * 调用方法
     *
     * @since 1.2.0
     */
    public abstract MethodDescriptor<?, ?> getMethodDescriptor();
  }

  /**
   * A balancing decision made by {@link SubchannelPicker SubchannelPicker} for an RPC.
   * SubchannelPicker 的选择结果
   *
   * <p>The outcome of the decision will be one of the following:
   * <ul>
   *   <li>Proceed: if a Subchannel is provided via {@link #withSubchannel withSubchannel()}, and is
   *       in READY state when the RPC tries to start on it, the RPC will proceed on that
   *       Subchannel.</li>
   *   <li>Error: if an error is provided via {@link #withError withError()}, and the RPC is not
   *       wait-for-ready (i.e., {@link CallOptions#withWaitForReady} was not called), the RPC will
   *       fail immediately with the given error.</li>
   *   <li>Buffer: in all other cases, the RPC will be buffered in the Channel, until the next
   *       picker is provided via {@link Helper#updateBalancingState Helper.updateBalancingState()},
   *       when the RPC will go through the same picking process again.</li>
   * </ul>
   * 决定的结果将是以下之一：
   * Proceed: 如果 Subchannel 是通过 withSubchannel 提供，并且当请求开始时已经是 READY 状态，则请求会继续由这个 Subchannel 处理
   * Error: 如果是通过 withError 返回，并且不是 wait-for-ready，请求会根据所给的错误立即失败
   * Buffer: 其他的情况，请求会在 Channel 中缓冲，直到 Helper.updateBalancingState() 提供下一个 picker，会再次执行选择流程
   *
   * @since 1.2.0
   */
  @Immutable
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public static final class PickResult {

    private static final PickResult NO_RESULT = new PickResult(null, null, Status.OK, false);

    @Nullable
    private final Subchannel subchannel;

    @Nullable
    private final ClientStreamTracer.Factory streamTracerFactory;

    // An error to be propagated to the application if subchannel == null
    // Or OK if there is no error.
    // subchannel being null and error being OK means RPC needs to wait
    // 如果 subchannel 是 null 则会传播错误给应用；或者没有错误，状态是 OK
    private final Status status;

    // True if the result is created by withDrop()
    // 如果通过 withDrop 创建，则是 true
    private final boolean drop;

    private PickResult(@Nullable Subchannel subchannel,
                       @Nullable ClientStreamTracer.Factory streamTracerFactory,
                       Status status,
                       boolean drop) {

      this.subchannel = subchannel;
      this.streamTracerFactory = streamTracerFactory;
      this.status = checkNotNull(status, "status");
      this.drop = drop;
    }

    /**
     * A decision to proceed the RPC on a Subchannel.
     * 决定由哪个 Subchannel 继续处理请求
     *
     * <p>The Subchannel should either be an original Subchannel returned by {@link
     * Helper#createSubchannel Helper.createSubchannel()}, or a wrapper of it preferably based on
     * {@code ForwardingSubchannel}.  At the very least its {@link Subchannel#getInternalSubchannel
     * getInternalSubchannel()} must return the same object as the one returned by the original.
     * Otherwise the Channel cannot use it for the RPC.
     * <p>
     * Subchannel 应当是 Helper.createSubchannel 创建的原始的 Subchannel，或者基于 ForwardingSubchannel
     * 的封装后的，必须返回与原始对象返回的对象相同的对象，否则 Channel 不能使用这个 Subchannel
     * 用于请求
     *
     * <p>When the RPC tries to use the return Subchannel, which is briefly after this method
     * returns, the state of the Subchannel will decide where the RPC would go:
     * 当请求准备使用返回的 Subchannel，在此方法返回的短暂时间内，Subchannel 的状态决定请求如何进行：
     *
     * <ul>
     *   <li>READY: the RPC will proceed on this Subchannel.</li>
     *   <li>IDLE: the RPC will be buffered.  Subchannel will attempt to create connection.</li>
     *   <li>All other states: the RPC will be buffered.</li>
     * </ul>
     * READY: 请求继续由这个 Subchannel 处理
     * IDLE: 请求会被缓冲，Subchannel 会尝试建立连接
     * 其他状态: 请求会被缓冲
     *
     * <p><strong>All buffered RPCs will stay buffered</strong> until the next call of {@link
     * Helper#updateBalancingState Helper.updateBalancingState()}, which will trigger a new picking
     * process.
     * 所有缓冲的请求会等待直到 Helper.updateBalancingState() 调用，会触发一个新的选择流程
     *
     * <p>Note that Subchannel's state may change at the same time the picker is making the
     * decision, which means the decision may be made with (to-be) outdated information.  For
     * example, a picker may return a Subchannel known to be READY, but it has become IDLE when is
     * about to be used by the RPC, which makes the RPC to be buffered.  The LoadBalancer will soon
     * learn about the Subchannels' transition from READY to IDLE, create a new picker and allow the
     * RPC to use another READY transport if there is any.
     * 需要注意 Subchannel 的状态可能在 picker 选择时会发生改变，意味着可能会根据已经过时的信息做选择；
     * 如 picker 可能会返回一个已经是 READY 状态的 Subchannel，但是当使用时可能变成了 IDLE 状态，
     * 可能会导致请求被缓冲，LoadBalancer 后续会了解 READY 变成 IDLE 的过渡，创建一个新的 Picker 并且
     * 允许请求使用另一个存在的 READY 状态的 Transport
     *
     * <p>You will want to avoid running into a situation where there are READY Subchannels out
     * there but some RPCs are still buffered for longer than a brief time.
     * <ul>
     *   <li>This can happen if you return Subchannels with states other than READY and IDLE.  For
     *       example, suppose you round-robin on 2 Subchannels, in READY and CONNECTING states
     *       respectively.  If the picker ignores the state and pick them equally, 50% of RPCs will
     *       be stuck in buffered state until both Subchannels are READY.</li>
     *   <li>This can also happen if you don't create a new picker at key state changes of
     *       Subchannels.  Take the above round-robin example again.  Suppose you do pick only READY
     *       and IDLE Subchannels, and initially both Subchannels are READY.  Now one becomes IDLE,
     *       then CONNECTING and stays CONNECTING for a long time.  If you don't create a new picker
     *       in response to the CONNECTING state to exclude that Subchannel, 50% of RPCs will hit it
     *       and be buffered even though the other Subchannel is READY.</li>
     * </ul>
     * 可能想要避免 Subchannel 是 READY，但是仍然有可能导致部分请求短暂缓冲的情况：
     * 可能发生在返回的 Subchannel 不是 READY 和 IDLE 状态的情况下，如 round_robin 策略选择两个 Subchannel，
     * 分别是 READY 和 CONNECTING 状态，如果忽略状态并且平等的选择，50% 的请求会被卡在缓冲中直到所有的 Subchannel
     * 都是 READY
     * 也可能发生在 Subchannel 状态发生变化时没有创建新的 Picker；如 round_robin 策略，如果只选择 READY 和 IDLE
     * 状态的 Subchannel，初始状态都是 READY，现在其中一个变成了 IDLE，并且长时间保持在 CONNECTING 状态，如果不重新
     * 创建一个 picker 来排除 CONNECTING 状态的 Subchannel，50% 的请求会被缓冲直到其他的 Subchannel 是 READY
     *
     * <p>In order to prevent unnecessary delay of RPCs, the rules of thumb are:
     * <ol>
     *   <li>The picker should only pick Subchannels that are known as READY or IDLE.  Whether to
     *       pick IDLE Subchannels depends on whether you want Subchannels to connect on-demand or
     *       actively:
     *       <ul>
     *         <li>If you want connect-on-demand, include IDLE Subchannels in your pick results,
     *             because when an RPC tries to use an IDLE Subchannel, the Subchannel will try to
     *             connect.</li>
     *         <li>If you want Subchannels to be always connected even when there is no RPC, you
     *             would call {@link Subchannel#requestConnection Subchannel.requestConnection()}
     *             whenever the Subchannel has transitioned to IDLE, then you don't need to include
     *             IDLE Subchannels in your pick results.</li>
     *       </ul></li>
     *   <li>Always create a new picker and call {@link Helper#updateBalancingState
     *       Helper.updateBalancingState()} whenever {@link #handleSubchannelState
     *       handleSubchannelState()} is called, unless the new state is SHUTDOWN. See
     *       {@code handleSubchannelState}'s javadoc for more details.</li>
     * </ol>
     * 为了避免不必要的请求延迟，经验法则是：
     * - picker 仅应当选择已知是 READY 或 IDLE 状态的 Subchannel，是否选择 IDLE 状态的 Subchannel 取决于是否
     *   连接或者主动连接：
     *    - 如果想要按需连接，则 Subchannel 包含 IDLE 状态，因为当请求尝试使用 IDLE 状态的 Subchannel，会尝试连接
     *    - 如果想要一直保持连接，即使没有请求，应当调用 Subchannel#requestConnection 方法，无论 Subchannel 何时
     *    过渡为 IDLE 状态，则返回的结果不包含 IDEL 状态的 Subchannel 即可
     *
     * @param subchannel          the picked Subchannel.  It must have been {@link Subchannel#start started}
     *                            被选择的 Subchannel，必须已经是启动的
     * @param streamTracerFactory if not null, will be used to trace the activities of the stream
     *                            created as a result of this pick. Note it's possible that no
     *                            stream is created at all in some cases.
     *                            如果不是 null，作为选择的结果创建，会被用于追踪流的连接情况，需要注意有些情况下可能
     *                            根本没有创建流
     * @since 1.3.0
     */
    public static PickResult withSubchannel(Subchannel subchannel,
                                            @Nullable ClientStreamTracer.Factory streamTracerFactory) {
      return new PickResult(checkNotNull(subchannel, "subchannel"),
              streamTracerFactory,
              Status.OK,
              false);
    }

    /**
     * Equivalent to {@code withSubchannel(subchannel, null)}.
     * 等同于 withSubchannel(subchannel, null)
     *
     * @since 1.2.0
     */
    public static PickResult withSubchannel(Subchannel subchannel) {
      return withSubchannel(subchannel, null);
    }

    /**
     * A decision to report a connectivity error to the RPC.  If the RPC is {@link
     * CallOptions#withWaitForReady wait-for-ready}, it will stay buffered.  Otherwise, it will fail
     * with the given error.
     * 将连接的错误描述返回给请求，如果请求是 wait-for-ready 的，则继续缓冲，否则会根据给定的错误失败
     *
     * @param error the error status.  Must not be OK.
     *              错误状态，不能是 OK
     * @since 1.2.0
     */
    public static PickResult withError(Status error) {
      Preconditions.checkArgument(!error.isOk(), "error status shouldn't be OK");
      return new PickResult(null, null, error, false);
    }

    /**
     * A decision to fail an RPC immediately.  This is a final decision and will ignore retry
     * policy.
     * 立即失败请求，是最终的决定，并且会忽视重试策略
     *
     * @param status the status with which the RPC will fail.  Must not be OK.
     *               失败的状态，必须不能是 OK
     * @since 1.8.0
     */
    public static PickResult withDrop(Status status) {
      Preconditions.checkArgument(!status.isOk(), "drop status shouldn't be OK");
      return new PickResult(null, null, status, true);
    }

    /**
     * No decision could be made.  The RPC will stay buffered.
     * 没有可用的结果，请求继续缓冲
     *
     * @since 1.2.0
     */
    public static PickResult withNoResult() {
      return NO_RESULT;
    }

    /**
     * The Subchannel if this result was created by {@link #withSubchannel withSubchannel()}, or
     * null otherwise.
     * 如果结果是通过 withSubchannel 创建则返回 Subchannel，否则返回 null
     *
     * @since 1.2.0
     */
    @Nullable
    public Subchannel getSubchannel() {
      return subchannel;
    }

    /**
     * The stream tracer factory this result was created with.
     * 创建此结果的流跟踪器
     *
     * @since 1.3.0
     */
    @Nullable
    public ClientStreamTracer.Factory getStreamTracerFactory() {
      return streamTracerFactory;
    }

    /**
     * The status associated with this result.  Non-{@code OK} if created with {@link #withError
     * withError}, or {@code OK} otherwise.
     * 结果关联的状态，如果是通过  withError 创建的，则是非 OK 的，否则就是 OK 的
     *
     * @since 1.2.0
     */
    public Status getStatus() {
      return status;
    }

    /**
     * Returns {@code true} if this result was created by {@link #withDrop withDrop()}.
     * 如果是通过 withDrop 创建的，则返回true
     *
     * @since 1.8.0
     */
    public boolean isDrop() {
      return drop;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
                        .add("subchannel", subchannel)
                        .add("streamTracerFactory", streamTracerFactory)
                        .add("status", status)
                        .add("drop", drop)
                        .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(subchannel, status, streamTracerFactory, drop);
    }

    /**
     * Returns true if the {@link Subchannel}, {@link Status}, and
     * {@link ClientStreamTracer.Factory} all match.
     */
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof PickResult)) {
        return false;
      }
      PickResult that = (PickResult) other;
      return Objects.equal(subchannel, that.subchannel) && Objects.equal(status, that.status)
              && Objects.equal(streamTracerFactory, that.streamTracerFactory)
              && drop == that.drop;
    }
  }

  /**
   * Arguments for creating a {@link Subchannel}.
   * 创建 Subchannel 的参数
   *
   * @since 1.22.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public static final class CreateSubchannelArgs {

    private final List<EquivalentAddressGroup> addrs;
    private final Attributes attrs;
    private final Object[][] customOptions;

    private CreateSubchannelArgs(List<EquivalentAddressGroup> addrs,
                                 Attributes attrs,
                                 Object[][] customOptions) {
      this.addrs = checkNotNull(addrs, "addresses are not set");
      this.attrs = checkNotNull(attrs, "attrs");
      this.customOptions = checkNotNull(customOptions, "customOptions");
    }

    /**
     * Returns the addresses, which is an unmodifiable list.
     * 返回不可变的地址集合
     */
    public List<EquivalentAddressGroup> getAddresses() {
      return addrs;
    }

    /**
     * Returns the attributes.
     */
    public Attributes getAttributes() {
      return attrs;
    }

    /**
     * Get the value for a custom option or its inherent default.
     * 获取自定义的 key 对应的值，如果没有则使用默认的
     *
     * @param key Key identifying option
     */
    @SuppressWarnings("unchecked")
    public <T> T getOption(Key<T> key) {
      Preconditions.checkNotNull(key, "key");
      for (int i = 0; i < customOptions.length; i++) {
        if (key.equals(customOptions[i][0])) {
          return (T) customOptions[i][1];
        }
      }
      return key.defaultValue;
    }

    /**
     * Returns a builder with the same initial values as this object.
     * 将对象转为 Builder
     */
    public Builder toBuilder() {
      return newBuilder().setAddresses(addrs)
                         .setAttributes(attrs)
                         .copyCustomOptions(customOptions);
    }

    /**
     * Creates a new builder.
     * 创建 Builder
     */
    public static Builder newBuilder() {
      return new Builder();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
                        .add("addrs", addrs)
                        .add("attrs", attrs)
                        .add("customOptions", Arrays.deepToString(customOptions))
                        .toString();
    }

    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
    public static final class Builder {

      private List<EquivalentAddressGroup> addrs;
      private Attributes attrs = Attributes.EMPTY;
      private Object[][] customOptions = new Object[0][2];

      Builder() {
      }

      private <T> Builder copyCustomOptions(Object[][] options) {
        customOptions = new Object[options.length][2];
        System.arraycopy(options, 0, customOptions, 0, options.length);
        return this;
      }

      /**
       * Add a custom option. Any existing value for the key is overwritten.
       * 添加自定义属性，如果 key 已经存在，则覆盖
       *
       * <p>This is an <strong>optional</strong> property.
       *
       * @param key   the option key
       * @param value the option value
       */
      public <T> Builder addOption(Key<T> key, T value) {
        Preconditions.checkNotNull(key, "key");
        Preconditions.checkNotNull(value, "value");

        int existingIdx = -1;
        for (int i = 0; i < customOptions.length; i++) {
          if (key.equals(customOptions[i][0])) {
            existingIdx = i;
            break;
          }
        }

        if (existingIdx == -1) {
          Object[][] newCustomOptions = new Object[customOptions.length + 1][2];
          System.arraycopy(customOptions, 0, newCustomOptions, 0, customOptions.length);
          customOptions = newCustomOptions;
          existingIdx = customOptions.length - 1;
        }
        customOptions[existingIdx] = new Object[]{key, value};
        return this;
      }

      /**
       * The addresses to connect to.  All addresses are considered equivalent and will be tried
       * in the order they are provided.
       */
      public Builder setAddresses(EquivalentAddressGroup addrs) {
        this.addrs = Collections.singletonList(addrs);
        return this;
      }

      /**
       * The addresses to connect to.  All addresses are considered equivalent and will
       * be tried in the order they are provided.
       *
       * <p>This is a <strong>required</strong> property.
       *
       * @throws IllegalArgumentException if {@code addrs} is empty
       */
      public Builder setAddresses(List<EquivalentAddressGroup> addrs) {
        checkArgument(!addrs.isEmpty(), "addrs is empty");
        this.addrs = Collections.unmodifiableList(new ArrayList<>(addrs));
        return this;
      }

      /**
       * Attributes provided here will be included in {@link Subchannel#getAttributes}.
       *
       * <p>This is an <strong>optional</strong> property.  Default is empty if not set.
       */
      public Builder setAttributes(Attributes attrs) {
        this.attrs = checkNotNull(attrs, "attrs");
        return this;
      }

      /**
       * Creates a new args object.
       */
      public CreateSubchannelArgs build() {
        return new CreateSubchannelArgs(addrs, attrs, customOptions);
      }
    }

    /**
     * Key for a key-value pair. Uses reference equality.
     * 键值对的 key
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
    public static final class Key<T> {

      private final String debugString;
      private final T defaultValue;

      private Key(String debugString, T defaultValue) {
        this.debugString = debugString;
        this.defaultValue = defaultValue;
      }

      /**
       * Factory method for creating instances of {@link Key}. The default value of the key is
       * {@code null}.
       * 创建 Key 实例的工厂方法，value 的默认值是 null
       *
       * @param debugString a debug string that describes this key.
       *                    描述 key 的字符串
       * @param <T>         Key type key 的类型
       * @return Key object key 对象
       */
      public static <T> Key<T> create(String debugString) {
        Preconditions.checkNotNull(debugString, "debugString");
        return new Key<>(debugString, /*defaultValue=*/ null);
      }

      /**
       * Factory method for creating instances of {@link Key}.
       * 创建 key 实例的默认方法
       *
       * @param debugString  a debug string that describes this key.
       *                     描述 key 的字符串
       * @param defaultValue default value to return when value for key not set
       *                     当没有设置值时返回的默认值
       * @param <T>          Key type key 类型
       * @return Key object key 对象
       */
      public static <T> Key<T> createWithDefault(String debugString, T defaultValue) {
        Preconditions.checkNotNull(debugString, "debugString");
        return new Key<>(debugString, defaultValue);
      }

      /**
       * Returns the user supplied default value for this key.
       */
      public T getDefault() {
        return defaultValue;
      }

      @Override
      public String toString() {
        return debugString;
      }
    }
  }

  /**
   * Provides essentials for LoadBalancer implementations.
   * 提供 LoadBalancer 实现的基本要素
   *
   * @since 1.2.0
   */
  @ThreadSafe
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class Helper {
    /**
     * Equivalent to {@link #createSubchannel(List, Attributes)} with the given single {@code
     * EquivalentAddressGroup}.
     * 等同于 createSubchannel(List, Attributes)，使用 EquivalentAddressGroup 构造
     *
     * @since 1.2.0
     * @deprecated Use {@link #createSubchannel(io.grpc.LoadBalancer.CreateSubchannelArgs)}
     * instead. Note the new API must be called from {@link #getSynchronizationContext
     * the Synchronization Context}.
     *  使用 createSubchannel(CreateSubchannelArgs) 代替，注意新的 API 必须通过 Synchronization Context 调用
     */
    @Deprecated
    public final Subchannel createSubchannel(EquivalentAddressGroup addrs, Attributes attrs) {
      checkNotNull(addrs, "addrs");
      return createSubchannel(Collections.singletonList(addrs), attrs);
    }

    /**
     * Creates a Subchannel, which is a logical connection to the given group of addresses which are
     * considered equivalent.  The {@code attrs} are custom attributes associated with this
     * Subchannel, and can be accessed later through {@link Subchannel#getAttributes
     * Subchannel.getAttributes()}.
     * 创建 Subchannel，等同于给定的地址的集合的逻辑连接，attrs 是与 Subchannel 关联的自定义的属性，
     * 可以在 Subchannel#getAttributes() 方法后调用
     *
     * <p>It is recommended you call this method from the Synchronization Context, otherwise your
     * logic around the creation may race with {@link #handleSubchannelState}.  See
     * <a href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for more discussions.
     * 推荐使用 Synchronization Context 调用这个方法，除非创建逻辑可能和 handleSubchannelState 竞争
     *
     * <p>The LoadBalancer is responsible for closing unused Subchannels, and closing all
     * Subchannels within {@link #shutdown}.
     * LoadBalancer 负责关闭未使用的 Subchannel，当 shutdown 时关闭全部
     *
     * @throws IllegalArgumentException if {@code addrs} is empty
     * @since 1.14.0
     * @deprecated Use {@link #createSubchannel(io.grpc.LoadBalancer.CreateSubchannelArgs)}
     * instead. Note the new API must be called from {@link #getSynchronizationContext
     * the Synchronization Context}.
     * 使用 createSubchannel(CreateSubchannelArgs) 代替，注意新的 API 必须通过 Synchronization Context
     * 调用
     */
    @Deprecated
    public Subchannel createSubchannel(List<EquivalentAddressGroup> addrs, Attributes attrs) {
      throw new UnsupportedOperationException();
    }

    /**
     * Creates a Subchannel, which is a logical connection to the given group of addresses which are
     * considered equivalent.  The {@code attrs} are custom attributes associated with this
     * Subchannel, and can be accessed later through {@link Subchannel#getAttributes
     * Subchannel.getAttributes()}.
     * <p>
     * 创建一个 Subchannel，等同于给定的地址的集合的逻辑连接，attrs 是与 Subchannel 关联的自定义的属性，
     * 可以在 Subchannel#getAttributes() 方法后调用
     *
     * <p>The LoadBalancer is responsible for closing unused Subchannels, and closing all
     * Subchannels within {@link #shutdown}.
     * LoadBalancer 负责关闭未使用的 Subchannel，当 shutdown 时关闭全部
     *
     * <p>It must be called from {@link #getSynchronizationContext the Synchronization Context}
     * 必须通过 Synchronization Context 调用
     *
     * @since 1.22.0
     */
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      throw new UnsupportedOperationException();
    }

    /**
     * Equivalent to {@link #updateSubchannelAddresses(io.grpc.LoadBalancer.Subchannel, List)} with
     * the given single {@code EquivalentAddressGroup}.
     * 等同于使用给定的 EquivalentAddressGroup 调用 updateSubchannelAddresses(Subchannel, List)
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     * 应当通过 Synchronization Context 调用
     *
     * @since 1.4.0
     * @deprecated use {@link Subchannel#updateAddresses} instead
     * 使用 Subchannel#updateAddress 代替
     */
    @Deprecated
    public final void updateSubchannelAddresses(Subchannel subchannel,
                                                EquivalentAddressGroup addrs) {
      checkNotNull(addrs, "addrs");
      updateSubchannelAddresses(subchannel, Collections.singletonList(addrs));
    }

    /**
     * Replaces the existing addresses used with {@code subchannel}. This method is superior to
     * {@link #createSubchannel} when the new and old addresses overlap, since the subchannel can
     * continue using an existing connection.
     * 代替给定的 Subchannel 中的地址,当替换旧的地址时这个方法优于 createSubchannel，因为 Subchannel 依然
     * 可以使用已经存在的连接
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     * 应当通过 Synchronization Context 调用
     *
     * @throws IllegalArgumentException if {@code subchannel} was not returned from {@link
     *                                  #createSubchannel} or {@code addrs} is empty
     *                                  如果 Subchannel 不是通过 createSubchannel 调用或者 addrs 是空的
     * @since 1.14.0
     * @deprecated use {@link Subchannel#updateAddresses} instead
     * 使用 Subchannel#updateAddresses 代替
     */
    @Deprecated
    public void updateSubchannelAddresses(Subchannel subchannel,
                                          List<EquivalentAddressGroup> addrs) {
      throw new UnsupportedOperationException();
    }

    /**
     * Out-of-band channel for LoadBalancer’s own RPC needs, e.g., talking to an external
     * load-balancer service.
     * 满足 LoadBalancer 自身请求的其他 Channel，如与外部的负载均衡器通信
     *
     * <p>The LoadBalancer is responsible for closing unused OOB channels, and closing all OOB
     * channels within {@link #shutdown}.
     * LoadBalancer 负责关闭未使用的 OOB Channel，当 shutdown 时关闭所有的 OOB Channel
     *
     * @since 1.4.0
     */
    // TODO(ejona): Allow passing a List<EAG> here and to updateOobChannelAddresses, but want to
    // wait until https://github.com/grpc/grpc-java/issues/4469 is done.
    // https://github.com/grpc/grpc-java/issues/4618
    public abstract ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority);

    /**
     * Updates the addresses used for connections in the {@code Channel} that was created by {@link
     * #createOobChannel(EquivalentAddressGroup, String)}. This is superior to {@link
     * #createOobChannel(EquivalentAddressGroup, String)} when the old and new addresses overlap,
     * since the channel can continue using an existing connection.
     * 更新 Channel 中使用的 createOobChannel 创建的连接的地址，当替代原有地址时要优于 createOobChannel，
     * 因为新旧地址相同时， Channel 依然可以使用存在的连接
     *
     * @throws IllegalArgumentException if {@code channel} was not returned from {@link
     *                                  #createOobChannel}
     *                                  当不是 createOobChannel 创建的 Channel 时抛出异常
     * @since 1.4.0
     */
    public void updateOobChannelAddresses(ManagedChannel channel, EquivalentAddressGroup eag) {
      throw new UnsupportedOperationException();
    }

    /**
     * Creates an out-of-band channel for LoadBalancer's own RPC needs, e.g., talking to an external
     * load-balancer service, that is specified by a target string.  See the documentation on
     * {@link ManagedChannelBuilder#forTarget} for the format of a target string.
     * 创建用于 LoadBalancer 自己的请求的额外 Channel，如与外部负载均衡器通信，通过特定的地址指定
     *
     * <p>The target string will be resolved by a {@link NameResolver} created according to the
     * target string.
     * 目标地址可以通过 NameResolver 解析
     *
     * <p>The LoadBalancer is responsible for closing unused OOB channels, and closing all OOB
     * channels within {@link #shutdown}.
     * LoadBalancer 负责关闭没有使用的 OOB Channel，当 shutdown 时关闭所有的 Channel
     *
     * @since 1.20.0
     */
    public ManagedChannel createResolvingOobChannel(String target) {
      return createResolvingOobChannelBuilder(target).build();
    }

    /**
     * Creates an out-of-band channel builder for LoadBalancer's own RPC needs, e.g., talking to an
     * external load-balancer service, that is specified by a target string.  See the documentation
     * on {@link ManagedChannelBuilder#forTarget} for the format of a target string.
     *
     * 创建用于 LoadBalancer 自己的请求的额外 Channel 的 builder，如用于与外部负载均衡器通信，通过特定的字符串指定，
     *
     * <p>The target string will be resolved by a {@link NameResolver} created according to the
     * target string.
     * 目标地址通过 NameResolver 解析
     *
     * <p>The LoadBalancer is responsible for closing unused OOB channels, and closing all OOB
     * channels within {@link #shutdown}.
     *  LoadBalancer 负责关闭没有使用的 OOB Channel，当 shutdown 时关闭所有的 Channel
     *
     * @since 1.31.0
     */
    public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(String target) {
      throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Set a new state with a new picker to the channel.
     * 使用新的 picker 为 channel 设置新的状态
     *
     * <p>When a new picker is provided via {@code updateBalancingState()}, the channel will apply
     * the picker on all buffered RPCs, by calling {@link SubchannelPicker#pickSubchannel(
     *LoadBalancer.PickSubchannelArgs)}.
     *
     * 如果提供了新的 picker 或调用 updateBalancingState 方法，channel 会通过调用 SubchannelPicker#pickSubchannel
     * 将 picker 用于所有缓冲的 buffer
     *
     * <p>The channel will hold the picker and use it for all RPCs, until {@code
     * updateBalancingState()} is called again and a new picker replaces the old one.  If {@code
     * updateBalancingState()} has never been called, the channel will buffer all RPCs until a
     * picker is provided.
     * channel 会持有 picker 并用于所有的 RPC，直到再次调用了 updateBalancingState 提供了新的 picker，
     * 如果没有调用 updateBalancingState，channel 会缓冲所有的 RPC 直到提供了 picker
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * <p>The passed state will be the channel's new state. The SHUTDOWN state should not be passed
     * and its behavior is undefined.
     * 传入的状态应当是 Channel 的新状态
     *
     * @since 1.6.0
     */
    public abstract void updateBalancingState(@Nonnull ConnectivityState newState,
                                              @Nonnull SubchannelPicker newPicker);

    /**
     * Call {@link NameResolver#refresh} on the channel's resolver.
     * 通过 Channel 的解析器调用 NameResolver#refresh 更新服务
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * @since 1.18.0
     */
    public void refreshNameResolution() {
      throw new UnsupportedOperationException();
    }

    /**
     * Schedule a task to be run in the Synchronization Context, which serializes the task with the
     * callback methods on the {@link LoadBalancer} interface.
     * 调度一个 Synchronization Context 调度的任务，在 LoadBalancer 使用回调方法序列化任务
     *
     * @since 1.2.0
     * @deprecated use/implement {@code getSynchronizationContext()} instead
     */
    @Deprecated
    public void runSerialized(Runnable task) {
      getSynchronizationContext().execute(task);
    }

    /**
     * Returns a {@link SynchronizationContext} that runs tasks in the same Synchronization Context
     * as that the callback methods on the {@link LoadBalancer} interface are run in.
     * 返回 SynchronizationContext 用于在同步上下文中执行任务，在 LoadBalancer 使用回调方法序列化任务
     *
     * <p>Pro-tip: in order to call {@link SynchronizationContext#schedule}, you need to provide a
     * {@link ScheduledExecutorService}.  {@link #getScheduledExecutorService} is provided for your
     * convenience.
     * 为了顺序调用 SynchronizationContext#schedule，需要提供 ScheduledExecutorService，
     * getScheduledExecutorService 方法用于方便提供
     *
     * @since 1.17.0
     */
    public SynchronizationContext getSynchronizationContext() {
      // TODO(zhangkun): make getSynchronizationContext() abstract after runSerialized() is deleted
      throw new UnsupportedOperationException();
    }

    /**
     * Returns a {@link ScheduledExecutorService} for scheduling delayed tasks.
     * 返回 ScheduledExecutorService 用于调度延时任务
     *
     * <p>This service is a shared resource and is only meant for quick tasks.  DO NOT block or run
     * time-consuming tasks.
     * 这个服务是共享的资源，仅用于快速执行的任务，不能用于阻塞或者长时间执行的任务
     *
     * <p>The returned service doesn't support {@link ScheduledExecutorService#shutdown shutdown()}
     * and {@link ScheduledExecutorService#shutdownNow shutdownNow()}.  They will throw if called.
     * 返回的服务不支持使用 shutdown 和 shutdownNow 方法，如果调用会抛出异常
     *
     * @since 1.17.0
     */
    public ScheduledExecutorService getScheduledExecutorService() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the NameResolver of the channel.
     * 返回 Channel 的 NameResolver
     *
     * @since 1.2.0
     * @deprecated this method will be deleted in a future release.  If you think it shouldn't be
     * deleted, please file an issue on <a href="https://github.com/grpc/grpc-java">github</a>.
     */
    @Deprecated
    public abstract NameResolver.Factory getNameResolverFactory();

    /**
     * Returns the authority string of the channel, which is derived from the DNS-style target name.
     * 返回 Channel 的目标名称
     *
     * @since 1.2.0
     */
    public abstract String getAuthority();

    /**
     * Returns the {@link ChannelLogger} for the Channel served by this LoadBalancer.
     * 返回用于 LoadBalancer 的 ChannelLogger
     *
     * @since 1.17.0
     */
    public ChannelLogger getChannelLogger() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the {@link NameResolver.Args} that the Channel uses to create {@link NameResolver}s.
     * 返回用于 Channel 创建 NameResolver 的 NameResolver.Args
     *
     * @since 1.22.0
     */
    public NameResolver.Args getNameResolverArgs() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns the {@link NameResolverRegistry} that the Channel uses to look for {@link
     * NameResolver}s.
     * 返回用于 Channel 查找 NameResolver 的 NameResolverRegistry
     *
     * @since 1.22.0
     */
    public NameResolverRegistry getNameResolverRegistry() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * A logical connection to a server, or a group of equivalent servers represented by an {@link
   * EquivalentAddressGroup}.
   * Server 的逻辑连接，或者一组等效的代表 server 的 EquivalentAddressGroup
   *
   * <p>It maintains at most one physical connection (aka transport) for sending new RPCs, while
   * also keeps track of previous transports that has been shut down but not terminated yet.
   * 最多维护一个物理连接发送 RPC，还跟踪已经关闭但是尚未终止的传输
   *
   * <p>If there isn't an active transport yet, and an RPC is assigned to the Subchannel, it will
   * create a new transport.  It won't actively create transports otherwise.  {@link
   * #requestConnection requestConnection()} can be used to ask Subchannel to create a transport if
   * there isn't any.
   * 如果没有活跃的 Transport，并且请求已经分配给 Subchannel，则会创建一个新的 Transport，除此之外不会创建活跃的
   * Transport，创建 Transport 通过 requestConnection() 方法
   *
   * <p>{@link #start} must be called prior to calling any other methods, with the exception of
   * {@link #shutdown}, which can be called at any time.
   * start 方法要先于其他所有方法调用，shutdown 可以在任何时候调用
   *
   * @since 1.2.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class Subchannel {
    /**
     * Starts the Subchannel.  Can only be called once.
     * 开始 Subchannel，只能调用一次
     *
     * <p>Must be called prior to any other method on this class, except for {@link #shutdown} which
     * may be called at any time.
     * 必须早于这个类中的其他方法调用，shutdown 可以被随时调用
     *
     * <p>Must be called from the {@link Helper#getSynchronizationContext Synchronization Context},
     * otherwise it may throw.  See <a href="https://github.com/grpc/grpc-java/issues/5015">
     * #5015</a> for more discussions.
     * 必须通过 Helper#getSynchronizationContext 返回的 Synchronization Context 调用，否则可能会抛出异常
     *
     * @param listener receives state updates for this Subchannel.
     *                 用于接收 Subchannel 的状态更新
     */
    public void start(SubchannelStateListener listener) {
      throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Shuts down the Subchannel.  After this method is called, this Subchannel should no longer
     * be returned by the latest {@link SubchannelPicker picker}, and can be safely discarded.
     * 关闭 Subchannel，当这个方法调用后，最新的 SubchannelPicker 不会再返回这个 Subchannel，可以被安全的丢弃
     *
     * <p>Calling it on an already shut-down Subchannel has no effect.
     * 调用已经关闭的 Subchannel 的 shutdown 方法没有任何效果
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     * 必须通过 Helper#getSynchronizationContext 返回的 Synchronization Context 调用，当前如果是同步只会
     * 输出警告日志，最终会抛出异常
     *
     * @since 1.2.0
     */
    public abstract void shutdown();

    /**
     * Asks the Subchannel to create a connection (aka transport), if there isn't an active one.
     * 如果没有活跃的连接，则要求 Subchannel 建立连接（即 Transport）
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * @since 1.2.0
     */
    public abstract void requestConnection();

    /**
     * Returns the addresses that this Subchannel is bound to.  This can be called only if
     * the Subchannel has only one {@link EquivalentAddressGroup}.  Under the hood it calls
     * {@link #getAllAddresses}.
     * 返回 Subchannel 绑定的地址，只有当 Subchannel 拥有一个 EquivalentAddressGroup 时调用，有多个时
     * 通过 getAllAddresses 获取
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * @throws IllegalStateException if this subchannel has more than one EquivalentAddressGroup.
     *                               Use {@link #getAllAddresses} instead
     *                               如果有多个 EquivalentAddressGroup 会抛出异常，使用 getAllAddresses 代替
     * @since 1.2.0
     */
    public final EquivalentAddressGroup getAddresses() {
      List<EquivalentAddressGroup> groups = getAllAddresses();
      Preconditions.checkState(groups.size() == 1, "%s does not have exactly one group", groups);
      return groups.get(0);
    }

    /**
     * Returns the addresses that this Subchannel is bound to. The returned list will not be empty.
     * 返回 Subchannel 绑定的地址，返回的集合不会是空的
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * @since 1.14.0
     */
    public List<EquivalentAddressGroup> getAllAddresses() {
      throw new UnsupportedOperationException();
    }

    /**
     * The same attributes passed to {@link Helper#createSubchannel Helper.createSubchannel()}.
     * LoadBalancer can use it to attach additional information here, e.g., the shard this
     * Subchannel belongs to.
     *
     * 返回 Helper.createSubchannel() 时传递的属性，LoadBalancer 使用这个对象附加更多信息
     *
     * @since 1.2.0
     */
    public abstract Attributes getAttributes();

    /**
     * (Internal use only) returns a {@link Channel} that is backed by this Subchannel.  This allows
     * a LoadBalancer to issue its own RPCs for auxiliary purposes, such as health-checking, on
     * already-established connections.  This channel has certain restrictions:
     * 返回 Subchannel 的 Channel，仅用于内部，用于 LoadBalancer 发出辅助请求，如在已经建立的连接上发出健康检查，
     * 这个 Channel 有一定的限制：
     * <ol>
     *   <li>It can issue RPCs only if the Subchannel is {@code READY}. If {@link
     *   Channel#newCall} is called when the Subchannel is not {@code READY}, the RPC will fail
     *   immediately.</li>
     *   <li>It doesn't support {@link CallOptions#withWaitForReady wait-for-ready} RPCs. Such RPCs
     *   will fail immediately.</li>
     * </ol>
     * 仅当 Subchannel 是 READY 时可以发出请求，如果 Channel#newCall 调用时 Subchannel 没有 READY，则请求会
     * 立即失败
     * 不支持 wait-for-ready 的请求，这样的请求会立即失败
     *
     * <p>RPCs made on this Channel is not counted when determining ManagedChannel's {@link
     * ManagedChannelBuilder#idleTimeout idle mode}.  In other words, they won't prevent
     * ManagedChannel from entering idle mode.
     * 这些请求不会计算在 ManagedChannel 的 IDLE 模式的请求数量内，换句话说，这些请求不会阻止 ManagedChannel
     * 进入空闲模式
     *
     * <p>Warning: RPCs made on this channel will prevent a shut-down transport from terminating. If
     * you make long-running RPCs, you need to make sure they will finish in time after the
     * Subchannel has transitioned away from {@code READY} state
     * (notified through {@link #handleSubchannelState}).
     * 在此 Channel 上进行的 RPC 将防止关闭 Transport 终止，如果有长时间执行的请求，需要确定在 Subchannel 状态变化
     * 后的过渡阶段可以完成
     *
     * <p>Warning: this is INTERNAL API, is not supposed to be used by external users, and may
     * change without notice. If you think you must use it, please file an issue.
     */
    @Internal
    public Channel asChannel() {
      throw new UnsupportedOperationException();
    }

    /**
     * Returns a {@link ChannelLogger} for this Subchannel.
     * 返回 Subchannel 的 ChannelLogger
     *
     * @since 1.17.0
     */
    public ChannelLogger getChannelLogger() {
      throw new UnsupportedOperationException();
    }

    /**
     * Replaces the existing addresses used with this {@code Subchannel}. If the new and old
     * addresses overlap, the Subchannel can continue using an existing connection.
     * <p>
     * 代替 Subchannel 使用的地址，如果有新旧地址相同，Subchannel 还可以继续使用存在的连接
     *
     * <p>It must be called from the Synchronization Context or will throw.
     *
     * @throws IllegalArgumentException if {@code addrs} is empty
     * @since 1.22.0
     */
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      throw new UnsupportedOperationException();
    }

    /**
     * (Internal use only) returns an object that represents the underlying subchannel that is used
     * by the Channel for sending RPCs when this {@link Subchannel} is picked.  This is an opaque
     * object that is both provided and consumed by the Channel.  Its type <strong>is not</strong>
     * {@code Subchannel}.
     * 返回一个对象，该对象表示选择此 Subchannel 时该通道用于发送 RPC 的基础 Subchannel，这是一个对提供者和消费者都
     * 不透明的对象，类型不是 Subchannel
     *
     * <p>Warning: this is INTERNAL API, is not supposed to be used by external users, and may
     * change without notice. If you think you must use it, please file an issue and we can consider
     * removing its "internal" status.
     */
    @Internal
    public Object getInternalSubchannel() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Receives state changes for one {@link Subchannel}. All methods are run under {@link
   * Helper#getSynchronizationContext}.
   * 接收 Subchannel 状态变化，所有的方法由  Helper#getSynchronizationContext 调用
   *
   * @since 1.22.0
   */
  public interface SubchannelStateListener {
    /**
     * Handles a state change on a Subchannel.
     * 处理 Subchannel 状态更新
     *
     * <p>The initial state of a Subchannel is IDLE. You won't get a notification for the initial
     * IDLE state.
     * Subchannel 初始状态是 IDLE，初始的 IDLE 状态没有通知
     *
     * <p>If the new state is not SHUTDOWN, this method should create a new picker and call {@link
     * Helper#updateBalancingState Helper.updateBalancingState()}.  Failing to do so may result in
     * unnecessary delays of RPCs. Please refer to {@link PickResult#withSubchannel
     * PickResult.withSubchannel()}'s javadoc for more information.
     * <p>
     * 如果新的状态不是 SHUTDOWN，这个方法应该创建一个新的 picker，并且调用 Helper.updateBalancingState()，
     * 如果操作失败可能会导致不必要的延迟，可以参考 PickResult.withSubchannel()
     *
     * <p>SHUTDOWN can only happen in two cases.  One is that LoadBalancer called {@link
     * Subchannel#shutdown} earlier, thus it should have already discarded this Subchannel.  The
     * other is that Channel is doing a {@link ManagedChannel#shutdownNow forced shutdown} or has
     * already terminated, thus there won't be further requests to LoadBalancer.  Therefore, the
     * LoadBalancer usually don't need to react to a SHUTDOWN state.
     * SHUTDOWN 只有两种情况下会发生，一种是 LoadBalancer 较早调用 Subchannel#shutdown，因此应当已经取消了
     * Subchannel；另一种是 Channel 在调用 ManagedChannel#shutdownNow 强制关闭或者已经停止，因此不会有更多
     * 的请求，LoadBalancer 通常不需要响应 SHUTDOWN 状态
     *
     * @param newState the new state 新的状态
     * @since 1.22.0
     */
    void onSubchannelState(ConnectivityStateInfo newState);
  }

  /**
   * Factory to create {@link LoadBalancer} instance.
   * 创建 LoadBalancer 实例的工厂
   *
   * @since 1.2.0
   */
  @ThreadSafe
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
  public abstract static class Factory {
    /**
     * Creates a {@link LoadBalancer} that will be used inside a channel.
     * 创建 Channel 中会使用的 LoadBalancer
     *
     * @since 1.2.0
     */
    public abstract LoadBalancer newLoadBalancer(Helper helper);
  }
}
