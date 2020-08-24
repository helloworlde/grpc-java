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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.base.Verify;
import com.google.common.base.VerifyException;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.ProxiedSocketAddress;
import io.grpc.ProxyDetector;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.SharedResourceHolder.Resource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A DNS-based {@link NameResolver}.
 * 基于 DNS 的 NameResolver
 *
 * <p>Each {@code A} or {@code AAAA} record emits an {@link EquivalentAddressGroup} in the list
 * passed to {@link NameResolver.Listener2#onResult(ResolutionResult)}.
 * 每一个在集合中的 EquivalentAddressGroup A 或者 AAAA 记录都被发送给 NameResolver.Listener2#onResult(ResolutionResult)
 *
 * @see DnsNameResolverProvider
 */
public class DnsNameResolver extends NameResolver {

    private static final Logger logger = Logger.getLogger(DnsNameResolver.class.getName());

    private static final String SERVICE_CONFIG_CHOICE_CLIENT_LANGUAGE_KEY = "clientLanguage";
    private static final String SERVICE_CONFIG_CHOICE_PERCENTAGE_KEY = "percentage";
    private static final String SERVICE_CONFIG_CHOICE_CLIENT_HOSTNAME_KEY = "clientHostname";
    private static final String SERVICE_CONFIG_CHOICE_SERVICE_CONFIG_KEY = "serviceConfig";

    // From https://github.com/grpc/proposal/blob/master/A2-service-configs-in-dns.md
    static final String SERVICE_CONFIG_PREFIX = "grpc_config=";
    private static final Set<String> SERVICE_CONFIG_CHOICE_KEYS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(SERVICE_CONFIG_CHOICE_CLIENT_LANGUAGE_KEY,
                    SERVICE_CONFIG_CHOICE_PERCENTAGE_KEY,
                    SERVICE_CONFIG_CHOICE_CLIENT_HOSTNAME_KEY,
                    SERVICE_CONFIG_CHOICE_SERVICE_CONFIG_KEY)));

    // From https://github.com/grpc/proposal/blob/master/A2-service-configs-in-dns.md
    private static final String SERVICE_CONFIG_NAME_PREFIX = "_grpc_config.";

    private static final String JNDI_PROPERTY =
            System.getProperty("io.grpc.internal.DnsNameResolverProvider.enable_jndi", "true");
    private static final String JNDI_LOCALHOST_PROPERTY =
            System.getProperty("io.grpc.internal.DnsNameResolverProvider.enable_jndi_localhost", "false");
    private static final String JNDI_TXT_PROPERTY =
            System.getProperty("io.grpc.internal.DnsNameResolverProvider.enable_service_config", "false");

    /**
     * Java networking system properties name for caching DNS result.
     * Java 的 DNS 缓存时间属性
     *
     * <p>Default value is -1 (cache forever) if security manager is installed. If security manager is
     * not installed, the ttl value is {@code null} which falls back to {@link
     * #DEFAULT_NETWORK_CACHE_TTL_SECONDS gRPC default value}.
     * 如果安装了 Security manager，则默认的值是 -1，如果没有安装，ttl 的值是 null，会使用
     * DEFAULT_NETWORK_CACHE_TTL_SECONDS 作为默认值
     *
     * <p>For android, gRPC doesn't attempt to cache; this property value will be ignored.
     * 在安卓上，gRPC 不会缓存，这个值会被忽略
     */
    @VisibleForTesting
    static final String NETWORKADDRESS_CACHE_TTL_PROPERTY = "networkaddress.cache.ttl";
    /**
     * Default DNS cache duration if network cache ttl value is not specified ({@code null}).
     * 如果网络的 ttl 时间没有设置则使用默认的 DNS 缓存时间
     */
    @VisibleForTesting
    static final long DEFAULT_NETWORK_CACHE_TTL_SECONDS = 30;

    @VisibleForTesting
    static boolean enableJndi = Boolean.parseBoolean(JNDI_PROPERTY);

    @VisibleForTesting
    static boolean enableJndiLocalhost = Boolean.parseBoolean(JNDI_LOCALHOST_PROPERTY);

    @VisibleForTesting
    protected static boolean enableTxt = Boolean.parseBoolean(JNDI_TXT_PROPERTY);

    // 初始化资源加载工厂类实例
    private static final ResourceResolverFactory resourceResolverFactory =
            getResourceResolverFactory(DnsNameResolver.class.getClassLoader());

    @VisibleForTesting
    final ProxyDetector proxyDetector;

    /**
     * Access through {@link #getLocalHostname}.
     */
    private static String localHostname;

    private final Random random = new Random();

    protected volatile AddressResolver addressResolver = JdkAddressResolver.INSTANCE;

    private final AtomicReference<ResourceResolver> resourceResolver = new AtomicReference<>();

    private final String authority;
    private final String host;
    private final int port;

    /**
     * Executor that will be used if an Executor is not provide via {@link NameResolver.Args}.
     * 如果 NameResolver.Args 里面没有提供 Executor 则使用这个
     */
    private final Resource<Executor> executorResource;
    private final long cacheTtlNanos;
    private final SynchronizationContext syncContext;

    // Following fields must be accessed from syncContext
    private final Stopwatch stopwatch;
    protected boolean resolved;
    private boolean shutdown;
    private Executor executor;

    /**
     * True if using an executor resource that should be released after use.
     * 如果 executor 使用之后应当被释放则返回 true
     */
    private final boolean usingExecutorResource;
    private final ServiceConfigParser serviceConfigParser;

    private boolean resolving;

    // The field must be accessed from syncContext, although the methods on an Listener2 can be called
    // from any thread.
    private NameResolver.Listener2 listener;

    /**
     * 构造 DNS 解析器实例
     *
     * @param nsAuthority      地址
     * @param name             URI 名称
     * @param args             参数
     * @param executorResource 线程池
     * @param stopwatch        记录时间
     * @param isAndroid        是否是安卓
     */
    protected DnsNameResolver(@Nullable String nsAuthority,
                              String name,
                              Args args,
                              Resource<Executor> executorResource,
                              Stopwatch stopwatch,
                              boolean isAndroid) {

        checkNotNull(args, "args");
        // TODO: if a DNS server is provided as nsAuthority, use it.
        // https://www.captechconsulting.com/blogs/accessing-the-dusty-corners-of-dns-with-java
        this.executorResource = executorResource;
        // Must prepend a "//" to the name when constructing a URI, otherwise it will be treated as an
        // opaque URI, thus the authority and host of the resulted URI would be null.
        URI nameUri = URI.create("//" + checkNotNull(name, "name"));
        Preconditions.checkArgument(nameUri.getHost() != null, "Invalid DNS name: %s", name);
        authority = Preconditions.checkNotNull(nameUri.getAuthority(), "nameUri (%s) doesn't have an authority", nameUri);
        host = nameUri.getHost();
        if (nameUri.getPort() == -1) {
            port = args.getDefaultPort();
        } else {
            port = nameUri.getPort();
        }

        this.proxyDetector = checkNotNull(args.getProxyDetector(), "proxyDetector");
        this.cacheTtlNanos = getNetworkAddressCacheTtlNanos(isAndroid);
        this.stopwatch = checkNotNull(stopwatch, "stopwatch");
        this.syncContext = checkNotNull(args.getSynchronizationContext(), "syncContext");
        this.executor = args.getOffloadExecutor();
        this.usingExecutorResource = executor == null;
        this.serviceConfigParser = checkNotNull(args.getServiceConfigParser(), "serviceConfigParser");
    }

    @Override
    public String getServiceAuthority() {
        return authority;
    }

    @VisibleForTesting
    protected String getHost() {
        return host;
    }

    /**
     * 开始解析
     *
     * @param listener used to receive updates on the target
     */
    @Override
    public void start(Listener2 listener) {
        Preconditions.checkState(this.listener == null, "already started");
        if (usingExecutorResource) {
            executor = SharedResourceHolder.get(executorResource);
        }
        this.listener = checkNotNull(listener, "listener");
        // 解析
        resolve();
    }

    /**
     * 重新解析地址
     */
    @Override
    public void refresh() {
        Preconditions.checkState(listener != null, "not started");
        resolve();
    }

    /**
     * 根据 Host 解析地址
     *
     * @return 解析到的地址集合
     */
    private List<EquivalentAddressGroup> resolveAddresses() {
        List<? extends InetAddress> addresses;
        Exception addressesException = null;
        try {
            // 根据 Host 解析地址
            addresses = addressResolver.resolveAddress(host);
        } catch (Exception e) {
            addressesException = e;
            Throwables.throwIfUnchecked(e);
            throw new RuntimeException(e);
        } finally {
            if (addressesException != null) {
                logger.log(Level.FINE, "Address resolution failure", addressesException);
            }
        }

        // 将地址和端口转换为地址集合
        // Each address forms an EAG
        List<EquivalentAddressGroup> servers = new ArrayList<>(addresses.size());
        for (InetAddress inetAddr : addresses) {
            servers.add(new EquivalentAddressGroup(new InetSocketAddress(inetAddr, port)));
        }
        return Collections.unmodifiableList(servers);
    }

    /**
     * 解析配置
     *
     * @return 解析到的配置
     */
    @Nullable
    private ConfigOrError resolveServiceConfig() {
        List<String> txtRecords = Collections.emptyList();
        ResourceResolver resourceResolver = getResourceResolver();

        if (resourceResolver != null) {
            try {
                // 根据前缀查找记录
                txtRecords = resourceResolver.resolveTxt(SERVICE_CONFIG_NAME_PREFIX + host);
            } catch (Exception e) {
                logger.log(Level.FINE, "ServiceConfig resolution failure", e);
            }
        }

        // 如果记录不为空，则解析配置
        if (!txtRecords.isEmpty()) {
            // 解析配置
            ConfigOrError rawServiceConfig = parseServiceConfig(txtRecords, random, getLocalHostname());

            if (rawServiceConfig != null) {
                if (rawServiceConfig.getError() != null) {
                    return ConfigOrError.fromError(rawServiceConfig.getError());
                }

                @SuppressWarnings("unchecked")
                Map<String, ?> verifiedRawServiceConfig = (Map<String, ?>) rawServiceConfig.getConfig();
                // 将 map 配置解析为对象并返回
                return serviceConfigParser.parseServiceConfig(verifiedRawServiceConfig);
            }
        } else {
            logger.log(Level.FINE, "No TXT records found for {0}", new Object[]{host});
        }
        return null;
    }

    /**
     * 检测是否有代理，如果有代理，则返回代理后的地址
     *
     * @return 代理后的地址
     */
    @Nullable
    private EquivalentAddressGroup detectProxy() throws IOException {
        InetSocketAddress destination = InetSocketAddress.createUnresolved(host, port);
        ProxiedSocketAddress proxiedAddr = proxyDetector.proxyFor(destination);
        if (proxiedAddr != null) {
            return new EquivalentAddressGroup(proxiedAddr);
        }
        return null;
    }

    /**
     * 解析地址
     * Main logic of name resolution.
     */
    protected InternalResolutionResult doResolve(boolean forceTxt) {
        InternalResolutionResult result = new InternalResolutionResult();
        try {
            result.addresses = resolveAddresses();
        } catch (Exception e) {
            if (!forceTxt) {
                result.error = Status.UNAVAILABLE.withDescription("Unable to resolve host " + host).withCause(e);
                return result;
            }
        }
        // 如果开启了 txt 解析，则解析配置
        if (enableTxt) {
            result.config = resolveServiceConfig();
        }
        return result;
    }

    /**
     * 解析地址
     */
    private final class Resolve implements Runnable {
        private final Listener2 savedListener;

        Resolve(Listener2 savedListener) {
            this.savedListener = checkNotNull(savedListener, "savedListener");
        }

        /**
         * 从 DNS 解析服务
         */
        @Override
        public void run() {
            if (logger.isLoggable(Level.FINER)) {
                logger.finer("Attempting DNS resolution of " + host);
            }
            InternalResolutionResult result = null;
            try {
                EquivalentAddressGroup proxiedAddr = detectProxy();
                ResolutionResult.Builder resolutionResultBuilder = ResolutionResult.newBuilder();
                // 代理的地址
                if (proxiedAddr != null) {
                    if (logger.isLoggable(Level.FINER)) {
                        logger.finer("Using proxy address " + proxiedAddr);
                    }
                    resolutionResultBuilder.setAddresses(Collections.singletonList(proxiedAddr));
                } else {
                    // 根据 HOST 解析地址和配置
                    result = doResolve(false);

                    if (result.error != null) {
                        savedListener.onError(result.error);
                        return;
                    }
                    // 如果有地址，则设置地址
                    if (result.addresses != null) {
                        resolutionResultBuilder.setAddresses(result.addresses);
                    }
                    if (result.config != null) {
                        resolutionResultBuilder.setServiceConfig(result.config);
                    }
                    if (result.attributes != null) {
                        resolutionResultBuilder.setAttributes(result.attributes);
                    }
                }
                // 更新负载均衡策略，处理未处理的请求
                savedListener.onResult(resolutionResultBuilder.build());
            } catch (IOException e) {
                savedListener.onError(Status.UNAVAILABLE.withDescription("Unable to resolve host " + host).withCause(e));
            } finally {
                final boolean succeed = result != null && result.error == null;
                syncContext.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (succeed) {
                            resolved = true;
                            if (cacheTtlNanos > 0) {
                                stopwatch.reset().start();
                            }
                        }
                        resolving = false;
                    }
                });
            }
        }
    }

    /**
     * 通过 DNS 解析配置
     *
     * @param rawTxtRecords
     * @param random
     * @param localHostname
     * @return
     */
    @Nullable
    static ConfigOrError parseServiceConfig(List<String> rawTxtRecords,
                                            Random random,
                                            String localHostname) {
        List<Map<String, ?>> possibleServiceConfigChoices;
        try {
            // 解析可能的配置
            possibleServiceConfigChoices = parseTxtResults(rawTxtRecords);
        } catch (IOException | RuntimeException e) {
            return ConfigOrError.fromError(Status.UNKNOWN.withDescription("failed to parse TXT records").withCause(e));
        }

        Map<String, ?> possibleServiceConfig = null;

        // 遍历可能的配置
        for (Map<String, ?> possibleServiceConfigChoice : possibleServiceConfigChoices) {
            try {
                // 解析配置
                possibleServiceConfig = maybeChooseServiceConfig(possibleServiceConfigChoice, random, localHostname);
            } catch (RuntimeException e) {
                return ConfigOrError.fromError(Status.UNKNOWN.withDescription("failed to pick service config choice").withCause(e));
            }
            if (possibleServiceConfig != null) {
                break;
            }
        }
        if (possibleServiceConfig == null) {
            return null;
        }
        // 返回配置
        return ConfigOrError.fromConfig(possibleServiceConfig);
    }

    /**
     * 解析服务
     */
    private void resolve() {
        if (resolving || shutdown || !cacheRefreshRequired()) {
            return;
        }
        resolving = true;
        // 根据监听器，解析名称
        executor.execute(new Resolve(listener));
    }

    private boolean cacheRefreshRequired() {
        return !resolved
                || cacheTtlNanos == 0
                || (cacheTtlNanos > 0 && stopwatch.elapsed(TimeUnit.NANOSECONDS) > cacheTtlNanos);
    }

    /**
     * 关闭命名解析
     */
    @Override
    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        if (executor != null && usingExecutorResource) {
            executor = SharedResourceHolder.release(executorResource, executor);
        }
    }

    final int getPort() {
        return port;
    }

    /**
     * 解析 DNS 记录，获取配置
     *
     * @throws IOException if one of the txt records contains improperly formatted JSON.
     */
    @VisibleForTesting
    static List<Map<String, ?>> parseTxtResults(List<String> txtRecords) throws IOException {
        List<Map<String, ?>> possibleServiceConfigChoices = new ArrayList<>();

        for (String txtRecord : txtRecords) {
            if (!txtRecord.startsWith(SERVICE_CONFIG_PREFIX)) {
                logger.log(Level.FINE, "Ignoring non service config {0}", new Object[]{txtRecord});
                continue;
            }

            // 解析 JSON，获取配置
            Object rawChoices = JsonParser.parse(txtRecord.substring(SERVICE_CONFIG_PREFIX.length()));
            if (!(rawChoices instanceof List)) {
                throw new ClassCastException("wrong type " + rawChoices);
            }
            List<?> listChoices = (List<?>) rawChoices;
            possibleServiceConfigChoices.addAll(JsonUtil.checkObjectList(listChoices));
        }
        return possibleServiceConfigChoices;
    }

    /**
     * 从配置中获取百分比
     *
     * @param serviceConfigChoice 配置
     * @return 百分比
     */
    @Nullable
    private static final Double getPercentageFromChoice(Map<String, ?> serviceConfigChoice) {
        return JsonUtil.getNumber(serviceConfigChoice, SERVICE_CONFIG_CHOICE_PERCENTAGE_KEY);
    }

    /**
     * 获取客户端语言
     *
     * @param serviceConfigChoice 配置
     * @return 语言类型
     */
    @Nullable
    private static final List<String> getClientLanguagesFromChoice(Map<String, ?> serviceConfigChoice) {
        return JsonUtil.getListOfStrings(serviceConfigChoice, SERVICE_CONFIG_CHOICE_CLIENT_LANGUAGE_KEY);
    }

    /**
     * 获取客户端主机名
     *
     * @param serviceConfigChoice 配置
     * @return 主机名
     */
    @Nullable
    private static final List<String> getHostnamesFromChoice(Map<String, ?> serviceConfigChoice) {
        return JsonUtil.getListOfStrings(serviceConfigChoice, SERVICE_CONFIG_CHOICE_CLIENT_HOSTNAME_KEY);
    }

    /**
     * Returns value of network address cache ttl property if not Android environment. For android,
     * DnsNameResolver does not cache the dns lookup result.
     * 如果不是安卓平台，返回网络地址 ttl 缓存时间配置，如果是安卓，则不缓存
     */
    private static long getNetworkAddressCacheTtlNanos(boolean isAndroid) {
        if (isAndroid) {
            // on Android, ignore dns cache.
            return 0;
        }

        String cacheTtlPropertyValue = System.getProperty(NETWORKADDRESS_CACHE_TTL_PROPERTY);
        long cacheTtl = DEFAULT_NETWORK_CACHE_TTL_SECONDS;
        if (cacheTtlPropertyValue != null) {
            try {
                cacheTtl = Long.parseLong(cacheTtlPropertyValue);
            } catch (NumberFormatException e) {
                logger.log(Level.WARNING,
                        "Property({0}) valid is not valid number format({1}), fall back to default({2})",
                        new Object[]{NETWORKADDRESS_CACHE_TTL_PROPERTY, cacheTtlPropertyValue, cacheTtl});
            }
        }
        return cacheTtl > 0 ? TimeUnit.SECONDS.toNanos(cacheTtl) : cacheTtl;
    }

    /**
     * 确定给定的服务配置选项是否适用，如果适用，则将其返回
     * Determines if a given Service Config choice applies, and if so, returns it.
     *
     * @param choice The service config choice.
     *               可选的配置
     * @return The service config object or {@code null} if this choice does not apply.
     * 返回配置，如果不可用则返回 null
     * @see <a href="https://github.com/grpc/proposal/blob/master/A2-service-configs-in-dns.md">
     * Service Config in DNS</a>
     */
    @Nullable
    @VisibleForTesting
    static Map<String, ?> maybeChooseServiceConfig(Map<String, ?> choice,
                                                   Random random,
                                                   String hostname) {

        for (Entry<String, ?> entry : choice.entrySet()) {
            Verify.verify(SERVICE_CONFIG_CHOICE_KEYS.contains(entry.getKey()), "Bad key: %s", entry);
        }

        // 获取语言
        List<String> clientLanguages = getClientLanguagesFromChoice(choice);

        // 如果不是 Java 则返回
        if (clientLanguages != null && !clientLanguages.isEmpty()) {
            boolean javaPresent = false;
            for (String lang : clientLanguages) {
                if ("java".equalsIgnoreCase(lang)) {
                    javaPresent = true;
                    break;
                }
            }
            if (!javaPresent) {
                return null;
            }
        }

        // 获取百分比，如果大于给定值，则返回
        Double percentage = getPercentageFromChoice(choice);
        if (percentage != null) {
            int pct = percentage.intValue();
            Verify.verify(pct >= 0 && pct <= 100, "Bad percentage: %s", percentage);
            if (random.nextInt(100) >= pct) {
                return null;
            }
        }

        // 获取 Hostname
        List<String> clientHostnames = getHostnamesFromChoice(choice);
        if (clientHostnames != null && !clientHostnames.isEmpty()) {
            boolean hostnamePresent = false;
            for (String clientHostname : clientHostnames) {
                if (clientHostname.equals(hostname)) {
                    hostnamePresent = true;
                    break;
                }
            }
            // 如果没有 hostname 则返回
            if (!hostnamePresent) {
                return null;
            }
        }
        // 获取配置
        Map<String, ?> sc = JsonUtil.getObject(choice, SERVICE_CONFIG_CHOICE_SERVICE_CONFIG_KEY);
        if (sc == null) {
            throw new VerifyException(String.format("key '%s' missing in '%s'", choice, SERVICE_CONFIG_CHOICE_SERVICE_CONFIG_KEY));
        }
        return sc;
    }

    /**
     * Used as a DNS-based name resolver's internal representation of resolution result.
     * 用作基于 DNS 的名称解析器解析结果的内部表示
     */
    protected static final class InternalResolutionResult {
        private Status error;
        private List<EquivalentAddressGroup> addresses;
        private ConfigOrError config;
        public Attributes attributes;

        private InternalResolutionResult() {
        }
    }

    /**
     * Describes a parsed SRV record.
     * 描述解析的 SRV 记录
     */
    @VisibleForTesting
    public static final class SrvRecord {
        public final String host;
        public final int port;

        public SrvRecord(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(host, port);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            SrvRecord that = (SrvRecord) obj;
            return port == that.port && host.equals(that.host);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("host", host)
                              .add("port", port)
                              .toString();
        }
    }

    @VisibleForTesting
    protected void setAddressResolver(AddressResolver addressResolver) {
        this.addressResolver = addressResolver;
    }

    @VisibleForTesting
    protected void setResourceResolver(ResourceResolver resourceResolver) {
        this.resourceResolver.set(resourceResolver);
    }

    /**
     * {@link ResourceResolverFactory} is a factory for making resource resolvers.  It supports
     * optionally checking if the factory is available.
     * ResourceResolverFactory 是用于生成资源解析器的工厂，用于随机的检查工厂是否可用
     */
    interface ResourceResolverFactory {

        /**
         * Creates a new resource resolver.  The return value is {@code null} iff
         * {@link #unavailabilityCause()} is not null;
         * 创建一个资源解析器，如果 unavailabilityCause 方法返回不是 null，则返回 null
         */
        @Nullable
        ResourceResolver newResourceResolver();

        /**
         * Returns the reason why the resource resolver cannot be created.  The return value is
         * {@code null} if {@link #newResourceResolver()} is suitable for use.
         * 返回为什么不能创建资源解决器，如果可以用则返回 null
         */
        @Nullable
        Throwable unavailabilityCause();
    }

    /**
     * AddressResolver resolves a hostname into a list of addresses.
     * 将 host 解析成地址集合
     */
    @VisibleForTesting
    public interface AddressResolver {
        List<InetAddress> resolveAddress(String host) throws Exception;
    }

    private enum JdkAddressResolver implements AddressResolver {
        INSTANCE;

        /**
         * 根据 Host 解析所有的地址
         *
         * @param host host
         * @return 解析到的地址集合
         * @throws UnknownHostException
         */
        @Override
        public List<InetAddress> resolveAddress(String host) throws UnknownHostException {
            return Collections.unmodifiableList(Arrays.asList(InetAddress.getAllByName(host)));
        }
    }

    /**
     * {@link ResourceResolver} is a Dns ResourceRecord resolver.
     * ResourceResolver 是 DNS 记录资源解析器
     */
    @VisibleForTesting
    public interface ResourceResolver {
        List<String> resolveTxt(String host) throws Exception;

        List<SrvRecord> resolveSrv(String host) throws Exception;
    }

    /**
     * 返回资源解决实例
     */
    @Nullable
    protected ResourceResolver getResourceResolver() {
        if (!shouldUseJndi(enableJndi, enableJndiLocalhost, host)) {
            return null;
        }
        ResourceResolver rr;
        if ((rr = resourceResolver.get()) == null) {
            if (resourceResolverFactory != null) {
                assert resourceResolverFactory.unavailabilityCause() == null;
                rr = resourceResolverFactory.newResourceResolver();
            }
        }
        return rr;
    }

    /**
     * 初始化资源加载工厂实例
     *
     * @param loader 类加载器
     * @return 资源加载工厂实例
     */
    @Nullable
    @VisibleForTesting
    static ResourceResolverFactory getResourceResolverFactory(ClassLoader loader) {
        Class<? extends ResourceResolverFactory> jndiClazz;
        try {
            // 加载资源工厂类
            jndiClazz = Class.forName("io.grpc.internal.JndiResourceResolverFactory", true, loader)
                             .asSubclass(ResourceResolverFactory.class);
        } catch (ClassNotFoundException e) {
            logger.log(Level.FINE, "Unable to find JndiResourceResolverFactory, skipping.", e);
            return null;
        } catch (ClassCastException e) {
            // This can happen if JndiResourceResolverFactory was removed by something like Proguard
            // combined with a broken ClassLoader that prefers classes from the child over the parent
            // while also not properly filtering dependencies in the parent that should be hidden. If the
            // class loader prefers loading from the parent then ResourceresolverFactory would have also
            // been loaded from the parent. If the class loader filtered deps, then
            // JndiResourceResolverFactory wouldn't have been found.
            logger.log(Level.FINE, "Unable to cast JndiResourceResolverFactory, skipping.", e);
            return null;
        }

        // 获取构造器
        Constructor<? extends ResourceResolverFactory> jndiCtor;
        try {
            jndiCtor = jndiClazz.getConstructor();
        } catch (Exception e) {
            logger.log(Level.FINE, "Can't find JndiResourceResolverFactory ctor, skipping.", e);
            return null;
        }

        // 构建实例
        ResourceResolverFactory rrf;
        try {
            rrf = jndiCtor.newInstance();
        } catch (Exception e) {
            logger.log(Level.FINE, "Can't construct JndiResourceResolverFactory, skipping.", e);
            return null;
        }

        if (rrf.unavailabilityCause() != null) {
            logger.log(Level.FINE, "JndiResourceResolverFactory not available, skipping.", rrf.unavailabilityCause());
            return null;
        }
        return rrf;
    }

    /**
     * 获取本地主机名
     *
     * @return 主机名
     */
    private static String getLocalHostname() {
        if (localHostname == null) {
            try {
                localHostname = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        }
        return localHostname;
    }

    /**
     * 判断是否需要使用 JNDI
     */
    @VisibleForTesting
    protected static boolean shouldUseJndi(boolean jndiEnabled,
                                           boolean jndiLocalhostEnabled,
                                           String target) {
        if (!jndiEnabled) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(target)) {
            return jndiLocalhostEnabled;
        }
        // Check if this name looks like IPv6
        if (target.contains(":")) {
            return false;
        }
        // Check if this might be IPv4.  Such addresses have no alphabetic characters.  This also
        // checks the target is empty.
        boolean alldigits = true;
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c != '.') {
                alldigits &= (c >= '0' && c <= '9');
            }
        }
        return !alldigits;
    }
}
