/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.grpclb;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.internal.DnsNameResolver;
import io.grpc.internal.SharedResourceHolder.Resource;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A DNS-based {@link NameResolver} with gRPC LB specific add-ons for resolving balancer
 * addresses via service records.
 * 基于 DNS 的 NameResolver，带有 gRPC LB 特定的附件，用于通过服务记录来解析地址
 * <p>
 * 将 host 通过 DNS 记录解析为相应的 IP 地址
 * <p>
 * $ORIGIN example.com.
 * server              IN A 10.0.0.11
 * IN A 10.0.0.12
 * ; grpclb for server.example.com goes to lb.example.com:1234
 * _grpclb._tcp.server IN SRV 0 0 1234 lb
 * ; lb.example.com has 3 IP addresses
 * lb                  IN A 10.0.0.1
 * IN A 10.0.0.2
 * IN A 10.0.0.3
 *
 * @see SecretGrpclbNameResolverProvider
 */
final class GrpclbNameResolver extends DnsNameResolver {

    private static final Logger logger = Logger.getLogger(GrpclbNameResolver.class.getName());

    // From https://github.com/grpc/proposal/blob/master/A5-grpclb-in-dns.md
    private static final String GRPCLB_NAME_PREFIX = "_grpclb._tcp.";

    GrpclbNameResolver(@Nullable String nsAuthority,
                       String name,
                       Args args,
                       Resource<Executor> executorResource,
                       Stopwatch stopwatch,
                       boolean isAndroid) {
        // 调用 DnsNameResolver 进行解析
        super(nsAuthority, name, args, executorResource, stopwatch, isAndroid);
    }

    /**
     * 解析记录
     *
     * @param forceTxt
     * @return 地址结果
     */
    @Override
    protected InternalResolutionResult doResolve(boolean forceTxt) {
        // 解析记录
        List<EquivalentAddressGroup> balancerAddrs = resolveBalancerAddresses();
        InternalResolutionResult result = super.doResolve(!balancerAddrs.isEmpty());

        if (!balancerAddrs.isEmpty()) {
            result.attributes = Attributes.newBuilder()
                                          .set(GrpclbConstants.ATTR_LB_ADDRS, balancerAddrs)
                                          .build();
        }
        return result;
    }

    /**
     * 解析记录，将 host 转为 IP 地址集合
     *
     * @return 解析的地址
     */
    private List<EquivalentAddressGroup> resolveBalancerAddresses() {
        List<SrvRecord> srvRecords = Collections.emptyList();
        Exception srvRecordsException = null;
        // 获取 ResourceResolver 实例
        ResourceResolver resourceResolver = getResourceResolver();
        if (resourceResolver != null) {
            try {
                // 解析记录
                srvRecords = resourceResolver.resolveSrv(GRPCLB_NAME_PREFIX + getHost());
            } catch (Exception e) {
                srvRecordsException = e;
            }
        }

        List<EquivalentAddressGroup> balancerAddresses = new ArrayList<>(srvRecords.size());
        Exception balancerAddressesException = null;
        Level level = Level.WARNING;
        // 将解析的记录转为地址
        for (SrvRecord record : srvRecords) {
            try {
                // Strip trailing dot for appearance's sake. It _should_ be fine either way, but most
                // people expect to see it without the dot.
                // 截断最后一个 '.'
                String authority = record.host.substring(0, record.host.length() - 1);
                // But we want to use the trailing dot for the IP lookup. The dot makes the name absolute
                // instead of relative and so will avoid the search list like that in resolv.conf.
                // 将 host 解析为 IP 集合
                List<? extends InetAddress> addrs = addressResolver.resolveAddress(record.host);
                // 遍历添加端口号
                List<SocketAddress> sockAddrs = new ArrayList<>(addrs.size());
                for (InetAddress addr : addrs) {
                    sockAddrs.add(new InetSocketAddress(addr, record.port));
                }
                // 添加域名属性
                Attributes attrs = Attributes.newBuilder()
                                             .set(GrpclbConstants.ATTR_LB_ADDR_AUTHORITY, authority)
                                             .build();
                // 添加到负载均衡的地址中
                balancerAddresses.add(new EquivalentAddressGroup(Collections.unmodifiableList(sockAddrs), attrs));
            } catch (Exception e) {
                logger.log(level, "Can't find address for SRV record " + record, e);
                if (balancerAddressesException == null) {
                    balancerAddressesException = e;
                    level = Level.FINE;
                }
            }
        }
        if (srvRecordsException != null ||
                (balancerAddressesException != null && balancerAddresses.isEmpty())) {
            logger.log(Level.FINE, "Balancer resolution failure", srvRecordsException);
        }
        return Collections.unmodifiableList(balancerAddresses);
    }

    @VisibleForTesting
    @Override
    protected void setAddressResolver(AddressResolver addressResolver) {
        super.setAddressResolver(addressResolver);
    }

    @VisibleForTesting
    @Override
    protected void setResourceResolver(ResourceResolver resourceResolver) {
        super.setResourceResolver(resourceResolver);
    }

    @VisibleForTesting
    @Override
    protected String getHost() {
        return super.getHost();
    }

    @VisibleForTesting
    static void setEnableTxt(boolean enableTxt) {
        DnsNameResolver.enableTxt = enableTxt;
    }
}
