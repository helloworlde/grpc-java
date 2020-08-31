/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds;

import com.google.common.base.Preconditions;
import io.grpc.Internal;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.XdsClient.XdsChannelFactory;

import java.net.URI;

/**
 * A provider for {@link XdsNameResolver}.
 * XDS 命名解析提供器
 *
 * <p>It resolves a target URI whose scheme is {@code "xds"}. The authority of the
 * target URI is never used for current release. The path of the target URI, excluding the leading
 * slash {@code '/'}, will indicate the name to use in the VHDS query.
 * 用于解析 schema 为 xds 的目标地址，目标 URI 仅限于当前版本，目标的 URI 除了斜线外，将被作为名称用于 VHDS 查询
 *
 * <p>This class should not be directly referenced in code. The resolver should be accessed
 * through {@link io.grpc.NameResolverRegistry} with the URI scheme "xds".
 * 这个类不应当被直接使用，schema 为 xds 的地址应当通过 io.grpc.NameResolverRegistry 使用
 */
@Internal
public final class XdsNameResolverProvider extends NameResolverProvider {

    private static final String SCHEME = "xds";

    @Override
    public XdsNameResolver newNameResolver(URI targetUri, Args args) {
        // 如果 schema 是 xds
        if (SCHEME.equals(targetUri.getScheme())) {
            String targetPath = Preconditions.checkNotNull(targetUri.getPath(), "targetPath");
            Preconditions.checkArgument(targetPath.startsWith("/"),
                    "the path component (%s) of the target (%s) must start with '/'",
                    targetPath,
                    targetUri);

            String name = targetPath.substring(1);
            // 创建 NameResolver
            return new XdsNameResolver(name,
                    args,
                    new ExponentialBackoffPolicy.Provider(),
                    GrpcUtil.STOPWATCH_SUPPLIER,
                    XdsChannelFactory.getInstance(),
                    Bootstrapper.getInstance());
        }
        return null;
    }

    @Override
    public String getDefaultScheme() {
        return SCHEME;
    }

    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        // Set priority value to be < 5 as we still want DNS resolver to be the primary default
        // resolver.
        // 优先级设置为 4，因为依然想使用 DNS 解析器作为主要的默认解析器
        return 4;
    }
}
