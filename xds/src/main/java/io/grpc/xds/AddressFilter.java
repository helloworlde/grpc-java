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

package io.grpc.xds;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver.ResolutionResultAttr;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * 地址过滤
 */
final class AddressFilter {
    @ResolutionResultAttr
    private static final Attributes.Key<PathChain> PATH_CHAIN_KEY = Attributes.Key.create("io.grpc.xds.AddressFilter.PATH_CHAIN_KEY");

    // Prevent instantiation.
    private AddressFilter() {
    }

    /**
     * Returns a new EquivalentAddressGroup by setting a path filter to the given
     * EquivalentAddressGroup. This method does not modify the input address.
     * 根据所给的 EquivalentAddressGroup 返回设置过 path 过滤的 EquivalentAddressGroup
     * 这个方法不会修改输入的地址
     */
    static EquivalentAddressGroup setPathFilter(EquivalentAddressGroup address, List<String> names) {
        checkNotNull(address, "address");
        checkNotNull(names, "names");

        // 移除地址属性中的 PATH_CHAIN_KEY 属性
        Attributes.Builder attrBuilder = address.getAttributes().toBuilder().discard(PATH_CHAIN_KEY);

        PathChain pathChain = null;
        // 遍历优先级，设置 path 链
        for (String name : names) {
            if (pathChain == null) {
                pathChain = new PathChain(name);
                attrBuilder.set(PATH_CHAIN_KEY, pathChain);
            } else {
                pathChain.next = new PathChain(name);
            }
        }
        // 返回地址
        return new EquivalentAddressGroup(address.getAddresses(), attrBuilder.build());
    }

    /**
     * Returns the next level hierarchical addresses derived from the given hierarchical addresses
     * with the given filter name (any non-hierarchical addresses in the input will be ignored).
     * This method does not modify the input addresses.
     * <p>
     * 根据所给的地址和过滤名称，返回下一优先级的地址，非优先级的地址将会被忽略，这个方法不会修改输入的地址
     */
    static List<EquivalentAddressGroup> filter(List<EquivalentAddressGroup> addresses, String name) {
        checkNotNull(addresses, "addresses");
        checkNotNull(name, "name");

        List<EquivalentAddressGroup> filteredAddresses = new ArrayList<>();

        for (EquivalentAddressGroup address : addresses) {
            // 获取 PathChain
            PathChain pathChain = address.getAttributes().get(PATH_CHAIN_KEY);

            // 如果 PathChain 不为 null，且名称相同
            if (pathChain != null && pathChain.name.equals(name)) {
                // 设置 PathChain 的下一个地址
                Attributes filteredAddressAttrs = address.getAttributes().toBuilder().set(PATH_CHAIN_KEY, pathChain.next).build();
                // 将设置了属性的地址添加到集合中
                filteredAddresses.add(new EquivalentAddressGroup(address.getAddresses(), filteredAddressAttrs));
            }
        }
        return Collections.unmodifiableList(filteredAddresses);
    }

    /**
     * Path 链
     */
    private static final class PathChain {
        final String name;

        @Nullable
        PathChain next;

        PathChain(String name) {
            this.name = checkNotNull(name, "name");
        }
    }
}
