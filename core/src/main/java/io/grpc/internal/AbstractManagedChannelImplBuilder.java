/*
 * Copyright 2014 The gRPC Authors
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
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Attributes;
import io.grpc.BinaryLog;
import io.grpc.ClientInterceptor;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalChannelz;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.NameResolverRegistry;
import io.grpc.ProxyDetector;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * The base class for channel builders.
 * Channel 构建器的基类
 *
 * @param <T> The concrete type of this builder.
 *            构建器的正确类型
 */
public abstract class AbstractManagedChannelImplBuilder<T extends AbstractManagedChannelImplBuilder<T>>
        extends ManagedChannelBuilder<T> {

    private static final String DIRECT_ADDRESS_SCHEME = "directaddress";

    private static final Logger log = Logger.getLogger(AbstractManagedChannelImplBuilder.class.getName());

    public static ManagedChannelBuilder<?> forAddress(String name, int port) {
        throw new UnsupportedOperationException("Subclass failed to hide static factory");
    }

    public static ManagedChannelBuilder<?> forTarget(String target) {
        throw new UnsupportedOperationException("Subclass failed to hide static factory");
    }

    /**
     * An idle timeout larger than this would disable idle mode.
     * 空闲超时时间，超过这个值将禁用空闲模式
     */
    @VisibleForTesting
    static final long IDLE_MODE_MAX_TIMEOUT_DAYS = 30;

    /**
     * The default idle timeout.
     * 默认的空闲超时时间
     */
    @VisibleForTesting
    static final long IDLE_MODE_DEFAULT_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /**
     * An idle timeout smaller than this would be capped to it.
     * 最小的空闲超时时间，小于这个时间将被覆盖
     */
    static final long IDLE_MODE_MIN_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(1);

    // 默认的线程池
    private static final ObjectPool<? extends Executor> DEFAULT_EXECUTOR_POOL =
            SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);

    // 默认的解压缩器注册器
    private static final DecompressorRegistry DEFAULT_DECOMPRESSOR_REGISTRY =
            DecompressorRegistry.getDefaultInstance();

    // 默认的压缩器注册器
    private static final CompressorRegistry DEFAULT_COMPRESSOR_REGISTRY =
            CompressorRegistry.getDefaultInstance();

    // 默认的重试缓冲区大小
    private static final long DEFAULT_RETRY_BUFFER_SIZE_IN_BYTES = 1L << 24;  // 16M
    // 默认的单个请求缓冲区大小
    private static final long DEFAULT_PER_RPC_BUFFER_LIMIT_IN_BYTES = 1L << 20; // 1M

    // 执行的线程池
    ObjectPool<? extends Executor> executorPool = DEFAULT_EXECUTOR_POOL;

    // 耗时请求的线程池
    ObjectPool<? extends Executor> offloadExecutorPool = DEFAULT_EXECUTOR_POOL;

    // 拦截器
    private final List<ClientInterceptor> interceptors = new ArrayList<>();

    // 命名解析注册器，使用默认的从 SPI 加载的 NameResolver 实现类
    final NameResolverRegistry nameResolverRegistry = NameResolverRegistry.getDefaultRegistry();

    // Access via getter, which may perform authority override as needed
    // 获取命名解析工厂，该工厂使用优先级最高的实现类作为命名解析实现
    private NameResolver.Factory nameResolverFactory = nameResolverRegistry.asFactory();

    // 服务名称
    final String target;

    // 直连的服务端地址
    @Nullable
    private final SocketAddress directServerAddress;

    // UserAgent
    @Nullable
    String userAgent;

    // 覆盖服务名称
    @VisibleForTesting
    @Nullable
    String authorityOverride;

    // 默认的负载均衡策略
    String defaultLbPolicy = GrpcUtil.DEFAULT_LB_POLICY;

    // 对整个流开启压缩
    boolean fullStreamDecompression;

    // 解压缩器注册器
    DecompressorRegistry decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;

    // 压缩器注册器
    CompressorRegistry compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;

    // 空闲超时时间
    long idleTimeoutMillis = IDLE_MODE_DEFAULT_TIMEOUT_MILLIS;

    // 最大重试次数
    int maxRetryAttempts = 5;
    // 最大对冲次数
    int maxHedgedAttempts = 5;
    // 重试缓冲区大小
    long retryBufferSize = DEFAULT_RETRY_BUFFER_SIZE_IN_BYTES;
    // 单个请求的缓冲区大小限制
    long perRpcBufferLimit = DEFAULT_PER_RPC_BUFFER_LIMIT_IN_BYTES;
    // 是否开启重试
    boolean retryEnabled = false; // TODO(zdapeng): default to true
    // Temporarily disable retry when stats or tracing is enabled to avoid breakage, until we know
    // what should be the desired behavior for retry + stats/tracing.
    // TODO(zdapeng): delete me
    boolean temporarilyDisableRetry;

    // 用于调试的 Channelz
    InternalChannelz channelz = InternalChannelz.instance();
    // 最大的 Channel 事件追踪数量
    int maxTraceEvents;

    // 默认的服务配置
    @Nullable
    Map<String, ?> defaultServiceConfig;
    // 是否开启服务配置查找
    boolean lookUpServiceConfig = true;

    // 用于跟踪的 TransportTracer 的工厂
    protected TransportTracer.Factory transportTracerFactory = TransportTracer.getDefaultFactory();

    // 最大的入站消息大小
    private int maxInboundMessageSize = GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;

    // 用于记录 Channel 二进制日志的对象
    @Nullable
    BinaryLog binlog;

    // 代理探测器
    @Nullable
    ProxyDetector proxyDetector;

    /**
     * Sets the maximum message size allowed for a single gRPC frame. If an inbound messages
     * larger than this limit is received it will not be processed and the RPC will fail with
     * RESOURCE_EXHAUSTED.
     * 设置允许的最大的单个 gRPC 帧的大小，如果接收到的入站消息比限制还大，则不会处理，会以 RESOURCE_EXHAUSTED 失败
     */
    // Can be overridden by subclasses.
    @Override
    public T maxInboundMessageSize(int max) {
        checkArgument(max >= 0, "negative max");
        maxInboundMessageSize = max;
        return thisT();
    }

    protected final int maxInboundMessageSize() {
        return maxInboundMessageSize;
    }

    // 开启统计
    private boolean statsEnabled = true;
    // 统计开始的请求
    private boolean recordStartedRpcs = true;
    // 统计已经完成的请求
    private boolean recordFinishedRpcs = true;
    // 统计请求的真正耗时
    private boolean recordRealTimeMetrics = false;
    // 开启请求追踪
    private boolean tracingEnabled = true;

    /**
     * 根据服务名称构建
     */
    protected AbstractManagedChannelImplBuilder(String target) {
        this.target = Preconditions.checkNotNull(target, "target");
        this.directServerAddress = null;
    }

    /**
     * Returns a target string for the SocketAddress. It is only used as a placeholder, because
     * DirectAddressNameResolverFactory will not actually try to use it. However, it must be a valid
     * URI.
     * 返回 SocketAddress 的目标地址，仅用于提示，因为 DirectAddressNameResolverFactory 不会使用，但是必须是有效的 URI
     */
    @VisibleForTesting
    static String makeTargetStringForDirectAddress(SocketAddress address) {
        try {
            return new URI(DIRECT_ADDRESS_SCHEME, "", "/" + address, null).toString();
        } catch (URISyntaxException e) {
            // It should not happen.
            throw new RuntimeException(e);
        }
    }

    /**
     * 使用直接地址构建
     */
    protected AbstractManagedChannelImplBuilder(SocketAddress directServerAddress, String authority) {
        this.target = makeTargetStringForDirectAddress(directServerAddress);
        this.directServerAddress = directServerAddress;
        // 通过直接地址构建命名解析
        this.nameResolverFactory = new DirectAddressNameResolverFactory(directServerAddress, authority);
    }

    /**
     * 使用直接的线程池
     */
    @Override
    public final T directExecutor() {
        return executor(MoreExecutors.directExecutor());
    }

    /**
     * 指定线程池
     */
    @Override
    public final T executor(Executor executor) {
        if (executor != null) {
            this.executorPool = new FixedObjectPool<>(executor);
        } else {
            this.executorPool = DEFAULT_EXECUTOR_POOL;
        }
        return thisT();
    }

    /**
     * 指定长耗时的请求的线程池
     */
    @Override
    public final T offloadExecutor(Executor executor) {
        if (executor != null) {
            this.offloadExecutorPool = new FixedObjectPool<>(executor);
        } else {
            this.offloadExecutorPool = DEFAULT_EXECUTOR_POOL;
        }
        return thisT();
    }

    /**
     * 指定拦截器
     */
    @Override
    public final T intercept(List<ClientInterceptor> interceptors) {
        this.interceptors.addAll(interceptors);
        return thisT();
    }

    /**
     * 指定拦截器
     */
    @Override
    public final T intercept(ClientInterceptor... interceptors) {
        return intercept(Arrays.asList(interceptors));
    }

    /**
     * 设置命名解析工厂
     */
    @Deprecated
    @Override
    public final T nameResolverFactory(NameResolver.Factory resolverFactory) {
        Preconditions.checkState(directServerAddress == null, "directServerAddress is set (%s), which forbids the use of NameResolverFactory", directServerAddress);
        // 如果 Factory 不为空，则使用传入的 Factory，否则使用默认的 Factory
        if (resolverFactory != null) {
            this.nameResolverFactory = resolverFactory;
        } else {
            this.nameResolverFactory = nameResolverRegistry.asFactory();
        }
        return thisT();
    }

    /**
     * 设置默认的负载均衡策略
     */
    @Override
    public final T defaultLoadBalancingPolicy(String policy) {
        Preconditions.checkState(directServerAddress == null, "directServerAddress is set (%s), which forbids the use of load-balancing policy", directServerAddress);
        Preconditions.checkArgument(policy != null, "policy cannot be null");
        this.defaultLbPolicy = policy;
        return thisT();
    }

    /**
     * 开启整个流的解压缩
     */
    @Override
    public final T enableFullStreamDecompression() {
        this.fullStreamDecompression = true;
        return thisT();
    }


    /**
     * 解压缩流注册器
     */
    @Override
    public final T decompressorRegistry(DecompressorRegistry registry) {
        if (registry != null) {
            this.decompressorRegistry = registry;
        } else {
            this.decompressorRegistry = DEFAULT_DECOMPRESSOR_REGISTRY;
        }
        return thisT();
    }

    /**
     * 压缩流注册器
     */
    @Override
    public final T compressorRegistry(CompressorRegistry registry) {
        if (registry != null) {
            this.compressorRegistry = registry;
        } else {
            this.compressorRegistry = DEFAULT_COMPRESSOR_REGISTRY;
        }
        return thisT();
    }

    @Override
    public final T userAgent(@Nullable String userAgent) {
        this.userAgent = userAgent;
        return thisT();
    }

    @Override
    public final T overrideAuthority(String authority) {
        this.authorityOverride = checkAuthority(authority);
        return thisT();
    }

    /**
     * 空闲超时时间
     */
    @Override
    public final T idleTimeout(long value, TimeUnit unit) {
        checkArgument(value > 0, "idle timeout is %s, but must be positive", value);
        // We convert to the largest unit to avoid overflow
        // 如果大于最大的超时时间，则设置为禁止超时
        if (unit.toDays(value) >= IDLE_MODE_MAX_TIMEOUT_DAYS) {
            // This disables idle mode
            this.idleTimeoutMillis = ManagedChannelImpl.IDLE_TIMEOUT_MILLIS_DISABLE;
        } else {
            this.idleTimeoutMillis = Math.max(unit.toMillis(value), IDLE_MODE_MIN_TIMEOUT_MILLIS);
        }
        return thisT();
    }

    /**
     * 最大重试次数
     */
    @Override
    public final T maxRetryAttempts(int maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
        return thisT();
    }

    /**
     * 最大对冲次数
     */
    @Override
    public final T maxHedgedAttempts(int maxHedgedAttempts) {
        this.maxHedgedAttempts = maxHedgedAttempts;
        return thisT();
    }

    /**
     * 重试缓冲区大小
     */
    @Override
    public final T retryBufferSize(long bytes) {
        checkArgument(bytes > 0L, "retry buffer size must be positive");
        retryBufferSize = bytes;
        return thisT();
    }

    /**
     * 单个请求缓冲区大小
     */
    @Override
    public final T perRpcBufferLimit(long bytes) {
        checkArgument(bytes > 0L, "per RPC buffer limit must be positive");
        perRpcBufferLimit = bytes;
        return thisT();
    }

    /**
     * 禁止重试
     */
    @Override
    public final T disableRetry() {
        retryEnabled = false;
        return thisT();
    }

    /**
     * 开启重试
     */
    @Override
    public final T enableRetry() {
        retryEnabled = true;
        statsEnabled = false;
        tracingEnabled = false;
        return thisT();
    }

    @Override
    public final T setBinaryLog(BinaryLog binlog) {
        this.binlog = binlog;
        return thisT();
    }

    @Override
    public T maxTraceEvents(int maxTraceEvents) {
        checkArgument(maxTraceEvents >= 0, "maxTraceEvents must be non-negative");
        this.maxTraceEvents = maxTraceEvents;
        return thisT();
    }

    @Override
    public T proxyDetector(@Nullable ProxyDetector proxyDetector) {
        this.proxyDetector = proxyDetector;
        return thisT();
    }

    /**
     * 从 builder 中指定服务配置
     *
     * @param serviceConfig
     * @return
     */
    @Override
    public T defaultServiceConfig(@Nullable Map<String, ?> serviceConfig) {
        // TODO(notcarl): use real parsing
        defaultServiceConfig = checkMapEntryTypes(serviceConfig);
        return thisT();
    }

    /**
     * 校验配置属性
     *
     * @param map
     * @return
     */
    @Nullable
    private static Map<String, ?> checkMapEntryTypes(@Nullable Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        // Not using ImmutableMap.Builder because of extra guava dependency for Android.
        Map<String, Object> parsedMap = new LinkedHashMap<>();
        // 遍历 map
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            // 检查 key
            checkArgument(entry.getKey() instanceof String, "The key of the entry '%s' is not of String type", entry);

            String key = (String) entry.getKey();
            Object value = entry.getValue();

            // 根据 value 类型检查并设置值
            if (value == null) {
                parsedMap.put(key, null);
            } else if (value instanceof Map) {
                parsedMap.put(key, checkMapEntryTypes((Map<?, ?>) value));
            } else if (value instanceof List) {
                parsedMap.put(key, checkListEntryTypes((List<?>) value));
            } else if (value instanceof String) {
                parsedMap.put(key, value);
            } else if (value instanceof Double) {
                parsedMap.put(key, value);
            } else if (value instanceof Boolean) {
                parsedMap.put(key, value);
            } else {
                throw new IllegalArgumentException("The value of the map entry '" + entry + "' is of type '" + value.getClass() + "', which is not supported");
            }
        }
        return Collections.unmodifiableMap(parsedMap);
    }

    /**
     * 检查 List 对象
     *
     * @param list
     * @return
     */
    private static List<?> checkListEntryTypes(List<?> list) {
        List<Object> parsedList = new ArrayList<>(list.size());
        for (Object value : list) {
            if (value == null) {
                parsedList.add(null);
            } else if (value instanceof Map) {
                parsedList.add(checkMapEntryTypes((Map<?, ?>) value));
            } else if (value instanceof List) {
                parsedList.add(checkListEntryTypes((List<?>) value));
            } else if (value instanceof String) {
                parsedList.add(value);
            } else if (value instanceof Double) {
                parsedList.add(value);
            } else if (value instanceof Boolean) {
                parsedList.add(value);
            } else {
                throw new IllegalArgumentException("The entry '" + value + "' is of type '" + value.getClass() + "', which is not supported");
            }
        }
        return Collections.unmodifiableList(parsedList);
    }

    /**
     * 禁止查找服务配置
     */
    @Override
    public T disableServiceConfigLookUp() {
        this.lookUpServiceConfig = false;
        return thisT();
    }

    /**
     * Disable or enable stats features. Enabled by default.
     * 禁用或者启动统计，默认是启用的
     *
     * <p>For the current release, calling {@code setStatsEnabled(true)} may have a side effect that
     * disables retry.
     */
    protected void setStatsEnabled(boolean value) {
        statsEnabled = value;
    }

    /**
     * Disable or enable stats recording for RPC upstarts.  Effective only if {@link
     * #setStatsEnabled} is set to true.  Enabled by default.
     * 开启或者关闭记录每个请求的开始，默认开启
     */
    protected void setStatsRecordStartedRpcs(boolean value) {
        recordStartedRpcs = value;
    }

    /**
     * Disable or enable stats recording for RPC completions.  Effective only if {@link
     * #setStatsEnabled} is set to true.  Enabled by default.
     * 开启或者关闭记录每个请求的完成，默认是开启的
     */
    protected void setStatsRecordFinishedRpcs(boolean value) {
        recordFinishedRpcs = value;
    }

    /**
     * Disable or enable real-time metrics recording.  Effective only if {@link #setStatsEnabled} is
     * set to true.  Disabled by default.
     * 开启或关闭记录请求的实时统计，默认是关闭的
     */
    protected void setStatsRecordRealTimeMetrics(boolean value) {
        recordRealTimeMetrics = value;
    }

    /**
     * Disable or enable tracing features.  Enabled by default.
     * 开启或关闭追踪，默认开启
     *
     * <p>For the current release, calling {@code setTracingEnabled(true)} may have a side effect that
     * disables retry.
     */
    protected void setTracingEnabled(boolean value) {
        tracingEnabled = value;
    }

    @VisibleForTesting
    final long getIdleTimeoutMillis() {
        return idleTimeoutMillis;
    }

    /**
     * Verifies the authority is valid.  This method exists as an escape hatch for putting in an
     * authority that is valid, but would fail the default validation provided by this
     * implementation.
     * 检查服务名称是否有效
     */
    protected String checkAuthority(String authority) {
        return GrpcUtil.checkAuthority(authority);
    }

    /**
     * 构建 ManagedChannel 对象
     *
     * @return
     */
    @Override
    public ManagedChannel build() {
        return new ManagedChannelOrphanWrapper(new ManagedChannelImpl(
                this,
                // 构建 Transport 工厂
                buildTransportFactory(),
                new ExponentialBackoffPolicy.Provider(),
                // 线程池
                SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR),
                // 计时器
                GrpcUtil.STOPWATCH_SUPPLIER,
                // 统计和追踪拦截器
                getEffectiveInterceptors(),
                // 时间提供器
                TimeProvider.SYSTEM_TIME_PROVIDER));
    }

    // Temporarily disable retry when stats or tracing is enabled to avoid breakage, until we know
    // what should be the desired behavior for retry + stats/tracing.
    // 获取拦截器
    // 当统计和追踪开启时会禁止重试，避免泄露
    // TODO(zdapeng): FIX IT
    @VisibleForTesting
    final List<ClientInterceptor> getEffectiveInterceptors() {
        List<ClientInterceptor> effectiveInterceptors = new ArrayList<>(this.interceptors);
        temporarilyDisableRetry = false;
        // 如果 statsEnabled 是 true，则不允许重试，当调用 enableRetry 方法后，statsEnabled 是false
        // 统计拦截器
        if (statsEnabled) {
            // 如果开启了统计，则禁止重试
            temporarilyDisableRetry = true;
            ClientInterceptor statsInterceptor = null;
            try {
                Class<?> censusStatsAccessor = Class.forName("io.grpc.census.InternalCensusStatsAccessor");
                Method getClientInterceptorMethod = censusStatsAccessor.getDeclaredMethod("getClientInterceptor",
                        boolean.class,
                        boolean.class,
                        boolean.class);
                statsInterceptor = (ClientInterceptor) getClientInterceptorMethod.invoke(null,
                        recordStartedRpcs,
                        recordFinishedRpcs,
                        recordRealTimeMetrics);
            } catch (ClassNotFoundException e) {
                // Replace these separate catch statements with multicatch when Android min-API >= 19
                log.log(Level.FINE, "Unable to apply census stats", e);
            } catch (NoSuchMethodException e) {
                log.log(Level.FINE, "Unable to apply census stats", e);
            } catch (IllegalAccessException e) {
                log.log(Level.FINE, "Unable to apply census stats", e);
            } catch (InvocationTargetException e) {
                log.log(Level.FINE, "Unable to apply census stats", e);
            }

            if (statsInterceptor != null) {
                // First interceptor runs last (see ClientInterceptors.intercept()), so that no
                // other interceptor can override the tracer factory we set in CallOptions.
                effectiveInterceptors.add(0, statsInterceptor);
            }
        }

        // 如果开启了追踪，则禁止重试，当调用 enableRetry 后，tracingEnabled 是 false
        if (tracingEnabled) {
            temporarilyDisableRetry = true;
            ClientInterceptor tracingInterceptor = null;
            try {
                Class<?> censusTracingAccessor = Class.forName("io.grpc.census.InternalCensusTracingAccessor");
                Method getClientInterceptroMethod = censusTracingAccessor.getDeclaredMethod("getClientInterceptor");
                tracingInterceptor = (ClientInterceptor) getClientInterceptroMethod.invoke(null);
            } catch (ClassNotFoundException e) {
                // Replace these separate catch statements with multicatch when Android min-API >= 19
                log.log(Level.FINE, "Unable to apply census stats", e);
            } catch (NoSuchMethodException e) {
                log.log(Level.FINE, "Unable to apply census stats", e);
            } catch (IllegalAccessException e) {
                log.log(Level.FINE, "Unable to apply census stats", e);
            } catch (InvocationTargetException e) {
                log.log(Level.FINE, "Unable to apply census stats", e);
            }
            if (tracingInterceptor != null) {
                effectiveInterceptors.add(0, tracingInterceptor);
            }
        }
        return effectiveInterceptors;
    }

    /**
     * Subclasses should override this method to provide the {@link ClientTransportFactory}
     * appropriate for this channel. This method is meant for Transport implementors and should not
     * be used by normal users.
     * 构建 Transport 工厂，由子类实现
     */
    protected abstract ClientTransportFactory buildTransportFactory();

    /**
     * Subclasses can override this method to provide a default port to {@link NameResolver} for use
     * in cases where the target string doesn't include a port.  The default implementation returns
     * {@link GrpcUtil#DEFAULT_PORT_SSL}.
     * 提供默认的端口，由子类实现，默认返回 443 端口
     */
    protected int getDefaultPort() {
        return GrpcUtil.DEFAULT_PORT_SSL;
    }

    /**
     * Returns a {@link NameResolver.Factory} for the channel.
     * 返回命名解析工厂
     */
    NameResolver.Factory getNameResolverFactory() {
        // 如果没有覆盖服务名称，则使用这个 nameResolverFactory，否则使用 OverrideAuthorityNameResolverFactory
        if (authorityOverride == null) {
            return nameResolverFactory;
        } else {
            return new OverrideAuthorityNameResolverFactory(nameResolverFactory, authorityOverride);
        }
    }

    /**
     * 直接地址命名解析工厂
     */
    private static class DirectAddressNameResolverFactory extends NameResolver.Factory {
        final SocketAddress address;
        final String authority;

        DirectAddressNameResolverFactory(SocketAddress address, String authority) {
            this.address = address;
            this.authority = authority;
        }

        @Override
        public NameResolver newNameResolver(URI notUsedUri, NameResolver.Args args) {
            return new NameResolver() {
                @Override
                public String getServiceAuthority() {
                    return authority;
                }

                @Override
                public void start(Listener2 listener) {
                    listener.onResult(ResolutionResult.newBuilder()
                                                      .setAddresses(Collections.singletonList(new EquivalentAddressGroup(address)))
                                                      .setAttributes(Attributes.EMPTY)
                                                      .build());
                }

                @Override
                public void shutdown() {
                }
            };
        }

        @Override
        public String getDefaultScheme() {
            return DIRECT_ADDRESS_SCHEME;
        }
    }

    /**
     * Returns the correctly typed version of the builder.
     * 返回 Builder 对象
     */
    private T thisT() {
        @SuppressWarnings("unchecked")
        T thisT = (T) this;
        return thisT;
    }

    /**
     * Returns the internal offload executor pool for offloading tasks.
     * 返回用于执行长时间任务的线程池
     */
    protected ObjectPool<? extends Executor> getOffloadExecutorPool() {
        return this.offloadExecutorPool;
    }
}
