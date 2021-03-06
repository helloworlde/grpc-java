/*
 * Copyright 2015 The gRPC Authors
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

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A pluggable component that resolves a target {@link URI} and return addresses to the caller.
 * 解析目标 URI 并返回地址给调用者的可插拔组件
 *
 * <p>A {@code NameResolver} uses the URI's scheme to determine whether it can resolve it, and uses
 * the components after the scheme for actual resolution.
 * NameResolver 使用 URI 的协议决定是否可解析，并在之后实际解析
 *
 * <p>The addresses and attributes of a target may be changed over time, thus the caller registers a
 * {@link Listener} to receive continuous updates.
 * 目标的地址和属性可能会随时改变，因此调用者注册一个监听器持续接收更新
 *
 * <p>A {@code NameResolver} does not need to automatically re-resolve on failure. Instead, the
 * {@link Listener} is responsible for eventually (after an appropriate backoff period) invoking
 * {@link #refresh()}.
 * NameResolver 不需要在失败后自动重试，监听器会在一定延迟之后调用 refresh 方法
 *
 * <p>Implementations <strong>don't need to be thread-safe</strong>.  All methods are guaranteed to
 * be called sequentially.  Additionally, all methods that have side-effects, i.e.,
 * {@link #start(Listener2)}, {@link #shutdown} and {@link #refresh} are called from the same
 * {@link SynchronizationContext} as returned by {@link Helper#getSynchronizationContext}.
 * 实现类不需要保证线程安全，所有的调用会依序进行，此外，方法具有副作用，如 start,shutdown,refresh
 * 都是由 Helper#getSynchronizationContext 返回的 SynchronizationContext 调用
 *
 * @since 1.0.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
public abstract class NameResolver {
    /**
     * Returns the authority used to authenticate connections to servers.  It <strong>must</strong> be
     * from a trusted source, because if the authority is tampered with, RPCs may be sent to the
     * attackers which may leak sensitive user data.
     * 返回用于验证与服务器用于连接的权限，必须来源于可靠的源，因为如果被篡改，RPC 可能会被发送给攻击者，泄露敏感的用户
     * 数据
     *
     * <p>An implementation must generate it without blocking, typically in line, and
     * <strong>must</strong> keep it unchanged. {@code NameResolver}s created from the same factory
     * with the same argument must return the same authority.
     * 实现必须是非阻塞的，通常按行生成，并且保持不变，同样的 NameResolver 使用同样的参数用同样的工厂创建，必须
     * 返回同样的 authority
     *
     * @since 1.0.0
     */
    public abstract String getServiceAuthority();

    /**
     * 开始服务解析
     * Starts the resolution.
     *
     * @param listener used to receive updates on the target
     * @since 1.0.0
     */
    public void start(final Listener listener) {
        // 如果是 Listener2 则开始解析，如果不是则使用 Listener2 解析
        if (listener instanceof Listener2) {
            start((Listener2) listener);
        } else {
            start(new Listener2() {
                @Override
                public void onError(Status error) {
                    listener.onError(error);
                }

                @Override
                public void onResult(ResolutionResult resolutionResult) {
                    listener.onAddresses(resolutionResult.getAddresses(), resolutionResult.getAttributes());
                }
            });
        }
    }

    /**
     * 开始服务解析
     * Starts the resolution.
     *
     * @param listener used to receive updates on the target
     * @since 1.21.0
     */
    public void start(Listener2 listener) {
        start((Listener) listener);
    }

    /**
     * Stops the resolution. Updates to the Listener will stop.
     * 关闭命名解析，更新监听器状态为停止
     *
     * @since 1.0.0
     */
    public abstract void shutdown();

    /**
     * Re-resolve the name.
     * 重新解析服务名称
     *
     * <p>Can only be called after {@link #start} has been called.
     * 只有在调用 start 之后才可以调用
     *
     * <p>This is only a hint. Implementation takes it as a signal but may not start resolution
     * immediately. It should never throw.
     * 实现可以将其作为信号，但是可以不用立即开始解析，不应该抛出异常
     *
     * <p>The default implementation is no-op.
     *
     * @since 1.0.0
     */
    public void refresh() {
    }

    /**
     * Factory that creates {@link NameResolver} instances.
     * 工厂用于创建 NameResolver 实例
     *
     * @since 1.0.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    public abstract static class Factory {
        /**
         * The port number used in case the target or the underlying naming system doesn't provide a
         * port number.
         * 端口用于在没有提供端口号时使用
         *
         * @since 1.0.0
         * @deprecated this will be deleted along with {@link #newNameResolver(URI, Attributes)} in
         * a future release.
         */
        @Deprecated
        public static final Attributes.Key<Integer> PARAMS_DEFAULT_PORT =
                Attributes.Key.create("params-default-port");

        /**
         * If the NameResolver wants to support proxy, it should inquire this {@link ProxyDetector}.
         * See documentation on {@link ProxyDetector} about how proxies work in gRPC.
         * 如果 NameResolver 想支持代理，应该查询 ProxyDetector
         *
         * @deprecated this will be deleted along with {@link #newNameResolver(URI, Attributes)} in
         * a future release
         */
        @ExperimentalApi("https://github.com/grpc/grpc-java/issues/5113")
        @Deprecated
        public static final Attributes.Key<ProxyDetector> PARAMS_PROXY_DETECTOR =
                Attributes.Key.create("params-proxy-detector");

        @Deprecated
        private static final Attributes.Key<SynchronizationContext> PARAMS_SYNC_CONTEXT =
                Attributes.Key.create("params-sync-context");

        @Deprecated
        private static final Attributes.Key<ServiceConfigParser> PARAMS_PARSER =
                Attributes.Key.create("params-parser");

        /**
         * Creates a {@link NameResolver} for the given target URI, or {@code null} if the given URI
         * cannot be resolved by this factory. The decision should be solely based on the scheme of the
         * URI.
         * 根据所给的目标 URI 创建一个 NameResolver，如果不能解析，则返回 null，该决定仅基于 URI 方案
         *
         * @param targetUri the target URI to be resolved, whose scheme must not be {@code null}
         *                  要解析的 URI，scheme 不能为 null
         * @param params    optional parameters. Canonical keys are defined as {@code PARAMS_*} fields in
         *                  {@link Factory}.
         *                  可选参数，key 在 Factory 里定义
         * @since 1.0.0
         * @deprecated Implement {@link #newNameResolver(URI, NameResolver.Helper)} instead.  This is
         * going to be deleted in a future release.
         * 使用 newNameResolver 代替
         */
        @Nullable
        @Deprecated
        public NameResolver newNameResolver(URI targetUri, final Attributes params) {
            Args args = Args.newBuilder()
                            .setDefaultPort(params.get(PARAMS_DEFAULT_PORT))
                            .setProxyDetector(params.get(PARAMS_PROXY_DETECTOR))
                            .setSynchronizationContext(params.get(PARAMS_SYNC_CONTEXT))
                            .setServiceConfigParser(params.get(PARAMS_PARSER))
                            .build();
            return newNameResolver(targetUri, args);
        }

        /**
         * Creates a {@link NameResolver} for the given target URI, or {@code null} if the given URI
         * cannot be resolved by this factory. The decision should be solely based on the scheme of the
         * URI.
         * 根据所给的 URI 创建一个 NameResolver，如果工厂不能解析，则返回null，该决定仅基于 URI 方案
         *
         * @param targetUri the target URI to be resolved, whose scheme must not be {@code null}
         *                  要解析的 URI，schema 不能为 null
         * @param helper    utility that may be used by the NameResolver implementation
         *                  NameResolver 实现可能用到的工具类
         * @since 1.19.0
         * @deprecated implement {@link #newNameResolver(URI, NameResolver.Args)} instead
         */
        @Deprecated
        @Nullable
        public NameResolver newNameResolver(URI targetUri, final Helper helper) {
            return newNameResolver(
                    targetUri,
                    Attributes.newBuilder()
                              .set(PARAMS_DEFAULT_PORT, helper.getDefaultPort())
                              .set(PARAMS_PROXY_DETECTOR, helper.getProxyDetector())
                              .set(PARAMS_SYNC_CONTEXT, helper.getSynchronizationContext())
                              .set(PARAMS_PARSER, new ServiceConfigParser() {
                                  @Override
                                  public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
                                      return helper.parseServiceConfig(rawServiceConfig);
                                  }
                              })
                              .build());
        }

        /**
         * Creates a {@link NameResolver} for the given target URI, or {@code null} if the given URI
         * cannot be resolved by this factory. The decision should be solely based on the scheme of the
         * URI.
         * 根据给定的 URI 创建 NameResolver，如果 URI 不能解析则返回 null，该决定仅基于 URI 方案
         *
         * @param targetUri the target URI to be resolved, whose scheme must not be {@code null}
         *                  需要解析的 URI，schema 不能为 null
         * @param args      other information that may be useful
         *                  其他有用的信息
         * @since 1.21.0
         */
        @SuppressWarnings("deprecation")
        // TODO(zhangkun83): make it abstract method after all other overrides have been deleted
        public NameResolver newNameResolver(URI targetUri, final Args args) {
            return newNameResolver(targetUri, new Helper() {
                /**
                 * 获取 port
                 *
                 */
                @Override
                public int getDefaultPort() {
                    return args.getDefaultPort();
                }

                /**
                 * 代理检测
                 *
                 */
                @Override
                public ProxyDetector getProxyDetector() {
                    return args.getProxyDetector();
                }

                /**
                 * 用于调用 start，shutdown，cancel 的 SynchronizationContext
                 *
                 */
                @Override
                public SynchronizationContext getSynchronizationContext() {
                    return args.getSynchronizationContext();
                }

                /**
                 * 解析校验配置
                 *
                 * @param rawServiceConfig The {@link Map} representation of the service config
                 *                         代表服务配置的 Map
                 */
                @Override
                public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
                    return args.getServiceConfigParser().parseServiceConfig(rawServiceConfig);
                }
            });
        }

        /**
         * Returns the default scheme, which will be used to construct a URI when {@link
         * ManagedChannelBuilder#forTarget(String)} is given an authority string instead of a compliant
         * URI.
         * 返回默认的 Schema，当 ManagedChannelBuilder#forTarget(String) 返回一个字符串而不是完整的URI 时使用
         *
         * @since 1.0.0
         */
        public abstract String getDefaultScheme();
    }

    /**
     * Receives address updates.
     * 监听地址更新
     *
     * <p>All methods are expected to return quickly.
     * 所有的方法都期望快速返回
     *
     * @since 1.0.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    @ThreadSafe
    public interface Listener {
        /**
         * Handles updates on resolved addresses and attributes.
         * 用给定的地址和属性更新地址
         *
         * <p>Implementations will not modify the given {@code servers}.
         * 实现不能修改给定的 servers 对象
         *
         * @param servers    the resolved server addresses. An empty list will trigger {@link #onError}
         *                   获取的 server 地址，空的集合将会触发 onError
         * @param attributes extra information from naming system.
         *                   额外的信息
         * @since 1.3.0
         */
        void onAddresses(List<EquivalentAddressGroup> servers, @ResolutionResultAttr Attributes attributes);

        /**
         * Handles an error from the resolver. The listener is responsible for eventually invoking
         * {@link #refresh()} to re-attempt resolution.
         * 处理服务解析时的错误，最终会调用 refresh 再次尝试解析
         *
         * @param error a non-OK status
         * @since 1.0.0
         */
        void onError(Status error);
    }

    /**
     * Receives address updates.
     * 监听地址更新
     *
     * <p>All methods are expected to return quickly.
     * 所有的方法都期望快速返回
     *
     * <p>This is a replacement API of {@code Listener}. However, we think this new API may change
     * again, so we aren't yet encouraging mass-migration to it. It is fine to use and works.
     * 用于替换 Listener 的 API，然而这个新的 API 可能会发生变化，不建议大规模迁移，可以正常使用和工作
     *
     * @since 1.21.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    public abstract static class Listener2 implements Listener {
        /**
         * @deprecated This will be removed in 1.22.0
         */
        @Override
        @Deprecated
        public final void onAddresses(
                List<EquivalentAddressGroup> servers, @ResolutionResultAttr Attributes attributes) {
            // TODO(jihuncho) need to promote Listener2 if we want to use ConfigOrError
            onResult(
                    ResolutionResult.newBuilder().setAddresses(servers).setAttributes(attributes).build());
        }

        /**
         * Handles updates on resolved addresses and attributes.  If
         * {@link ResolutionResult#getAddresses()} is empty, {@link #onError(Status)} will be called.
         * <p>
         * 处理更新事件，如果地址是空的，则会触发 onError 事件
         *
         * @param resolutionResult the resolved server addresses, attributes, and Service Config.
         *                         解析的地址，属性，服务配置
         * @since 1.21.0
         */
        public abstract void onResult(ResolutionResult resolutionResult);

        /**
         * Handles a name resolving error from the resolver. The listener is responsible for eventually
         * invoking {@link NameResolver#refresh()} to re-attempt resolution.
         * 处理解析错误，最终会调用 NameResolver#refresh 方法再次解析
         *
         * @param error a non-OK status
         * @since 1.21.0
         */
        @Override
        public abstract void onError(Status error);
    }

    /**
     * Annotation for name resolution result attributes. It follows the annotation semantics defined
     * by {@link Attributes}.
     * 标注服务名称解析属性，遵循定义的注释语义
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4972")
    @Retention(RetentionPolicy.SOURCE)
    @Documented
    public @interface ResolutionResultAttr {
    }

    /**
     * A utility object passed to {@link Factory#newNameResolver(URI, NameResolver.Helper)}.
     * 传递给 Factory#newNameResolver 方法的工具对象
     *
     * @since 1.19.0
     * @deprecated use {@link Args} instead.
     */
    @Deprecated
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    public abstract static class Helper {
        /**
         * The port number used in case the target or the underlying naming system doesn't provide a
         * port number.
         * 如果没有提供端口，则使用默认的端口
         *
         * @since 1.19.0
         */
        public abstract int getDefaultPort();

        /**
         * If the NameResolver wants to support proxy, it should inquire this {@link ProxyDetector}.
         * See documentation on {@link ProxyDetector} about how proxies work in gRPC.
         * 代理检测
         *
         * @since 1.19.0
         */
        public abstract ProxyDetector getProxyDetector();

        /**
         * Returns the {@link SynchronizationContext} where {@link #start(Listener2)}, {@link #shutdown}
         * and {@link #refresh} are run from.
         * 返回用于执行 start,shutdown 和 refresh 的 SynchronizationContext
         *
         * @since 1.20.0
         */
        public SynchronizationContext getSynchronizationContext() {
            throw new UnsupportedOperationException("Not implemented");
        }

        /**
         * Parses and validates the service configuration chosen by the name resolver.  This will
         * return a {@link ConfigOrError} which contains either the successfully parsed config, or the
         * {@link Status} representing the failure to parse.  Implementations are expected to not throw
         * exceptions but return a Status representing the failure.  The value inside the
         * {@link ConfigOrError} should implement {@code equals()} and {@code hashCode()}.
         * 传递验证 NameResolver 选择的服务配置，无论成功失败都会返回一个 ConfigOrError 对象，实现不应该抛出异常，
         * 而是返回代表失败的状态码， ConfigOrError 应该实现 equals 和 hashcode 方法
         *
         * @param rawServiceConfig The {@link Map} representation of the service config
         *                         代表服务配置的 Map
         * @return a tuple of the fully parsed and validated channel configuration, else the Status.
         * 一组解析并检验过的 Channel 的配置，或者状态
         * @since 1.20.0
         */
        public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
            throw new UnsupportedOperationException("should have been implemented");
        }
    }

    /**
     * Information that a {@link Factory} uses to create a {@link NameResolver}.
     * 用于 Factory 创建 NameResolver 的信息
     * <p>Note this class doesn't override neither {@code equals()} nor {@code hashCode()}.
     * 这个方法没有重写 equals 和 hashCode 方法
     *
     * @since 1.21.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    public static final class Args {
        private final int defaultPort;
        private final ProxyDetector proxyDetector;
        private final SynchronizationContext syncContext;
        private final ServiceConfigParser serviceConfigParser;
        @Nullable
        private final ScheduledExecutorService scheduledExecutorService;
        @Nullable
        private final ChannelLogger channelLogger;
        @Nullable
        private final Executor executor;

        private Args(Integer defaultPort,
                     ProxyDetector proxyDetector,
                     SynchronizationContext syncContext,
                     ServiceConfigParser serviceConfigParser,
                     @Nullable ScheduledExecutorService scheduledExecutorService,
                     @Nullable ChannelLogger channelLogger,
                     @Nullable Executor executor) {
            this.defaultPort = checkNotNull(defaultPort, "defaultPort not set");
            this.proxyDetector = checkNotNull(proxyDetector, "proxyDetector not set");
            this.syncContext = checkNotNull(syncContext, "syncContext not set");
            this.serviceConfigParser = checkNotNull(serviceConfigParser, "serviceConfigParser not set");
            this.scheduledExecutorService = scheduledExecutorService;
            this.channelLogger = channelLogger;
            this.executor = executor;
        }

        /**
         * The port number used in case the target or the underlying naming system doesn't provide a
         * port number.
         * 当地址中没有端口时使用默认的端口
         *
         * @since 1.21.0
         */
        public int getDefaultPort() {
            return defaultPort;
        }

        /**
         * If the NameResolver wants to support proxy, it should inquire this {@link ProxyDetector}.
         * See documentation on {@link ProxyDetector} about how proxies work in gRPC.
         * 如果 NameResolver 想支持代理，应该询问这个 ProxyDetector
         *
         * @since 1.21.0
         */
        public ProxyDetector getProxyDetector() {
            return proxyDetector;
        }

        /**
         * Returns the {@link SynchronizationContext} where {@link #start(Listener2)}, {@link #shutdown}
         * and {@link #refresh} are run from.
         * 返回用于调用 start，shutdown，refresh 的 SynchronizationContext
         *
         * @since 1.21.0
         */
        public SynchronizationContext getSynchronizationContext() {
            return syncContext;
        }

        /**
         * Returns a {@link ScheduledExecutorService} for scheduling delayed tasks.
         * 返回用于执行延时任务的 ScheduledExecutorService
         *
         * <p>This service is a shared resource and is only meant for quick tasks. DO NOT block or run
         * time-consuming tasks.
         * 这个服务是共享的资源，仅用于快速的任务，不能用于阻塞或者长时间执行的任务
         *
         * <p>The returned service doesn't support {@link ScheduledExecutorService#shutdown shutdown()}
         * and {@link ScheduledExecutorService#shutdownNow shutdownNow()}. They will throw if called.
         * 返回的服务不支持 ScheduledExecutorService#shutdown 方法和 ScheduledExecutorService#shutdownNow
         * 方法，如果被调用将会抛出异常
         *
         * @since 1.26.0
         */
        @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6454")
        public ScheduledExecutorService getScheduledExecutorService() {
            if (scheduledExecutorService == null) {
                throw new IllegalStateException("ScheduledExecutorService not set in Builder");
            }
            return scheduledExecutorService;
        }

        /**
         * Returns the {@link ServiceConfigParser}.
         * 返回配置解析器
         *
         * @since 1.21.0
         */
        public ServiceConfigParser getServiceConfigParser() {
            return serviceConfigParser;
        }

        /**
         * Returns the {@link ChannelLogger} for the Channel served by this NameResolver.
         * 返回用于 Channel 的 ChannelLogger
         *
         * @since 1.26.0
         */
        @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6438")
        public ChannelLogger getChannelLogger() {
            if (channelLogger == null) {
                throw new IllegalStateException("ChannelLogger is not set in Builder");
            }
            return channelLogger;
        }

        /**
         * Returns the Executor on which this resolver should execute long-running or I/O bound work.
         * Null if no Executor was set.
         * 返回用于长时间执行解析任务的执行器，如果没有则返回 null
         *
         * @since 1.25.0
         */
        @Nullable
        @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6279")
        public Executor getOffloadExecutor() {
            return executor;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("defaultPort", defaultPort)
                              .add("proxyDetector", proxyDetector)
                              .add("syncContext", syncContext)
                              .add("serviceConfigParser", serviceConfigParser)
                              .add("scheduledExecutorService", scheduledExecutorService)
                              .add("channelLogger", channelLogger)
                              .add("executor", executor)
                              .toString();
        }

        /**
         * Returns a builder with the same initial values as this object.
         * 返回有初始值的对象 Builder
         *
         * @since 1.21.0
         */
        public Builder toBuilder() {
            Builder builder = new Builder();
            builder.setDefaultPort(defaultPort);
            builder.setProxyDetector(proxyDetector);
            builder.setSynchronizationContext(syncContext);
            builder.setServiceConfigParser(serviceConfigParser);
            builder.setScheduledExecutorService(scheduledExecutorService);
            builder.setChannelLogger(channelLogger);
            builder.setOffloadExecutor(executor);
            return builder;
        }

        /**
         * Creates a new builder.
         * 创建新的 Builder
         *
         * @since 1.21.0
         */
        public static Builder newBuilder() {
            return new Builder();
        }

        /**
         * Builder for {@link Args}.
         * Args 的 Builder
         *
         * @since 1.21.0
         */
        public static final class Builder {
            private Integer defaultPort;
            private ProxyDetector proxyDetector;
            private SynchronizationContext syncContext;
            private ServiceConfigParser serviceConfigParser;
            private ScheduledExecutorService scheduledExecutorService;
            private ChannelLogger channelLogger;
            private Executor executor;

            Builder() {
            }

            /**
             * See {@link Args#getDefaultPort}.  This is a required field.
             * 设置默认的端口
             *
             * @since 1.21.0
             */
            public Builder setDefaultPort(int defaultPort) {
                this.defaultPort = defaultPort;
                return this;
            }

            /**
             * See {@link Args#getProxyDetector}.  This is required field.
             * 设置代理检测
             *
             * @since 1.21.0
             */
            public Builder setProxyDetector(ProxyDetector proxyDetector) {
                this.proxyDetector = checkNotNull(proxyDetector);
                return this;
            }

            /**
             * See {@link Args#getSynchronizationContext}.  This is a required field.
             * 设置 SynchronizationContext
             *
             * @since 1.21.0
             */
            public Builder setSynchronizationContext(SynchronizationContext syncContext) {
                this.syncContext = checkNotNull(syncContext);
                return this;
            }

            /**
             * See {@link Args#getScheduledExecutorService}.
             * 设置线程池
             */
            @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6454")
            public Builder setScheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
                this.scheduledExecutorService = checkNotNull(scheduledExecutorService);
                return this;
            }

            /**
             * See {@link Args#getServiceConfigParser}.  This is a required field.
             * 设置配置解析器
             *
             * @since 1.21.0
             */
            public Builder setServiceConfigParser(ServiceConfigParser parser) {
                this.serviceConfigParser = checkNotNull(parser);
                return this;
            }

            /**
             * See {@link Args#getChannelLogger}.
             * 设置 Channel 日志
             *
             * @since 1.26.0
             */
            @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6438")
            public Builder setChannelLogger(ChannelLogger channelLogger) {
                this.channelLogger = checkNotNull(channelLogger);
                return this;
            }

            /**
             * See {@link Args#getOffloadExecutor}. This is an optional field.
             * 设置长时间任务执行器
             *
             * @since 1.25.0
             */
            @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6279")
            public Builder setOffloadExecutor(Executor executor) {
                this.executor = executor;
                return this;
            }

            /**
             * Builds an {@link Args}.
             * 构建 Args 对象
             *
             * @since 1.21.0
             */
            public Args build() {
                return new Args(defaultPort,
                        proxyDetector,
                        syncContext,
                        serviceConfigParser,
                        scheduledExecutorService,
                        channelLogger,
                        executor);
            }
        }
    }

    /**
     * Parses and validates service configuration.
     * 解析并校验服务配置
     *
     * @since 1.21.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    public abstract static class ServiceConfigParser {
        /**
         * Parses and validates the service configuration chosen by the name resolver.  This will
         * return a {@link ConfigOrError} which contains either the successfully parsed config, or the
         * {@link Status} representing the failure to parse.  Implementations are expected to not throw
         * exceptions but return a Status representing the failure.  The value inside the
         * {@link ConfigOrError} should implement {@code equals()} and {@code hashCode()}.
         * <p>
         * 解析校验从服务发现获取到的方法配置，会返回一个 ConfigOrError 对象，要求实现不能抛出异常，失败时可以返回一个状态，
         * ConfigOrError 对象的 value 字段应当实现 equals 和 hashcode 方法
         *
         * @param rawServiceConfig The {@link Map} representation of the service config
         * @return a tuple of the fully parsed and validated channel configuration, else the Status.
         * @since 1.21.0
         */
        public abstract ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig);
    }

    /**
     * Represents the results from a Name Resolver.
     * 代表名称解析的结果
     *
     * @since 1.21.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    public static final class ResolutionResult {

        /**
         * 解析的地址集合
         */
        private final List<EquivalentAddressGroup> addresses;

        /**
         * 解析的额外属性
         */
        @ResolutionResultAttr
        private final Attributes attributes;

        /**
         * 配置
         */
        @Nullable
        private final ConfigOrError serviceConfig;

        ResolutionResult(List<EquivalentAddressGroup> addresses,
                         @ResolutionResultAttr Attributes attributes,
                         ConfigOrError serviceConfig) {
            this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
            this.attributes = checkNotNull(attributes, "attributes");
            this.serviceConfig = serviceConfig;
        }

        /**
         * Constructs a new builder of a name resolution result.
         * 构建一个新的服务解析的结果
         *
         * @since 1.21.0
         */
        public static Builder newBuilder() {
            return new Builder();
        }

        /**
         * Converts these results back to a builder.
         * 将对象转为 Builder
         *
         * @since 1.21.0
         */
        public Builder toBuilder() {
            return newBuilder()
                    .setAddresses(addresses)
                    .setAttributes(attributes)
                    .setServiceConfig(serviceConfig);
        }

        /**
         * Gets the addresses resolved by name resolution.
         * 返回服务解析的地址
         *
         * @since 1.21.0
         */
        public List<EquivalentAddressGroup> getAddresses() {
            return addresses;
        }

        /**
         * Gets the attributes associated with the addresses resolved by name resolution.  If there are
         * no attributes, {@link Attributes#EMPTY} will be returned.
         * 返回命名解析获取到的地址的属性信息，如果没有则返回 Attributes#EMPTY
         *
         * @since 1.21.0
         */
        @ResolutionResultAttr
        public Attributes getAttributes() {
            return attributes;
        }

        /**
         * Gets the Service Config parsed by {@link NameResolver.Helper#parseServiceConfig(Map)}.
         * 获取 NameResolver.Helper#parseServiceConfig(Map) 解析的配置
         *
         * @since 1.21.0
         */
        @Nullable
        public ConfigOrError getServiceConfig() {
            return serviceConfig;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("addresses", addresses)
                              .add("attributes", attributes)
                              .add("serviceConfig", serviceConfig)
                              .toString();
        }

        /**
         * Useful for testing.  May be slow to calculate.
         */
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ResolutionResult)) {
                return false;
            }
            ResolutionResult that = (ResolutionResult) obj;
            return Objects.equal(this.addresses, that.addresses)
                    && Objects.equal(this.attributes, that.attributes)
                    && Objects.equal(this.serviceConfig, that.serviceConfig);
        }

        /**
         * Useful for testing.  May be slow to calculate.
         */
        @Override
        public int hashCode() {
            return Objects.hashCode(addresses, attributes, serviceConfig);
        }

        /**
         * A builder for {@link ResolutionResult}.
         * ResolutionResult 的 Builder
         *
         * @since 1.21.0
         */
        @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
        public static final class Builder {

            /**
             * 解析的地址
             */
            private List<EquivalentAddressGroup> addresses = Collections.emptyList();

            /**
             * 属性
             */
            private Attributes attributes = Attributes.EMPTY;

            /**
             * 配置
             */
            @Nullable
            private ConfigOrError serviceConfig;
            //  Make sure to update #toBuilder above!

            Builder() {
            }

            /**
             * 设置解析到的地址
             * Sets the addresses resolved by name resolution.  This field is required.
             *
             * @since 1.21.0
             */
            public Builder setAddresses(List<EquivalentAddressGroup> addresses) {
                this.addresses = addresses;
                return this;
            }

            /**
             * Sets the attributes for the addresses resolved by name resolution.  If unset,
             * {@link Attributes#EMPTY} will be used as a default.
             * 设置解析的地址属性，如果不设置会使用 Attributes#EMPTY 作为默认的
             *
             * @since 1.21.0
             */
            public Builder setAttributes(Attributes attributes) {
                this.attributes = attributes;
                return this;
            }

            /**
             * Sets the Service Config parsed by {@link NameResolver.Helper#parseServiceConfig(Map)}.
             * This field is optional.
             * 设置  NameResolver.Helper#parseServiceConfig(Map) 解析的服务配置，属性是可选的
             *
             * @since 1.21.0
             */
            public Builder setServiceConfig(@Nullable ConfigOrError serviceConfig) {
                this.serviceConfig = serviceConfig;
                return this;
            }

            /**
             * Constructs a new {@link ResolutionResult} from this builder.
             * 通过当前 Builder 构造 ResolutionResult
             *
             * @since 1.21.0
             */
            public ResolutionResult build() {
                return new ResolutionResult(addresses, attributes, serviceConfig);
            }
        }
    }

    /**
     * Gets the attributes associated with the addresses resolved by name resolution.  If there are
     * no attributes, {@link Attributes#EMPTY} will be returned.
     * 获取与服务解析管理的地址的属性，如果没有则使用 Attributes#EMPTY
     *
     * @since 1.21.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
    public static final class ConfigOrError {

        /**
         * Returns a {@link ConfigOrError} for the successfully parsed config.
         * 返回解析成功的配置对象
         */
        public static ConfigOrError fromConfig(Object config) {
            return new ConfigOrError(config);
        }

        /**
         * Returns a {@link ConfigOrError} for the failure to parse the config.
         * 如果解析配置失败返回 ConfigOrError
         *
         * @param status a non-OK status 非 OK 的状态码
         */
        public static ConfigOrError fromError(Status status) {
            return new ConfigOrError(status);
        }

        private final Status status;
        private final Object config;

        private ConfigOrError(Object config) {
            this.config = checkNotNull(config, "config");
            this.status = null;
        }

        private ConfigOrError(Status status) {
            this.config = null;
            this.status = checkNotNull(status, "status");
            checkArgument(!status.isOk(), "cannot use OK status: %s", status);
        }

        /**
         * Returns config if exists, otherwise null.
         * 如果配置存在，则返回配置，不存在则返回 null
         */
        @Nullable
        public Object getConfig() {
            return config;
        }

        /**
         * Returns error status if exists, otherwise null.
         * 如果错误状态存在则返回错误状态，不存在则返回 null
         */
        @Nullable
        public Status getError() {
            return status;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ConfigOrError that = (ConfigOrError) o;
            return Objects.equal(status, that.status) && Objects.equal(config, that.config);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(status, config);
        }

        @Override
        public String toString() {
            if (config != null) {
                return MoreObjects.toStringHelper(this)
                                  .add("config", config)
                                  .toString();
            } else {
                assert status != null;
                return MoreObjects.toStringHelper(this)
                                  .add("error", status)
                                  .toString();
            }
        }
    }
}
