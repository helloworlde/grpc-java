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

package io.grpc;

import com.google.common.base.MoreObjects;
import io.grpc.NameResolver.ConfigOrError;

import java.util.Map;

/**
 * Provider of {@link LoadBalancer}s.  Each provider is bounded to a load-balancing policy name.
 * 提供 LoadBalancer，每一个 Provider 都和负载均衡策略绑定
 *
 * <p>Implementations can be automatically discovered by gRPC via Java's SPI mechanism. For
 * automatic discovery, the implementation must have a zero-argument constructor and include
 * a resource named {@code META-INF/services/io.grpc.LoadBalancerProvider} in their JAR. The
 * file's contents should be the implementation's class name. Implementations that need arguments in
 * their constructor can be manually registered by {@link LoadBalancerRegistry#register}.
 * <p>
 * 实现可以通过 gRPC 或者 Java 的 SPI 自动发现，在自动发现时，实现必须有一个无参的构造函数，并且包含在
 * Jar 目录的 META-INF/services/io.grpc.LoadBalancerProvider 内，内容是实现类的名称，构造器需要参数的
 * 实现类可以手动的通过 LoadBalancerRegistry#register 注册
 *
 * <p>Implementations <em>should not</em> throw. If they do, it may interrupt class loading. If
 * exceptions may reasonably occur for implementation-specific reasons, implementations should
 * generally handle the exception gracefully and return {@code false} from {@link #isAvailable()}.
 * 实现不应该抛出异常，如果抛出了可能会打断类加载，如果由于特定于实现的原因可能合理地发生例外，实现需要优雅的处理
 * 异常并通过 isAvailable 返回 false
 *
 * @since 1.17.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
public abstract class LoadBalancerProvider extends LoadBalancer.Factory {

    /**
     * A sentinel value indicating that service config is not supported.   This can be used to
     * indicate that parsing of the service config is neither right nor wrong, but doesn't have
     * any meaning.
     * 一个指示值，指示服务不支持配置，用于表明配置既不是对的也不是错的，没有任何意义
     */
    private static final ConfigOrError UNKNOWN_CONFIG = ConfigOrError.fromConfig(new UnknownConfig());

    /**
     * Whether this provider is available for use, taking the current environment into consideration.
     * If {@code false}, {@link #newLoadBalancer} is not safe to be called.
     * <p>
     * 表示 Provider 是否可用，考虑当前环境，如果是 false，newLoadBalancer 调用是不安全的
     */
    public abstract boolean isAvailable();

    /**
     * A priority, from 0 to 10 that this provider should be used, taking the current environment into
     * consideration. 5 should be considered the default, and then tweaked based on environment
     * detection. A priority of 0 does not imply that the provider wouldn't work; just that it should
     * be last in line.
     * <p>
     * 表示是否可以使用的优先级，有效值是 0-10，考虑当前环境；5是默认值，然后基于环境调整，0 不代表 Provider 将不会工作，
     * 只是优先级排在最后
     */
    public abstract int getPriority();

    /**
     * Returns the load-balancing policy name associated with this provider, which makes it selectable
     * via {@link LoadBalancerRegistry#getProvider}.  This is called only when the class is loaded. It
     * shouldn't change, and there is no point doing so.
     * 返回关联的负载均衡策略名称，在  LoadBalancerRegistry#getProvider 中是可选的，只有当类加载后可以调用，
     * 不应当发生变化，这样做是没有意义的
     *
     * <p>The policy name should consist of only lower case letters letters, underscore and digits,
     * and can only start with letters.
     * 策略名称应当是小写的字符，下划线或者数字，以字符开头
     */
    public abstract String getPolicyName();

    /**
     * Parses the config for the Load Balancing policy unpacked from the service config.  This will
     * return a {@link ConfigOrError} which contains either the successfully parsed config, or the
     * {@link Status} representing the failure to parse.  Implementations are expected to not throw
     * exceptions but return a Status representing the failure.  If successful, the load balancing
     * policy config should be immutable.
     * <p>
     * 解析负载均衡策略的配置，无论是否解析成功都会返回 ConfigOrError，即使失败实现不应当抛出异常，如果成功，
     * 负载均衡策略不应该再发生变化
     *
     * @param rawLoadBalancingPolicyConfig The {@link Map} representation of the load balancing
     *                                     policy choice.
     *                                     代表负载均衡策略
     * @return a tuple of the fully parsed and validated balancer configuration, else the Status.
     * 解析的有效的负载均衡策略或者状态
     * @see <a href="https://github.com/grpc/proposal/blob/master/A24-lb-policy-config.md">
     * A24-lb-policy-config.md</a>
     * @since 1.20.0
     */
    public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingPolicyConfig) {
        return UNKNOWN_CONFIG;
    }

    @Override
    public final String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("policy", getPolicyName())
                          .add("priority", getPriority())
                          .add("available", isAvailable())
                          .toString();
    }

    /**
     * Uses identity equality.
     */
    @Override
    public final boolean equals(Object other) {
        return this == other;
    }

    @Override
    public final int hashCode() {
        return super.hashCode();
    }

    private static final class UnknownConfig {

        UnknownConfig() {
        }

        @Override
        public String toString() {
            return "service config is unused";
        }
    }
}
