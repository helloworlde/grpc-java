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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.*;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.InternalChannelz.ChannelStats;
import io.grpc.InternalChannelz.ChannelTrace;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.AutoConfiguredLoadBalancerFactory.AutoConfiguredLoadBalancer;
import io.grpc.internal.ClientCallImpl.ClientTransportProvider;
import io.grpc.internal.RetriableStream.ChannelBufferMeter;
import io.grpc.internal.RetriableStream.Throttle;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.internal.ServiceConfigInterceptor.HEDGING_POLICY_KEY;
import static io.grpc.internal.ServiceConfigInterceptor.RETRY_POLICY_KEY;

/**
 * A communication channel for making outgoing RPCs.
 * 用于发送请求的 Channel
 */
@ThreadSafe
final class ManagedChannelImpl extends ManagedChannel implements
        InternalInstrumented<ChannelStats> {
  static final Logger logger = Logger.getLogger(ManagedChannelImpl.class.getName());

  // Matching this pattern means the target string is a URI target or at least intended to be one.
  // A URI target must be an absolute hierarchical URI.
  // From RFC 2396: scheme = alpha *( alpha | digit | "+" | "-" | "." )
  @VisibleForTesting
  static final Pattern URI_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*:/.*");

  static final long IDLE_TIMEOUT_MILLIS_DISABLE = -1;

  static final long SUBCHANNEL_SHUTDOWN_DELAY_SECONDS = 5;

  @VisibleForTesting
  static final Status SHUTDOWN_NOW_STATUS =
      Status.UNAVAILABLE.withDescription("Channel shutdownNow invoked");

  @VisibleForTesting
  static final Status SHUTDOWN_STATUS =
      Status.UNAVAILABLE.withDescription("Channel shutdown invoked");

  @VisibleForTesting
  static final Status SUBCHANNEL_SHUTDOWN_STATUS =
      Status.UNAVAILABLE.withDescription("Subchannel shutdown invoked");

  // 默认的空配置
  private static final ManagedChannelServiceConfig EMPTY_SERVICE_CONFIG = ManagedChannelServiceConfig.empty();

  private final InternalLogId logId;
  private final String target;
  private final NameResolverRegistry nameResolverRegistry;
  private final NameResolver.Factory nameResolverFactory;
  private final NameResolver.Args nameResolverArgs;
  private final AutoConfiguredLoadBalancerFactory loadBalancerFactory;
  private final ClientTransportFactory transportFactory;
  private final RestrictedScheduledExecutor scheduledExecutor;
  private final Executor executor;
  private final ObjectPool<? extends Executor> executorPool;
  private final ObjectPool<? extends Executor> balancerRpcExecutorPool;
  private final ExecutorHolder balancerRpcExecutorHolder;
  private final ExecutorHolder offloadExecutorHolder;
  private final TimeProvider timeProvider;
  private final int maxTraceEvents;

  @VisibleForTesting
  final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(
              Level.SEVERE,
              "[" + getLogId() + "] Uncaught exception in the SynchronizationContext. Panic!",
              e);
          panic(e);
        }
      });

  private boolean fullStreamDecompression;

  private final DecompressorRegistry decompressorRegistry;
  private final CompressorRegistry compressorRegistry;

  private final Supplier<Stopwatch> stopwatchSupplier;
  /** The timout before entering idle mode. */
  private final long idleTimeoutMillis;

  private final ConnectivityStateManager channelStateManager = new ConnectivityStateManager();

  private final ServiceConfigInterceptor serviceConfigInterceptor;

  private final BackoffPolicy.Provider backoffPolicyProvider;

  /**
   * We delegate to this channel, so that we can have interceptors as necessary. If there aren't
   * any interceptors and the {@link io.grpc.BinaryLog} is {@code null} then this will just be a
   * {@link RealChannel}.
   */
  private final Channel interceptorChannel;
  @Nullable private final String userAgent;

  // Only null after channel is terminated. Must be assigned from the syncContext.
  // 服务发现
  private NameResolver nameResolver;

  // Must be accessed from the syncContext.
  private boolean nameResolverStarted;

  // null when channel is in idle mode.  Must be assigned from syncContext.
  @Nullable
  private LbHelperImpl lbHelper;

  // Must ONLY be assigned from updateSubchannelPicker(), which is called from syncContext.
  // null if channel is in idle mode.
  // 必须通过 updateSubchannelPicker 修改，同步上下文时调用，当处于 idle 模式时为null
  @Nullable
  private volatile SubchannelPicker subchannelPicker;

  // Must be accessed from the syncContext
  private boolean panicMode;

  // Must be mutated from syncContext
  // If any monitoring hook to be added later needs to get a snapshot of this Set, we could
  // switch to a ConcurrentHashMap.
  private final Set<InternalSubchannel> subchannels = new HashSet<>(16, .75f);

  // Must be mutated from syncContext
  private final Set<OobChannel> oobChannels = new HashSet<>(1, .75f);

  // reprocess() must be run from syncContext
  private final DelayedClientTransport delayedTransport;
  // 保存未提交的可重试流的注册器
  private final UncommittedRetriableStreamsRegistry uncommittedRetriableStreamsRegistry = new UncommittedRetriableStreamsRegistry();

  // Shutdown states.
  //
  // Channel's shutdown process:
  // 1. shutdown(): stop accepting new calls from applications
  //   1a shutdown <- true
  //   1b subchannelPicker <- null
  //   1c delayedTransport.shutdown()
  // 2. delayedTransport terminated: stop stream-creation functionality
  //   2a terminating <- true
  //   2b loadBalancer.shutdown()
  //     * LoadBalancer will shutdown subchannels and OOB channels
  //   2c loadBalancer <- null
  //   2d nameResolver.shutdown()
  //   2e nameResolver <- null
  // 3. All subchannels and OOB channels terminated: Channel considered terminated

  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  // Must only be mutated and read from syncContext
  private boolean shutdownNowed;
  // Must only be mutated from syncContext
  private volatile boolean terminating;
  // Must be mutated from syncContext
  private volatile boolean terminated;
  private final CountDownLatch terminatedLatch = new CountDownLatch(1);

  private final CallTracer.Factory callTracerFactory;
  // 统计 Channel 调用信息
  private final CallTracer channelCallTracer;
  private final ChannelTracer channelTracer;
  private final ChannelLogger channelLogger;
  private final InternalChannelz channelz;

  // Must be mutated and read from syncContext
  // a flag for doing channel tracing when flipped
  private ResolutionState lastResolutionState = ResolutionState.NO_RESOLUTION;
  // Must be mutated and read from constructor or syncContext
  // used for channel tracing when value changed
  private ManagedChannelServiceConfig lastServiceConfig = EMPTY_SERVICE_CONFIG;
  @Nullable
  private final ManagedChannelServiceConfig defaultServiceConfig;
  // Must be mutated and read from constructor or syncContext
  private boolean serviceConfigUpdated = false;

  // 查找服务配置
  private final boolean lookUpServiceConfig;

  // One instance per channel.
  private final ChannelBufferMeter channelBufferUsed = new ChannelBufferMeter();

  private final long perRpcBufferLimit;
  private final long channelBufferLimit;

  // Temporary false flag that can skip the retry code path.
  // 是否开启重试
  private final boolean retryEnabled;

  // Called from syncContext
  private final ManagedClientTransport.Listener delayedTransportListener =
      new DelayedTransportListener();

  // Must be called from syncContext
  private void maybeShutdownNowSubchannels() {
    if (shutdownNowed) {
      for (InternalSubchannel subchannel : subchannels) {
        subchannel.shutdownNow(SHUTDOWN_NOW_STATUS);
      }
      for (OobChannel oobChannel : oobChannels) {
        oobChannel.getInternalSubchannel().shutdownNow(SHUTDOWN_NOW_STATUS);
      }
    }
  }

  // Must be accessed from syncContext
  @VisibleForTesting
  final InUseStateAggregator<Object> inUseStateAggregator = new IdleModeStateAggregator();

  /**
   * 返回统计对象
   *
   * @return 统计数据
   */
  @Override
  public ListenableFuture<ChannelStats> getStats() {
    // 创建一个 Future
    final SettableFuture<ChannelStats> ret = SettableFuture.create();
    final class StatsFetcher implements Runnable {
      @Override
      public void run() {
        // 创建并更新
        ChannelStats.Builder builder = new InternalChannelz.ChannelStats.Builder();
        channelCallTracer.updateBuilder(builder);
        channelTracer.updateBuilder(builder);
        // 更新状态
        builder.setTarget(target).setState(channelStateManager.getState());
        List<InternalWithLogId> children = new ArrayList<>();
        children.addAll(subchannels);
        children.addAll(oobChannels);
        builder.setSubchannels(children);
        ret.set(builder.build());
      }
    }

    // subchannels and oobchannels can only be accessed from syncContext
    // 执行统计任务
    syncContext.execute(new StatsFetcher());
    return ret;
  }

  @Override
  public InternalLogId getLogId() {
    return logId;
  }

  // Run from syncContext
  private class IdleModeTimer implements Runnable {

    @Override
    public void run() {
      // 退出空闲模式
      enterIdleMode();
    }
  }

  // Must be called from syncContext

  /**
   * 关闭服务发现和负载均衡
   *
   * @param channelIsActive Channel 是否活跃
   */
  private void shutdownNameResolverAndLoadBalancer(boolean channelIsActive) {
    syncContext.throwIfNotInThisSynchronizationContext();
    // 如果 Channel 活跃，则检查状态
    if (channelIsActive) {
      checkState(nameResolverStarted, "nameResolver is not started");
      checkState(lbHelper != null, "lbHelper is null");
    }
    if (nameResolver != null) {
      // 取消任务
      cancelNameResolverBackoff();
      // 关闭服务发现、监听器
      nameResolver.shutdown();
      nameResolverStarted = false;
      // 如果 Channel 依然活跃，则获取其服务发现对象
      if (channelIsActive) {
        nameResolver = getNameResolver(target, nameResolverFactory, nameResolverArgs);
      } else {
        nameResolver = null;
      }
    }
    // 如果负载均衡不为空，则关闭
    if (lbHelper != null) {
      lbHelper.lb.shutdown();
      lbHelper = null;
    }
    subchannelPicker = null;
  }

  /**
   * 让 Channel 退出空闲模式
   * Make the channel exit idle mode, if it's in it.
   *
   * <p>Must be called from syncContext
   */
  @VisibleForTesting
  void exitIdleMode() {
    syncContext.throwIfNotInThisSynchronizationContext();

    // 如果关闭或有错误，则返回
    if (shutdown.get() || panicMode) {
      return;
    }
    // 如果在使用中，则取消计时器
    if (inUseStateAggregator.isInUse()) {
      // Cancel the timer now, so that a racing due timer will not put Channel on idleness
      // when the caller of exitIdleMode() is about to use the returned loadBalancer.
      // 现在取消计时器，以便当 exitIdleMode 的调用者将要使用返回的 loadBalancer 时，到期计时器不会将 Channel 置于空闲状态
      cancelIdleTimer(false);
    } else {
      // exitIdleMode() may be called outside of inUseStateAggregator.handleNotInUse() while
      // isInUse() == false, in which case we still need to schedule the timer.
      // 重新调度计时器
      rescheduleIdleTimer();
    }

    // 如果 lbHelper 不为空，则返回
    if (lbHelper != null) {
      return;
    }
    channelLogger.log(ChannelLogLevel.INFO, "Exiting idle mode");
    // 构建新的 lbHelper
    LbHelperImpl lbHelper = new LbHelperImpl();

    // 自动配置负载均衡
    lbHelper.lb = loadBalancerFactory.newLoadBalancer(lbHelper);
    // Delay setting lbHelper until fully initialized, since loadBalancerFactory is user code and
    // may throw. We don't want to confuse our state, even if we will enter panic mode.
    this.lbHelper = lbHelper;

    // 服务发现监听器
    NameResolverListener listener = new NameResolverListener(lbHelper, nameResolver);
    nameResolver.start(listener);
    nameResolverStarted = true;
  }

  /**
   * 退出空闲模式
   */
  // Must be run from syncContext
  private void enterIdleMode() {
    // nameResolver and loadBalancer are guaranteed to be non-null.  If any of them were null,
    // either the idleModeTimer ran twice without exiting the idle mode, or the task in shutdown()
    // did not cancel idleModeTimer, or enterIdle() ran while shutdown or in idle, all of
    // which are bugs.
    // 关闭服务发现和负载均衡
    shutdownNameResolverAndLoadBalancer(true);
    // 设置选择器，重新创建 Transport，处理流
    delayedTransport.reprocess(null);
    channelLogger.log(ChannelLogLevel.INFO, "Entering IDLE state");
    // Channel 状态变为空闲
    channelStateManager.gotoState(IDLE);
    // 如果状态是使用中，则再次尝试退出
    if (inUseStateAggregator.isInUse()) {
      exitIdleMode();
    }
  }

  /**
   * 取消空闲计时器
   * @param permanent
   */
  // Must be run from syncContext
  private void cancelIdleTimer(boolean permanent) {
    idleTimer.cancel(permanent);
  }

  /**
   * 重新调度空闲计时器
   */
  // Always run from syncContext
  private void rescheduleIdleTimer() {
    // 如果不允许空闲超时，则直接返回
    if (idleTimeoutMillis == IDLE_TIMEOUT_MILLIS_DISABLE) {
      return;
    }
    // 如果允许超时则重新调度
    idleTimer.reschedule(idleTimeoutMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * 延迟执行服务发现
   */
  // Run from syncContext
  @VisibleForTesting
  class DelayedNameResolverRefresh implements Runnable {
    @Override
    public void run() {
      scheduledNameResolverRefresh = null;
      refreshNameResolution();
    }
  }

  // Must be used from syncContext
  @Nullable private ScheduledHandle scheduledNameResolverRefresh;
  // The policy to control backoff between name resolution attempts. Non-null when an attempt is
  // scheduled. Must be used from syncContext
  @Nullable private BackoffPolicy nameResolverBackoffPolicy;

  /**
   * 取消服务发现任务
   */
  // Must be run from syncContext
  private void cancelNameResolverBackoff() {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (scheduledNameResolverRefresh != null) {
      scheduledNameResolverRefresh.cancel();
      scheduledNameResolverRefresh = null;
      nameResolverBackoffPolicy = null;
    }
  }

  /**
   * Force name resolution refresh to happen immediately and reset refresh back-off. Must be run
   * from syncContext.
   * 强制更新服务发现，并重置刷新时间
   */
  private void refreshAndResetNameResolution() {
    syncContext.throwIfNotInThisSynchronizationContext();
    // 取消当前任务
    cancelNameResolverBackoff();
    // 重新调度服务发现
    refreshNameResolution();
  }

  /**
   * 更新服务发现
   */
  private void refreshNameResolution() {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (nameResolverStarted) {
      // 调用服务发现进行更新
      nameResolver.refresh();
    }
  }

  private final class ChannelTransportProvider implements ClientTransportProvider {
    /**
     * 通过参数，选择某个 Subchannel，发起调用
     *
     * @param args object containing call arguments.
     * @return
     */
    @Override
    public ClientTransport get(PickSubchannelArgs args) {
      SubchannelPicker pickerCopy = subchannelPicker;
      // 如果是关闭状态，则停止调用
      if (shutdown.get()) {
        // If channel is shut down, delayedTransport is also shut down which will fail the stream
        // properly.
        return delayedTransport;
      }
      // 如果是 SubchannelPicker 是空的，则退出 idle 模模式，返回 delayedTransport
      if (pickerCopy == null) {
        final class ExitIdleModeForTransport implements Runnable {
          @Override
          public void run() {
            // 退出 idle 模式，将会创建 LoadBalancer,NameResovler
            exitIdleMode();
          }
        }

        syncContext.execute(new ExitIdleModeForTransport());
        return delayedTransport;
      }
      // There is no need to reschedule the idle timer here.
      // 此处无需重新调度 idle 计时器
      //
      // pickerCopy != null, which means idle timer has not expired when this method starts.
      // Even if idle timer expires right after we grab pickerCopy, and it shuts down LoadBalancer
      // which calls Subchannel.shutdown(), the InternalSubchannel will be actually shutdown after
      // SUBCHANNEL_SHUTDOWN_DELAY_SECONDS, which gives the caller time to start RPC on it.
      // 如果 pickerCopy 不为null，则意味着 idle 计时器在方法启动时没有过期，即使在检查完 pickerCopy 后过期了
      // 会在调用  Subchannel.shutdown() 时关闭 LoadBalancer，在 SUBCHANNEL_SHUTDOWN_DELAY_SECONDS 时间
      // 之后 InternalSubchannel 会被真正关闭，这使调用者有时间在其上启动RPC
      //
      // In most cases the idle timer is scheduled to fire after the transport has created the
      // stream, which would have reported in-use state to the channel that would have cancelled
      // the idle timer.
      // 大多数情况下，idle 计时器会在传输创建流后开始启动，它将向正在取消空闲计时器的通道报告使用中状态
      // 选择某个 SubChannel 发起调用，即选择某个服务端
      PickResult pickResult = pickerCopy.pickSubchannel(args);
      ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult, args.getCallOptions().isWaitForReady());
      // 如果有 Transport，则返回
      if (transport != null) {
        return transport;
      }
      return delayedTransport;
    }

    /**
     * 创建可重试流
     *
     * @param method      调用的方法描述
     * @param callOptions 调用的选项
     * @param headers     请求头
     * @param context     请求上下文
     * @return 可重试的流
     */
    @Override
    public <ReqT> ClientStream newRetriableStream(final MethodDescriptor<ReqT, ?> method,
                                                  final CallOptions callOptions,
                                                  final Metadata headers,
                                                  final Context context) {

      checkState(retryEnabled, "retry should be enabled");

      // 获取节流配置
      final Throttle throttle = lastServiceConfig.getRetryThrottling();

      // 定义可重试流
      final class RetryStream extends RetriableStream<ReqT> {
        // 构造重试流
        RetryStream() {
          super(method,
                  headers,
                  channelBufferUsed,
                  perRpcBufferLimit,
                  channelBufferLimit,
                  getCallExecutor(callOptions),
                  transportFactory.getScheduledExecutorService(),
                  callOptions.getOption(RETRY_POLICY_KEY),
                  callOptions.getOption(HEDGING_POLICY_KEY),
                  throttle);
        }

        /**
         * 将未提交的可重试流添加到注册器中
         *
         * @return 如果 channel 没有关闭则返回 null，否则返回关闭状态
         */
        @Override
        Status prestart() {
          return uncommittedRetriableStreamsRegistry.add(this);
        }

        /**
         * 提交后的操作
         */
        @Override
        void postCommit() {
          // 将当前流从未提交的流中移除
          uncommittedRetriableStreamsRegistry.remove(this);
        }

        /**
         * 创建流
         *
         * @param tracerFactory 跟踪的线程工厂
         * @param newHeaders 新的 header
         * @return
         */
        @Override
        ClientStream newSubstream(ClientStreamTracer.Factory tracerFactory, Metadata newHeaders) {
          CallOptions newOptions = callOptions.withStreamTracerFactory(tracerFactory);
          // 重试，重新 pick subchannel
          ClientTransport transport = get(new PickSubchannelArgsImpl(method, newHeaders, newOptions));
          Context origContext = context.attach();
          try {
            // 创建新的流，并返回
            return transport.newStream(method, newHeaders, newOptions);
          } finally {
            context.detach(origContext);
          }
        }
      }

      return new RetryStream();
    }
  }

  private final ClientTransportProvider transportProvider = new ChannelTransportProvider();

  private final Rescheduler idleTimer;

  /**
   * Channel 构造方法
   *
   * @param builder                 构造器
   * @param clientTransportFactory  Transport 工程
   * @param backoffPolicyProvider   回退策略提供器
   * @param balancerRpcExecutorPool 线程池
   * @param stopwatchSupplier       计时器
   * @param interceptors            统计和追踪拦截器
   * @param timeProvider            时间提供器
   */
  ManagedChannelImpl(
          AbstractManagedChannelImplBuilder<?> builder,
          ClientTransportFactory clientTransportFactory,
          BackoffPolicy.Provider backoffPolicyProvider,
          ObjectPool<? extends Executor> balancerRpcExecutorPool,
          Supplier<Stopwatch> stopwatchSupplier,
          List<ClientInterceptor> interceptors,
          final TimeProvider timeProvider) {
    this.target = checkNotNull(builder.target, "target");
    this.logId = InternalLogId.allocate("Channel", target);
    this.timeProvider = checkNotNull(timeProvider, "timeProvider");
    this.executorPool = checkNotNull(builder.executorPool, "executorPool");
    this.executor = checkNotNull(executorPool.getObject(), "executor");
    this.transportFactory = new CallCredentialsApplyingTransportFactory(clientTransportFactory, this.executor);
    this.scheduledExecutor = new RestrictedScheduledExecutor(transportFactory.getScheduledExecutorService());
    maxTraceEvents = builder.maxTraceEvents;
    channelTracer = new ChannelTracer(logId, builder.maxTraceEvents, timeProvider.currentTimeNanos(), "Channel for '" + target + "'");
    channelLogger = new ChannelLoggerImpl(channelTracer, timeProvider);

    // 服务发现工厂
    this.nameResolverFactory = builder.getNameResolverFactory();
    ProxyDetector proxyDetector = builder.proxyDetector != null ? builder.proxyDetector : GrpcUtil.DEFAULT_PROXY_DETECTOR;

    // 是否开启重试，根据 builder 的 retryEnabled 值和 temporarilyDisableRetry 决定，当调用 enableRetry 后，temporarilyDisableRetry 是false
    this.retryEnabled = builder.retryEnabled && !builder.temporarilyDisableRetry;

    // 负载均衡策略
    this.loadBalancerFactory = new AutoConfiguredLoadBalancerFactory(builder.defaultLbPolicy);
    this.offloadExecutorHolder = new ExecutorHolder(checkNotNull(builder.offloadExecutorPool, "offloadExecutorPool"));
    this.nameResolverRegistry = builder.nameResolverRegistry;

    // 配置解析器
    ScParser serviceConfigParser = new ScParser(
            retryEnabled,
            builder.maxRetryAttempts,
            builder.maxHedgedAttempts,
            loadBalancerFactory,
            channelLogger);

    // 命名解析器参数
    this.nameResolverArgs = NameResolver.Args.newBuilder()
                                             .setDefaultPort(builder.getDefaultPort())
                                             .setProxyDetector(proxyDetector)
                                             .setSynchronizationContext(syncContext)
                                             .setScheduledExecutorService(scheduledExecutor)
                                             .setServiceConfigParser(serviceConfigParser)
                                             .setChannelLogger(channelLogger)
                                             .setOffloadExecutor(
                                                     // Avoid creating the offloadExecutor until it is first used
                                                     new Executor() {
                                                       @Override
                                                       public void execute(Runnable command) {
                                                         offloadExecutorHolder.getExecutor().execute(command);
                                                       }
                                                     })
                                             .build();

    // 命名解析
    this.nameResolver = getNameResolver(target, nameResolverFactory, nameResolverArgs);

    this.balancerRpcExecutorPool = checkNotNull(balancerRpcExecutorPool, "balancerRpcExecutorPool");
    this.balancerRpcExecutorHolder = new ExecutorHolder(balancerRpcExecutorPool);
    this.delayedTransport = new DelayedClientTransport(this.executor, this.syncContext);
    this.delayedTransport.start(delayedTransportListener);
    this.backoffPolicyProvider = backoffPolicyProvider;

    // 服务配置拦截器
    serviceConfigInterceptor = new ServiceConfigInterceptor(retryEnabled);

    // 如果 builder 有配置，则解析配置
    if (builder.defaultServiceConfig != null) {
      // 解析配置
      ConfigOrError parsedDefaultServiceConfig = serviceConfigParser.parseServiceConfig(builder.defaultServiceConfig);
      // 校验
      checkState(parsedDefaultServiceConfig.getError() == null, "Default config is invalid: %s", parsedDefaultServiceConfig.getError());
      this.defaultServiceConfig = (ManagedChannelServiceConfig) parsedDefaultServiceConfig.getConfig();
      this.lastServiceConfig = this.defaultServiceConfig;
    } else {
      this.defaultServiceConfig = null;
    }

    this.lookUpServiceConfig = builder.lookUpServiceConfig;
    // 创建 Channel
    Channel channel = new RealChannel(nameResolver.getServiceAuthority());
    // 添加方法拦截器
    channel = ClientInterceptors.intercept(channel, serviceConfigInterceptor);

    if (builder.binlog != null) {
      channel = builder.binlog.wrapChannel(channel);
    }

    this.interceptorChannel = ClientInterceptors.intercept(channel, interceptors);
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    if (builder.idleTimeoutMillis == IDLE_TIMEOUT_MILLIS_DISABLE) {
      this.idleTimeoutMillis = builder.idleTimeoutMillis;
    } else {
      checkArgument(builder.idleTimeoutMillis >= AbstractManagedChannelImplBuilder.IDLE_MODE_MIN_TIMEOUT_MILLIS, "invalid idleTimeoutMillis %s", builder.idleTimeoutMillis);
      this.idleTimeoutMillis = builder.idleTimeoutMillis;
    }

    idleTimer = new Rescheduler(new IdleModeTimer(),
            syncContext,
            transportFactory.getScheduledExecutorService(),
            stopwatchSupplier.get());

    this.fullStreamDecompression = builder.fullStreamDecompression;
    this.decompressorRegistry = checkNotNull(builder.decompressorRegistry, "decompressorRegistry");
    this.compressorRegistry = checkNotNull(builder.compressorRegistry, "compressorRegistry");
    this.userAgent = builder.userAgent;

    this.channelBufferLimit = builder.retryBufferSize;
    this.perRpcBufferLimit = builder.perRpcBufferLimit;
    final class ChannelCallTracerFactory implements CallTracer.Factory {
      @Override
      public CallTracer create() {
        return new CallTracer(timeProvider);
      }
    }

    this.callTracerFactory = new ChannelCallTracerFactory();
    channelCallTracer = callTracerFactory.create();
    this.channelz = checkNotNull(builder.channelz);
    channelz.addRootChannel(this);

    // 如果没有开启则使用默认配置
    if (!lookUpServiceConfig) {
      if (defaultServiceConfig != null) {
        channelLogger.log(ChannelLogLevel.INFO, "Service config look-up disabled, using default service config");
      }
      handleServiceConfigUpdate();
    }
  }

  // May only be called in constructor or syncContext
  // 更新服务配置
  private void handleServiceConfigUpdate() {
    serviceConfigUpdated = true;
    serviceConfigInterceptor.handleUpdate(lastServiceConfig);
  }

  /**
   * 服务发现
   *
   * @param target              服务名称
   * @param nameResolverFactory
   * @param nameResolverArgs
   * @return
   */
  @VisibleForTesting
  static NameResolver getNameResolver(String target,
                                      NameResolver.Factory nameResolverFactory,
                                      NameResolver.Args nameResolverArgs) {
    // Finding a NameResolver. Try using the target string as the URI. If that fails, try prepending
    // "dns:///".
    URI targetUri = null;
    StringBuilder uriSyntaxErrors = new StringBuilder();
    // 解析地址
    try {
      targetUri = new URI(target);
      // For "localhost:8080" this would likely cause newNameResolver to return null, because
      // "localhost" is parsed as the scheme. Will fall into the next branch and try
      // "dns:///localhost:8080".
    } catch (URISyntaxException e) {
      // Can happen with ip addresses like "[::1]:1234" or 127.0.0.1:1234.
      uriSyntaxErrors.append(e.getMessage());
    }

    // 创建 NameResolver
    if (targetUri != null) {
      NameResolver resolver = nameResolverFactory.newNameResolver(targetUri, nameResolverArgs);
      if (resolver != null) {
        return resolver;
      }
      // "foo.googleapis.com:8080" cause resolver to be null, because "foo.googleapis.com" is an
      // unmapped scheme. Just fall through and will try "dns:///foo.googleapis.com:8080"
    }

    // If we reached here, the targetUri couldn't be used.
    // 如果不是 URI 格式，则使用默认的 schema
    if (!URI_PATTERN.matcher(target).matches()) {
      // It doesn't look like a URI target. Maybe it's an authority string. Try with the default
      // scheme from the factory.
      try {
        targetUri = new URI(nameResolverFactory.getDefaultScheme(), "", "/" + target, null);
      } catch (URISyntaxException e) {
        // Should not be possible.
        throw new IllegalArgumentException(e);
      }

      NameResolver resolver = nameResolverFactory.newNameResolver(targetUri, nameResolverArgs);
      if (resolver != null) {
        return resolver;
      }
    }
    throw new IllegalArgumentException(String.format("cannot find a NameResolver for %s%s", target, uriSyntaxErrors.length() > 0 ? " (" + uriSyntaxErrors + ")" : ""));
  }

  /**
   * Initiates an orderly shutdown in which preexisting calls continue but new calls are immediately
   * cancelled.
   */
  @Override
  public ManagedChannelImpl shutdown() {
    channelLogger.log(ChannelLogLevel.DEBUG, "shutdown() called");
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }

    // Put gotoState(SHUTDOWN) as early into the syncContext's queue as possible.
    // delayedTransport.shutdown() may also add some tasks into the queue. But some things inside
    // delayedTransport.shutdown() like setting delayedTransport.shutdown = true are not run in the
    // syncContext's queue and should not be blocked, so we do not drain() immediately here.
    final class Shutdown implements Runnable {
      @Override
      public void run() {
        channelLogger.log(ChannelLogLevel.INFO, "Entering SHUTDOWN state");
        channelStateManager.gotoState(SHUTDOWN);
      }
    }

    syncContext.executeLater(new Shutdown());

    uncommittedRetriableStreamsRegistry.onShutdown(SHUTDOWN_STATUS);
    final class CancelIdleTimer implements Runnable {
      @Override
      public void run() {
        cancelIdleTimer(/* permanent= */ true);
      }
    }

    syncContext.execute(new CancelIdleTimer());
    return this;
  }

  /**
   * Initiates a forceful shutdown in which preexisting and new calls are cancelled. Although
   * forceful, the shutdown process is still not instantaneous; {@link #isTerminated()} will likely
   * return {@code false} immediately after this method returns.
   */
  @Override
  public ManagedChannelImpl shutdownNow() {
    channelLogger.log(ChannelLogLevel.DEBUG, "shutdownNow() called");
    shutdown();
    uncommittedRetriableStreamsRegistry.onShutdownNow(SHUTDOWN_NOW_STATUS);
    final class ShutdownNow implements Runnable {
      @Override
      public void run() {
        if (shutdownNowed) {
          return;
        }
        shutdownNowed = true;
        maybeShutdownNowSubchannels();
      }
    }

    syncContext.execute(new ShutdownNow());
    return this;
  }

  // Called from syncContext
  @VisibleForTesting
  void panic(final Throwable t) {
    if (panicMode) {
      // Preserve the first panic information
      return;
    }
    panicMode = true;
    cancelIdleTimer(/* permanent= */ true);
    shutdownNameResolverAndLoadBalancer(false);
    final class PanicSubchannelPicker extends SubchannelPicker {
      private final PickResult panicPickResult =
          PickResult.withDrop(
              Status.INTERNAL.withDescription("Panic! This is a bug!").withCause(t));

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return panicPickResult;
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(PanicSubchannelPicker.class)
            .add("panicPickResult", panicPickResult)
            .toString();
      }
    }

    updateSubchannelPicker(new PanicSubchannelPicker());
    channelLogger.log(ChannelLogLevel.ERROR, "PANIC! Entering TRANSIENT_FAILURE");
    channelStateManager.gotoState(TRANSIENT_FAILURE);
  }

  @VisibleForTesting
  boolean isInPanicMode() {
    return panicMode;
  }

  /**
   * 更新 Channel 新的 picker
   *
   * @param newPicker
   */
  // Called from syncContext
  private void updateSubchannelPicker(SubchannelPicker newPicker) {
    subchannelPicker = newPicker;
    // 处理待处理的流
    delayedTransport.reprocess(newPicker);
  }

  @Override
  public boolean isShutdown() {
    return shutdown.get();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return terminatedLatch.await(timeout, unit);
  }

  @Override
  public boolean isTerminated() {
    return terminated;
  }

  /**
   * Creates a new outgoing call on the channel.
   * 在当前 channel 上创建一个新的调用
   * BlockingStub 初始化 ClientCall 执行请求顺序: 4
   */
  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method,
                                                       CallOptions callOptions) {
    return interceptorChannel.newCall(method, callOptions);
  }

  @Override
  public String authority() {
    return interceptorChannel.authority();
  }

  /**
   * 从调用选项中获取执行器，在第一步调用时创建
   *
   * @param callOptions
   * @return
   */
  private Executor getCallExecutor(CallOptions callOptions) {
    // 从 CallOptions 获取执行器
    Executor executor = callOptions.getExecutor();
    // 如果不存在，则使用创建 ManagedChannel 时 builder 中的线程池，即 GrpcUtil.SHARED_CHANNEL_EXECUTOR
    if (executor == null) {
      executor = this.executor;
    }
    return executor;
  }

  /**
   * 真正创建 ClientCallImpl 的 Channel
   */
  private class RealChannel extends Channel {
    // Set when the NameResolver is initially created. When we create a new NameResolver for the
    // same target, the new instance must have the same value.
    // 创建 NameResolver 时设置，当为同一个模板创建新的 NameResolver 时，新的实例必须有相同的值；即 IP:PORT 或服务名
    private final String authority;

    private RealChannel(String authority) {
      this.authority =  checkNotNull(authority, "authority");
    }

    /**
     * BlockingStub 初始化 ClientCall 执行请求顺序: 7
     */
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method,
                                                         CallOptions callOptions) {
      return new ClientCallImpl<>(method,
              // 执行的线程池
              getCallExecutor(callOptions),
              // 调用的参数
              callOptions,
              // Transport 提供器
              transportProvider,
              // 如果没有关闭，则获取用于调度的执行器
              terminated ? null : transportFactory.getScheduledExecutorService(),
              // 统计 Channel 调用信息
              channelCallTracer,
              // 是否重试
              retryEnabled)
              .setFullStreamDecompression(fullStreamDecompression)
              .setDecompressorRegistry(decompressorRegistry)
              .setCompressorRegistry(compressorRegistry);
    }

    @Override
    public String authority() {
      return authority;
    }
  }

  /**
   * Terminate the channel if termination conditions are met.
   * 当终止条件满足时终止 Subchannel
   */
  // Must be run from syncContext
  private void maybeTerminateChannel() {
    // 如果已经终止了，则直接返回
    if (terminated) {
      return;
    }

    // 如果已经 Shutdown，则移除 Channel，返回连接池，释放 LB，关闭 Transport
    if (shutdown.get() && subchannels.isEmpty() && oobChannels.isEmpty()) {
      channelLogger.log(ChannelLogLevel.INFO, "Terminated");
      channelz.removeRootChannel(this);
      executorPool.returnObject(executor);
      balancerRpcExecutorHolder.release();
      offloadExecutorHolder.release();
      // Release the transport factory so that it can deallocate any resources.
      transportFactory.close();

      terminated = true;
      terminatedLatch.countDown();
    }
  }

  // Must be called from syncContext

  /**
   * 处理 Subchannel 状态变化
   *
   * @param newState 新的状态
   */
  private void handleInternalSubchannelState(ConnectivityStateInfo newState) {
    // 如果状态是 TRANSIENT_FAILURE 或者 IDLE
    if (newState.getState() == TRANSIENT_FAILURE || newState.getState() == IDLE) {
      // 强制刷新 NameResolver
      refreshAndResetNameResolution();
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  public ConnectivityState getState(boolean requestConnection) {
    ConnectivityState savedChannelState = channelStateManager.getState();
    if (requestConnection && savedChannelState == IDLE) {
      final class RequestConnection implements Runnable {
        @Override
        public void run() {
          exitIdleMode();
          if (subchannelPicker != null) {
            subchannelPicker.requestConnection();
          }
          if (lbHelper != null) {
            lbHelper.lb.requestConnection();
          }
        }
      }

      syncContext.execute(new RequestConnection());
    }
    return savedChannelState;
  }

  @Override
  public void notifyWhenStateChanged(final ConnectivityState source, final Runnable callback) {
    final class NotifyStateChanged implements Runnable {
      @Override
      public void run() {
        channelStateManager.notifyWhenStateChanged(callback, executor, source);
      }
    }

    syncContext.execute(new NotifyStateChanged());
  }

  @Override
  public void resetConnectBackoff() {
    final class ResetConnectBackoff implements Runnable {
      @Override
      public void run() {
        if (shutdown.get()) {
          return;
        }
        if (scheduledNameResolverRefresh != null && scheduledNameResolverRefresh.isPending()) {
          checkState(nameResolverStarted, "name resolver must be started");
          refreshAndResetNameResolution();
        }
        for (InternalSubchannel subchannel : subchannels) {
          subchannel.resetConnectBackoff();
        }
        for (OobChannel oobChannel : oobChannels) {
          oobChannel.resetConnectBackoff();
        }
      }
    }

    syncContext.execute(new ResetConnectBackoff());
  }

  @Override
  public void enterIdle() {
    final class PrepareToLoseNetworkRunnable implements Runnable {
      @Override
      public void run() {
        if (shutdown.get() || lbHelper == null) {
          return;
        }
        cancelIdleTimer(/* permanent= */ false);
        enterIdleMode();
      }
    }

    syncContext.execute(new PrepareToLoseNetworkRunnable());
  }

  /**
   * A registry that prevents channel shutdown from killing existing retry attempts that are in
   * backoff.
   * 防止关闭 channel 时杀掉存在的退让重试请求的注册器
   */
  private final class UncommittedRetriableStreamsRegistry {
    // TODO(zdapeng): This means we would acquire a lock for each new retry-able stream,
    // it's worthwhile to look for a lock-free approach.
    final Object lock = new Object();

    /**
     * 用于保存可重试流的集合
     */
    @GuardedBy("lock")
    Collection<ClientStream> uncommittedRetriableStreams = new HashSet<>();

    @GuardedBy("lock")
    Status shutdownStatus;

    void onShutdown(Status reason) {
      boolean shouldShutdownDelayedTransport = false;
      synchronized (lock) {
        if (shutdownStatus != null) {
          return;
        }
        shutdownStatus = reason;
        // Keep the delayedTransport open until there is no more uncommitted streams, b/c those
        // retriable streams, which may be in backoff and not using any transport, are already
        // started RPCs.
        if (uncommittedRetriableStreams.isEmpty()) {
          shouldShutdownDelayedTransport = true;
        }
      }

      if (shouldShutdownDelayedTransport) {
        delayedTransport.shutdown(reason);
      }
    }

    void onShutdownNow(Status reason) {
      onShutdown(reason);
      Collection<ClientStream> streams;

      synchronized (lock) {
        streams = new ArrayList<>(uncommittedRetriableStreams);
      }

      for (ClientStream stream : streams) {
        stream.cancel(reason);
      }
      delayedTransport.shutdownNow(reason);
    }

    /**
     * Registers a RetriableStream and return null if not shutdown, otherwise just returns the
     * shutdown Status.
     * 注册一个可重试的流，如果没有关闭则返回 null，否则返回关闭状态
     */
    @Nullable
    Status add(RetriableStream<?> retriableStream) {
      synchronized (lock) {
        if (shutdownStatus != null) {
          return shutdownStatus;
        }
        uncommittedRetriableStreams.add(retriableStream);
        return null;
      }
    }

    void remove(RetriableStream<?> retriableStream) {
      Status shutdownStatusCopy = null;

      synchronized (lock) {
        uncommittedRetriableStreams.remove(retriableStream);
        if (uncommittedRetriableStreams.isEmpty()) {
          shutdownStatusCopy = shutdownStatus;
          // Because retriable transport is long-lived, we take this opportunity to down-size the
          // hashmap.
          uncommittedRetriableStreams = new HashSet<>();
        }
      }

      if (shutdownStatusCopy != null) {
        delayedTransport.shutdown(shutdownStatusCopy);
      }
    }
  }


  /**
   * LoadBalancer Helper
   */
  private class LbHelperImpl extends LoadBalancer.Helper {
    AutoConfiguredLoadBalancer lb;

    /**
     * 创建 Subchannel
     */
    @Deprecated
    @Override
    public AbstractSubchannel createSubchannel(List<EquivalentAddressGroup> addressGroups, Attributes attrs) {
      logWarningIfNotInSyncContext("createSubchannel()");
      // TODO(ejona): can we be even stricter? Like loadBalancer == null?
      checkNotNull(addressGroups, "addressGroups");
      checkNotNull(attrs, "attrs");

      // 创建 Subchannel
      final SubchannelImpl subchannel = createSubchannelInternal(CreateSubchannelArgs.newBuilder()
                                                                                     .setAddresses(addressGroups)
                                                                                     .setAttributes(attrs)
                                                                                     .build());

      // 创建 Subchannel 状态监听器
      final SubchannelStateListener listener = new LoadBalancer.SubchannelStateListener() {
        @Override
        public void onSubchannelState(ConnectivityStateInfo newState) {
          // Call LB only if it's not shutdown.  If LB is shutdown, lbHelper won't match.
          // 只有 LB 没有 shutdown 的时候调用，如果 LB 是 shutdown， lbHelper 不会匹配
          if (LbHelperImpl.this != ManagedChannelImpl.this.lbHelper) {
            return;
          }
          // 更新 Subchannel 状态
          lb.handleSubchannelState(subchannel, newState);
        }
      };

      subchannel.internalStart(listener);
      return subchannel;
    }

    @Override
    public AbstractSubchannel createSubchannel(CreateSubchannelArgs args) {
      syncContext.throwIfNotInThisSynchronizationContext();
      return createSubchannelInternal(args);
    }

    /**
     * 创建 Subchannel
     * @param args 参数
     * @return Subchannel
     */
    private SubchannelImpl createSubchannelInternal(CreateSubchannelArgs args) {
      // TODO(ejona): can we be even stricter? Like loadBalancer == null?
      checkState(!terminated, "Channel is terminated");
      // 创建 Subchannel 实现
      return new SubchannelImpl(args, this);
    }


    /**
     * 使用新的 picker 为 channel 设置新的状态
     *
     * @param newState  新的状态
     * @param newPicker 新的 picker
     */
    @Override
    public void updateBalancingState(final ConnectivityState newState,
                                     final SubchannelPicker newPicker) {
      checkNotNull(newState, "newState");
      checkNotNull(newPicker, "newPicker");
      logWarningIfNotInSyncContext("updateBalancingState()");
      final class UpdateBalancingState implements Runnable {
        @Override
        public void run() {
          if (LbHelperImpl.this != lbHelper) {
            return;
          }
          // 更新选择器，处理流
          updateSubchannelPicker(newPicker);
          // It's not appropriate to report SHUTDOWN state from lb.
          // Ignore the case of newState == SHUTDOWN for now.
          if (newState != SHUTDOWN) {
            channelLogger.log(ChannelLogLevel.INFO, "Entering {0} state with picker: {1}", newState, newPicker);
            // 更新状态
            channelStateManager.gotoState(newState);
          }
        }
      }

      // 执行更新
      syncContext.execute(new UpdateBalancingState());
    }

    @Override
    public void refreshNameResolution() {
      logWarningIfNotInSyncContext("refreshNameResolution()");
      final class LoadBalancerRefreshNameResolution implements Runnable {
        @Override
        public void run() {
          refreshAndResetNameResolution();
        }
      }

      syncContext.execute(new LoadBalancerRefreshNameResolution());
    }

    @Deprecated
    @Override
    public void updateSubchannelAddresses(
            LoadBalancer.Subchannel subchannel, List<EquivalentAddressGroup> addrs) {
      checkArgument(subchannel instanceof SubchannelImpl,
              "subchannel must have been returned from createSubchannel");
      logWarningIfNotInSyncContext("updateSubchannelAddresses()");
      ((InternalSubchannel) subchannel.getInternalSubchannel()).updateAddresses(addrs);
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup addressGroup, String authority) {
      // TODO(ejona): can we be even stricter? Like terminating?
      checkState(!terminated, "Channel is terminated");
      long oobChannelCreationTime = timeProvider.currentTimeNanos();
      InternalLogId oobLogId = InternalLogId.allocate("OobChannel", /*details=*/ null);
      InternalLogId subchannelLogId =
              InternalLogId.allocate("Subchannel-OOB", /*details=*/ authority);
      ChannelTracer oobChannelTracer =
              new ChannelTracer(
                      oobLogId, maxTraceEvents, oobChannelCreationTime,
                      "OobChannel for " + addressGroup);
      final OobChannel oobChannel = new OobChannel(
              authority, balancerRpcExecutorPool, transportFactory.getScheduledExecutorService(),
              syncContext, callTracerFactory.create(), oobChannelTracer, channelz, timeProvider);
      channelTracer.reportEvent(new ChannelTrace.Event.Builder()
              .setDescription("Child OobChannel created")
              .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
              .setTimestampNanos(oobChannelCreationTime)
              .setChannelRef(oobChannel)
              .build());
      ChannelTracer subchannelTracer =
              new ChannelTracer(subchannelLogId, maxTraceEvents, oobChannelCreationTime,
                      "Subchannel for " + addressGroup);
      ChannelLogger subchannelLogger = new ChannelLoggerImpl(subchannelTracer, timeProvider);
      final class ManagedOobChannelCallback extends InternalSubchannel.Callback {
        @Override
        void onTerminated(InternalSubchannel is) {
          oobChannels.remove(oobChannel);
          channelz.removeSubchannel(is);
          oobChannel.handleSubchannelTerminated();
          maybeTerminateChannel();
        }

        @Override
        void onStateChange(InternalSubchannel is, ConnectivityStateInfo newState) {
          handleInternalSubchannelState(newState);
          oobChannel.handleSubchannelStateChange(newState);
        }
      }

      final InternalSubchannel internalSubchannel = new InternalSubchannel(
              Collections.singletonList(addressGroup),
              authority, userAgent, backoffPolicyProvider, transportFactory,
              transportFactory.getScheduledExecutorService(), stopwatchSupplier, syncContext,
              // All callback methods are run from syncContext
              new ManagedOobChannelCallback(),
              channelz,
              callTracerFactory.create(),
              subchannelTracer,
              subchannelLogId,
              subchannelLogger);
      oobChannelTracer.reportEvent(new ChannelTrace.Event.Builder()
              .setDescription("Child Subchannel created")
              .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
              .setTimestampNanos(oobChannelCreationTime)
              .setSubchannelRef(internalSubchannel)
              .build());
      channelz.addSubchannel(oobChannel);
      channelz.addSubchannel(internalSubchannel);
      oobChannel.setSubchannel(internalSubchannel);
      final class AddOobChannel implements Runnable {
        @Override
        public void run() {
          if (terminating) {
            oobChannel.shutdown();
          }
          if (!terminated) {
            // If channel has not terminated, it will track the subchannel and block termination
            // for it.
            oobChannels.add(oobChannel);
          }
        }
      }

      syncContext.execute(new AddOobChannel());
      return oobChannel;
    }

    @Override
    public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(String target) {
      final class ResolvingOobChannelBuilder
              extends AbstractManagedChannelImplBuilder<ResolvingOobChannelBuilder> {
        int defaultPort = -1;

        ResolvingOobChannelBuilder(String target) {
          super(target);
        }

        @Override
        public int getDefaultPort() {
          return defaultPort;
        }

        @Override
        protected ClientTransportFactory buildTransportFactory() {
          throw new UnsupportedOperationException();
        }

        @Override
        public ManagedChannel build() {
          // TODO(creamsoup) prevent main channel to shutdown if oob channel is not terminated
          return new ManagedChannelImpl(
                  this,
                  transportFactory,
                  backoffPolicyProvider,
                  balancerRpcExecutorPool,
                  stopwatchSupplier,
                  Collections.<ClientInterceptor>emptyList(),
                  timeProvider);
        }
      }

      checkState(!terminated, "Channel is terminated");

      ResolvingOobChannelBuilder builder = new ResolvingOobChannelBuilder(target);
      builder.offloadExecutorPool = offloadExecutorHolder.pool;
      builder.overrideAuthority(getAuthority());
      @SuppressWarnings("deprecation")
      ResolvingOobChannelBuilder unused = builder.nameResolverFactory(nameResolverFactory);
      builder.executorPool = executorPool;
      builder.maxTraceEvents = maxTraceEvents;
      builder.proxyDetector = nameResolverArgs.getProxyDetector();
      builder.defaultPort = nameResolverArgs.getDefaultPort();
      builder.userAgent = userAgent;
      return builder;
    }

    @Override
    public void updateOobChannelAddresses(ManagedChannel channel, EquivalentAddressGroup eag) {
      checkArgument(channel instanceof OobChannel,
              "channel must have been returned from createOobChannel");
      ((OobChannel) channel).updateAddresses(eag);
    }

    @Override
    public String getAuthority() {
      return ManagedChannelImpl.this.authority();
    }

    @Deprecated
    @Override
    public NameResolver.Factory getNameResolverFactory() {
      return nameResolverFactory;
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return scheduledExecutor;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return channelLogger;
    }

    @Override
    public NameResolver.Args getNameResolverArgs() {
      return nameResolverArgs;
    }

    @Override
    public NameResolverRegistry getNameResolverRegistry() {
      return nameResolverRegistry;
    }
  }

  private final class NameResolverListener extends NameResolver.Listener2 {
    final LbHelperImpl helper;
    final NameResolver resolver;

    /**
     * 构建服务发现监听器
     *
     * @param helperImpl 负载均衡提供器
     * @param resolver   服务发现
     * @return 服务发现监听器
     */
    NameResolverListener(LbHelperImpl helperImpl, NameResolver resolver) {
      this.helper = checkNotNull(helperImpl, "helperImpl");
      this.resolver = checkNotNull(resolver, "resolver");
    }

    /**
     * 更新负载均衡算法，处理未处理的请求
     * 处理更新事件，如果地址是空的，则会触发 onError 事件
     *
     * @param resolutionResult the resolved server addresses, attributes, and Service Config.
     *                         解析的地址，属性，服务配置
     */
    @Override
    public void onResult(final ResolutionResult resolutionResult) {
      final class NamesResolved implements Runnable {

        @SuppressWarnings("ReferenceEquality")
        @Override
        public void run() {

          List<EquivalentAddressGroup> servers = resolutionResult.getAddresses();
          channelLogger.log(ChannelLogLevel.DEBUG, "Resolved address: {0}, config={1}", servers, resolutionResult.getAttributes());

          if (lastResolutionState != ResolutionState.SUCCESS) {
            channelLogger.log(ChannelLogLevel.INFO, "Address resolved: {0}", servers);
            lastResolutionState = ResolutionState.SUCCESS;
          }

          nameResolverBackoffPolicy = null;
          // 获取配置
          ConfigOrError configOrError = resolutionResult.getServiceConfig();
          // 如果配置没有错误则使用这个配置
          ManagedChannelServiceConfig validServiceConfig = configOrError != null && configOrError.getConfig() != null ?
                  (ManagedChannelServiceConfig) resolutionResult.getServiceConfig().getConfig() :
                  null;
          Status serviceConfigError = configOrError != null ? configOrError.getError() : null;

          ManagedChannelServiceConfig effectiveServiceConfig;
          // 如果不查找配置，则使用默认配置
          if (!lookUpServiceConfig) {
            if (validServiceConfig != null) {
              channelLogger.log(ChannelLogLevel.INFO, "Service config from name resolver discarded by channel settings");
            }
            effectiveServiceConfig = defaultServiceConfig == null ? EMPTY_SERVICE_CONFIG : defaultServiceConfig;
          } else {
            // Try to use config if returned from name resolver
            // Otherwise, try to use the default config if available
            // 尝试使用服务发现返回的配置
            if (validServiceConfig != null) {
              effectiveServiceConfig = validServiceConfig;
            } else if (defaultServiceConfig != null) {
              effectiveServiceConfig = defaultServiceConfig;
              channelLogger.log(ChannelLogLevel.INFO, "Received no service config, using default service config");
            } else if (serviceConfigError != null) {
              // 如果不更新服务配置，则报错
              if (!serviceConfigUpdated) {
                // First DNS lookup has invalid service config, and cannot fall back to default
                channelLogger.log(ChannelLogLevel.INFO, "Fallback to error due to invalid first service config without default config");
                onError(configOrError.getError());
                return;
              } else {
                effectiveServiceConfig = lastServiceConfig;
              }
            } else {
              effectiveServiceConfig = EMPTY_SERVICE_CONFIG;
            }
            // 如果配置发生变化，则更新
            if (!effectiveServiceConfig.equals(lastServiceConfig)) {
              channelLogger.log(ChannelLogLevel.INFO, "Service config changed{0}", effectiveServiceConfig == EMPTY_SERVICE_CONFIG ? " to empty" : "");
              lastServiceConfig = effectiveServiceConfig;
            }

            try {
              // TODO(creamsoup): when `servers` is empty and lastResolutionStateCopy == SUCCESS
              //  and lbNeedAddress, it shouldn't call the handleServiceConfigUpdate. But,
              //  lbNeedAddress is not deterministic
              // 更新配置
              handleServiceConfigUpdate();
            } catch (RuntimeException re) {
              logger.log(Level.WARNING, "[" + getLogId() + "] Unexpected exception from parsing service config", re);
            }
          }

          // 获取属性
          Attributes effectiveAttrs = resolutionResult.getAttributes();
          // Call LB only if it's not shutdown.  If LB is shutdown, lbHelper won't match.
          // 如果服务发现没有关闭
          if (NameResolverListener.this.helper == ManagedChannelImpl.this.lbHelper) {
            // 获取健康检查
            Map<String, ?> healthCheckingConfig = effectiveServiceConfig.getHealthCheckingConfig();
            // 构建健康检查配置
            if (healthCheckingConfig != null) {
              effectiveAttrs = effectiveAttrs.toBuilder()
                                             .set(LoadBalancer.ATTR_HEALTH_CHECKING_CONFIG, healthCheckingConfig)
                                             .build();
            }

            // 更新负载均衡算法，处理未处理的请求
            Status handleResult = helper.lb.tryHandleResolvedAddresses(
                    ResolvedAddresses.newBuilder()
                                     .setAddresses(servers)
                                     .setAttributes(effectiveAttrs)
                                     .setLoadBalancingPolicyConfig(effectiveServiceConfig.getLoadBalancingConfig())
                                     .build());

            if (!handleResult.isOk()) {
              handleErrorInSyncContext(handleResult.augmentDescription(resolver + " was used"));
            }
          }
        }
      }

      // 执行处理
      syncContext.execute(new NamesResolved());
    }

    @Override
    public void onError(final Status error) {
      checkArgument(!error.isOk(), "the error status must not be OK");
      final class NameResolverErrorHandler implements Runnable {
        @Override
        public void run() {
          handleErrorInSyncContext(error);
        }
      }

      syncContext.execute(new NameResolverErrorHandler());
    }

    private void handleErrorInSyncContext(Status error) {
      logger.log(Level.WARNING, "[{0}] Failed to resolve name. status={1}",
          new Object[] {getLogId(), error});
      if (lastResolutionState != ResolutionState.ERROR) {
        channelLogger.log(ChannelLogLevel.WARNING, "Failed to resolve name: {0}", error);
        lastResolutionState = ResolutionState.ERROR;
      }
      // Call LB only if it's not shutdown.  If LB is shutdown, lbHelper won't match.
      if (NameResolverListener.this.helper != ManagedChannelImpl.this.lbHelper) {
        return;
      }

      helper.lb.handleNameResolutionError(error);

      scheduleExponentialBackOffInSyncContext();
    }

    private void scheduleExponentialBackOffInSyncContext() {
      if (scheduledNameResolverRefresh != null && scheduledNameResolverRefresh.isPending()) {
        // The name resolver may invoke onError multiple times, but we only want to
        // schedule one backoff attempt
        // TODO(ericgribkoff) Update contract of NameResolver.Listener or decide if we
        // want to reset the backoff interval upon repeated onError() calls
        return;
      }
      if (nameResolverBackoffPolicy == null) {
        nameResolverBackoffPolicy = backoffPolicyProvider.get();
      }
      long delayNanos = nameResolverBackoffPolicy.nextBackoffNanos();
      channelLogger.log(
          ChannelLogLevel.DEBUG,
          "Scheduling DNS resolution backoff for {0} ns", delayNanos);
      scheduledNameResolverRefresh =
          syncContext.schedule(
              new DelayedNameResolverRefresh(), delayNanos, TimeUnit.NANOSECONDS,
              transportFactory .getScheduledExecutorService());
    }
  }

  /**
   * Subchannel 的实现
   */
  private final class SubchannelImpl extends AbstractSubchannel {
    final CreateSubchannelArgs args;
    final LbHelperImpl helper;

    final InternalLogId subchannelLogId;
    final ChannelLoggerImpl subchannelLogger;
    final ChannelTracer subchannelTracer;

    SubchannelStateListener listener;
    InternalSubchannel subchannel;

    boolean started;
    boolean shutdown;

    ScheduledHandle delayedShutdownTask;

    /**
     * 创建 Subchannel
     */
    SubchannelImpl(CreateSubchannelArgs args, LbHelperImpl helper) {
      this.args = checkNotNull(args, "args");
      this.helper = checkNotNull(helper, "helper");
      subchannelLogId = InternalLogId.allocate("Subchannel", /*details=*/ authority());

      // 创建 Tracer 跟踪器
      subchannelTracer = new ChannelTracer(subchannelLogId,
              maxTraceEvents,
              timeProvider.currentTimeNanos(),
              "Subchannel for " + args.getAddresses());

      // 创建 Subchannel 日志实现
      subchannelLogger = new ChannelLoggerImpl(subchannelTracer, timeProvider);
    }

    // This can be called either in or outside of syncContext
    // TODO(zhangkun83): merge it back into start() once the caller createSubchannel() is deleted.
    private void internalStart(final SubchannelStateListener listener) {
      checkState(!started, "already started");
      checkState(!shutdown, "already shutdown");

      // 将 started 状态改为 true
      started = true;
      this.listener = listener;

      // TODO(zhangkun): possibly remove the volatile of terminating when this whole method is
      // required to be called from syncContext
      // 如果是在关闭中，则通知关闭
      if (terminating) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            listener.onSubchannelState(ConnectivityStateInfo.forNonError(SHUTDOWN));
          }
        });
        return;
      }

      // 操作回调
      final class ManagedInternalSubchannelCallback extends InternalSubchannel.Callback {
        // All callbacks are run in syncContext
        @Override
        void onTerminated(InternalSubchannel is) {
          // 终止时从集合中移除当前的 Subchannel
          subchannels.remove(is);
          channelz.removeSubchannel(is);
          // 执行终止操作
          maybeTerminateChannel();
        }

        /**
         * 更新 Subchannel 状态变化
         *
         * @param is       Subchannel
         * @param newState 新的状态
         */
        @Override
        void onStateChange(InternalSubchannel is, ConnectivityStateInfo newState) {
          // 如果状态是 TRANSIENT_FAILURE 或者 IDLE，则强制刷新 NameResolver
          handleInternalSubchannelState(newState);
          checkState(listener != null, "listener is null");
          // 通知监听器状态变化
          listener.onSubchannelState(newState);
        }

        @Override
        void onInUse(InternalSubchannel is) {
          inUseStateAggregator.updateObjectInUse(is, true);
        }

        @Override
        void onNotInUse(InternalSubchannel is) {
          inUseStateAggregator.updateObjectInUse(is, false);
        }
      }

      // 创建 InternalSubchannel
      final InternalSubchannel internalSubchannel = new InternalSubchannel(args.getAddresses(),
              authority(),
              userAgent,
              backoffPolicyProvider,
              transportFactory,
              transportFactory.getScheduledExecutorService(),
              stopwatchSupplier,
              syncContext,
              new ManagedInternalSubchannelCallback(),
              channelz,
              callTracerFactory.create(),
              subchannelTracer,
              subchannelLogId,
              subchannelLogger);

      // 记录创建事件
      channelTracer.reportEvent(new ChannelTrace.Event.Builder()
              .setDescription("Child Subchannel started")
              .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
              .setTimestampNanos(timeProvider.currentTimeNanos())
              .setSubchannelRef(internalSubchannel)
              .build());

      this.subchannel = internalSubchannel;
      // TODO(zhangkun83): no need to schedule on syncContext when this whole method is required
      // to be called from syncContext
      // 将 Subchannel 添加到集合中
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          channelz.addSubchannel(internalSubchannel);
          subchannels.add(internalSubchannel);
        }
      });
    }

    /**
     * 启动 Subchannel
     *
     * @param listener receives state updates for this Subchannel.
     *                 用于接收 Subchannel 状态更新的监听器
     */
    @Override
    public void start(SubchannelStateListener listener) {
      syncContext.throwIfNotInThisSynchronizationContext();
      internalStart(listener);
    }

    /**
     * 获取 InternalInstrumented 封装的 Subchannel
     */
    @Override
    InternalInstrumented<ChannelStats> getInstrumentedInternalSubchannel() {
      checkState(started, "not started");
      return subchannel;
    }

    @Override
    public void shutdown() {
      // TODO(zhangkun83): replace shutdown() with internalShutdown() to turn the warning into an
      // exception.
      logWarningIfNotInSyncContext("Subchannel.shutdown()");
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          internalShutdown();
        }
      });
    }

    /**
     * 关闭 Subchannel
     */
    private void internalShutdown() {
      syncContext.throwIfNotInThisSynchronizationContext();
      // 如果 Subchannel 是空的，则直接返回
      if (subchannel == null) {
        // start() was not successful
        shutdown = true;
        return;
      }

      // 如果状态是 shutdown，则取消延时 shutdown 任务
      if (shutdown) {
        if (terminating && delayedShutdownTask != null) {
          // shutdown() was previously called when terminating == false, thus a delayed shutdown()
          // was scheduled.  Now since terminating == true, We should expedite the shutdown.
          delayedShutdownTask.cancel();
          delayedShutdownTask = null;
          // Will fall through to the subchannel.shutdown() at the end.
        } else {
          return;
        }
      } else {
        shutdown = true;
      }
      // Add a delay to shutdown to deal with the race between 1) a transport being picked and
      // newStream() being called on it, and 2) its Subchannel is shut down by LoadBalancer (e.g.,
      // because of address change, or because LoadBalancer is shutdown by Channel entering idle
      // mode). If (2) wins, the app will see a spurious error. We work around this by delaying
      // shutdown of Subchannel for a few seconds here.
      //
      // TODO(zhangkun83): consider a better approach
      // (https://github.com/grpc/grpc-java/issues/2562).
      // 如果不是终止中，则创建延时任务终止
      if (!terminating) {
        final class ShutdownSubchannel implements Runnable {
          @Override
          public void run() {
            subchannel.shutdown(SUBCHANNEL_SHUTDOWN_STATUS);
          }
        }

        delayedShutdownTask = syncContext.schedule(new LogExceptionRunnable(new ShutdownSubchannel()),
                SUBCHANNEL_SHUTDOWN_DELAY_SECONDS,
                TimeUnit.SECONDS,
                transportFactory.getScheduledExecutorService());
        return;
      }
      // When terminating == true, no more real streams will be created. It's safe and also
      // desirable to shutdown timely.
      subchannel.shutdown(SHUTDOWN_STATUS);
    }

    /**
     * 如果没有连接，则要求 Subchannel 建立连接
     */
    @Override
    public void requestConnection() {
      logWarningIfNotInSyncContext("Subchannel.requestConnection()");
      checkState(started, "not started");
      // 选择 Transport，建立连接
      subchannel.obtainActiveTransport();
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      logWarningIfNotInSyncContext("Subchannel.getAllAddresses()");
      checkState(started, "not started");
      return subchannel.getAddressGroups();
    }

    @Override
    public Attributes getAttributes() {
      return args.getAttributes();
    }

    @Override
    public String toString() {
      return subchannelLogId.toString();
    }

    /**
     * 将 Subchannel 作为 Channel
     *
     * @return Channel
     */
    @Override
    public Channel asChannel() {
      checkState(started, "not started");
      return new SubchannelChannel(subchannel,
              balancerRpcExecutorHolder.getExecutor(),
              transportFactory.getScheduledExecutorService(),
              callTracerFactory.create());
    }

    @Override
    public Object getInternalSubchannel() {
      checkState(started, "Subchannel is not started");
      return subchannel;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return subchannelLogger;
    }

    /**
     * 更新 Subchannel 地址
     * @param addrs 地址集合
     */
    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      syncContext.throwIfNotInThisSynchronizationContext();
      subchannel.updateAddresses(addrs);
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("logId", logId.getId())
        .add("target", target)
        .toString();
  }

  /**
   * Called from syncContext.
   */
  private final class DelayedTransportListener implements ManagedClientTransport.Listener {
    @Override
    public void transportShutdown(Status s) {
      checkState(shutdown.get(), "Channel must have been shut down");
    }

    @Override
    public void transportReady() {
      // Don't care
    }

    @Override
    public void transportInUse(final boolean inUse) {
      inUseStateAggregator.updateObjectInUse(delayedTransport, inUse);
    }

    @Override
    public void transportTerminated() {
      checkState(shutdown.get(), "Channel must have been shut down");
      terminating = true;
      shutdownNameResolverAndLoadBalancer(false);
      // No need to call channelStateManager since we are already in SHUTDOWN state.
      // Until LoadBalancer is shutdown, it may still create new subchannels.  We catch them
      // here.
      maybeShutdownNowSubchannels();
      maybeTerminateChannel();
    }
  }

  /**
   * Must be accessed from syncContext.
   * IDLE 模式状态聚合器
   */
  private final class IdleModeStateAggregator extends InUseStateAggregator<Object> {
    @Override
    protected void handleInUse() {
      // 当处于 in-use 状态时，退出 IDLE 模式
      exitIdleMode();
    }

    @Override
    protected void handleNotInUse() {
      // 如果是 shutdown，则直接返回
      if (shutdown.get()) {
        return;
      }
      // 不是 shutdown，重新调度空闲计时器
      rescheduleIdleTimer();
    }
  }

  /**
   * Lazily request for Executor from an executor pool.
   */
  private static final class ExecutorHolder {
    private final ObjectPool<? extends Executor> pool;
    private Executor executor;

    ExecutorHolder(ObjectPool<? extends Executor> executorPool) {
      this.pool = checkNotNull(executorPool, "executorPool");
    }

    synchronized Executor getExecutor() {
      if (executor == null) {
        executor = checkNotNull(pool.getObject(), "%s.getObject()", executor);
      }
      return executor;
    }

    synchronized void release() {
      if (executor != null) {
        executor = pool.returnObject(executor);
      }
    }
  }

  private static final class RestrictedScheduledExecutor implements ScheduledExecutorService {
    final ScheduledExecutorService delegate;

    private RestrictedScheduledExecutor(ScheduledExecutorService delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      return delegate.schedule(callable, delay, unit);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable cmd, long delay, TimeUnit unit) {
      return delegate.schedule(cmd, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      return delegate.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      return delegate.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
      return delegate.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
      return delegate.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
      return delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
      return delegate.invokeAny(tasks, timeout, unit);
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public void shutdown() {
      throw new UnsupportedOperationException("Restricted: shutdown() is not allowed");
    }

    @Override
    public List<Runnable> shutdownNow() {
      throw new UnsupportedOperationException("Restricted: shutdownNow() is not allowed");
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      return delegate.submit(task);
    }

    @Override
    public Future<?> submit(Runnable task) {
      return delegate.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      return delegate.submit(task, result);
    }

    @Override
    public void execute(Runnable command) {
      delegate.execute(command);
    }
  }

  /**
   * 配置解析实现类
   */
  @VisibleForTesting
  static final class ScParser extends NameResolver.ServiceConfigParser {

    private final boolean retryEnabled;
    private final int maxRetryAttemptsLimit;
    private final int maxHedgedAttemptsLimit;
    private final AutoConfiguredLoadBalancerFactory autoLoadBalancerFactory;
    private final ChannelLogger channelLogger;

    ScParser(boolean retryEnabled,
             int maxRetryAttemptsLimit,
             int maxHedgedAttemptsLimit,
             AutoConfiguredLoadBalancerFactory autoLoadBalancerFactory,
             ChannelLogger channelLogger) {
      this.retryEnabled = retryEnabled;
      this.maxRetryAttemptsLimit = maxRetryAttemptsLimit;
      this.maxHedgedAttemptsLimit = maxHedgedAttemptsLimit;
      this.autoLoadBalancerFactory = checkNotNull(autoLoadBalancerFactory, "autoLoadBalancerFactory");
      this.channelLogger = checkNotNull(channelLogger, "channelLogger");
    }

    /**
     * 解析 Channel 配置
     *
     * @param rawServiceConfig The {@link Map} representation of the service config
     * @return
     */
    @Override
    public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
      try {
        Object loadBalancingPolicySelection;
        // 解析负载均衡策略
        ConfigOrError choiceFromLoadBalancer = autoLoadBalancerFactory.parseLoadBalancerPolicy(rawServiceConfig, channelLogger);

        // 根据获取到的结果设置负载均衡策略或者返回错误
        if (choiceFromLoadBalancer == null) {
          loadBalancingPolicySelection = null;
        } else if (choiceFromLoadBalancer.getError() != null) {
          return ConfigOrError.fromError(choiceFromLoadBalancer.getError());
        } else {
          loadBalancingPolicySelection = choiceFromLoadBalancer.getConfig();
        }

        // 构建 Channel 的配置
        return ConfigOrError.fromConfig(
                ManagedChannelServiceConfig.fromServiceConfig(
                        rawServiceConfig,
                        retryEnabled,
                        maxRetryAttemptsLimit,
                        maxHedgedAttemptsLimit,
                        loadBalancingPolicySelection)
        );
      } catch (RuntimeException e) {
        return ConfigOrError.fromError(Status.UNKNOWN.withDescription("failed to parse service config").withCause(e));
      }
    }
  }

  private void logWarningIfNotInSyncContext(String method) {
    try {
      syncContext.throwIfNotInThisSynchronizationContext();
    } catch (IllegalStateException e) {
      logger.log(Level.WARNING,
          method + " should be called from SynchronizationContext. "
          + "This warning will become an exception in a future release. "
          + "See https://github.com/grpc/grpc-java/issues/5015 for more details", e);
    }
  }

  /**
   * A ResolutionState indicates the status of last name resolution.
   */
  enum ResolutionState {
    NO_RESOLUTION,
    SUCCESS,
    ERROR
  }
}
