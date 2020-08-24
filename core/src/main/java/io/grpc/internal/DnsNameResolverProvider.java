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

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import io.grpc.InternalServiceProviders;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;

import java.net.URI;

/**
 * A provider for {@link DnsNameResolver}.
 * DNS 服务解析提供器
 *
 * <p>It resolves a target URI whose scheme is {@code "dns"}. The (optional) authority of the target
 * URI is reserved for the address of alternative DNS server (not implemented yet). The path of the
 * target URI, excluding the leading slash {@code '/'}, is treated as the host name and the optional
 * port to be resolved by DNS. Example target URIs:
 * 用于解析 Schema 为 dns 的服务，目标 URI 的（可选）权限保留用于备用DNS服务器的地址（尚未实现），目标 URI，
 * 不包含斜线, 被视为主机名和要由 DNS 解析的可选端口，目标URI示例：
 *
 * <ul>
 *   <li>{@code "dns:///foo.googleapis.com:8080"} (using default DNS)
 *   默认的 DNS
 *   </li>
 *   <li>{@code "dns://8.8.8.8/foo.googleapis.com:8080"} (using alternative DNS (not implemented
 *   yet))
 *   可选的 DNS
 *   </li>
 *   <li>{@code "dns:///foo.googleapis.com"} (without port)
 *   没有端口
 *   </li>
 * </ul>
 */
public final class DnsNameResolverProvider extends NameResolverProvider {

    private static final String SCHEME = "dns";

    @Override
    public DnsNameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        // 如果是 DNS 开头的 Schema
        if (SCHEME.equals(targetUri.getScheme())) {
            // 检查要解析的服务不为空，且以斜线开头
            String targetPath = Preconditions.checkNotNull(targetUri.getPath(), "targetPath");
            Preconditions.checkArgument(targetPath.startsWith("/"), "the path component (%s) of the target (%s) must start with '/'", targetPath, targetUri);

            // 截取斜线之后的部分作为服务名
            String name = targetPath.substring(1);
            // 创建 DNS 服务解析器
            return new DnsNameResolver(
                    targetUri.getAuthority(),
                    name,
                    args,
                    GrpcUtil.SHARED_CHANNEL_EXECUTOR,
                    Stopwatch.createUnstarted(),
                    InternalServiceProviders.isAndroid(getClass().getClassLoader()));
        } else {
            // 如果不是 DNS 开头的则返回 null
            return null;
        }
    }

    /**
     * 获取默认的 Schema
     *
     * @return schema
     */
    @Override
    public String getDefaultScheme() {
        return SCHEME;
    }

    /**
     * 是否可用
     */
    @Override
    protected boolean isAvailable() {
        return true;
    }

    /**
     * 优先级
     */
    @Override
    public int priority() {
        return 5;
    }
}
