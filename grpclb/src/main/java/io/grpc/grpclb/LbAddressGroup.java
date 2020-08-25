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

package io.grpc.grpclb;

import io.grpc.EquivalentAddressGroup;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Represents a balancer address entry.
 * 代表负载均衡地址的实体
 */
final class LbAddressGroup {
    // 地址
    private final EquivalentAddressGroup addresses;
    // 主机名
    private final String authority;

    LbAddressGroup(EquivalentAddressGroup addresses, String authority) {
        this.addresses = checkNotNull(addresses, "addresses");
        this.authority = checkNotNull(authority, "authority");
    }

    EquivalentAddressGroup getAddresses() {
        return addresses;
    }

    String getAuthority() {
        return authority;
    }
}
