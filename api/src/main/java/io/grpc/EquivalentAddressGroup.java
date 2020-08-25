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

import com.google.common.base.Preconditions;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A group of {@link SocketAddress}es that are considered equivalent when channel makes connections.
 * 一组 SocketAddress，当 Channel 创建连接时，他们是等效的
 *
 * <p>Usually the addresses are addresses resolved from the same host name, and connecting to any of
 * them is equally sufficient. They do have order. An address appears earlier on the list is likely
 * to be tried earlier.
 * 通常这些地址是解析同一个主机名得到的，连接其中的 任何一个都是等效的，地址是有序的，先添加到集合中的会被先尝试
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1770")
public final class EquivalentAddressGroup {

    /**
     * The authority to be used when constructing Subchannels for this EquivalentAddressGroup.
     * 名称用于在创建 Subchannel 的时候使用
     */
    @Attr
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/6138")
    public static final Attributes.Key<String> ATTR_AUTHORITY_OVERRIDE =
            Attributes.Key.create("io.grpc.EquivalentAddressGroup.authorityOverride");

    private final List<SocketAddress> addrs;
    private final Attributes attrs;

    /**
     * {@link SocketAddress} docs say that the addresses are immutable, so we cache the hashCode.
     * 因为 SocketAddress 的地址是不可变的，所以缓存 hashCode
     */
    private final int hashCode;

    /**
     * List constructor without {@link Attributes}.
     * 在没有 Attributes 的情况下构建 EquivalentAddressGroup
     */
    public EquivalentAddressGroup(List<SocketAddress> addrs) {
        this(addrs, Attributes.EMPTY);
    }

    /**
     * List constructor with {@link Attributes}.
     * 使用地址和属性构建 EquivalentAddressGroup
     */
    public EquivalentAddressGroup(List<SocketAddress> addrs, @Attr Attributes attrs) {
        Preconditions.checkArgument(!addrs.isEmpty(), "addrs is empty");
        this.addrs = Collections.unmodifiableList(new ArrayList<>(addrs));
        this.attrs = Preconditions.checkNotNull(attrs, "attrs");
        // Attributes may contain mutable objects, which means Attributes' hashCode may change over
        // time, thus we don't cache Attributes' hashCode.
        // 缓存地址的 hashCode
        hashCode = this.addrs.hashCode();
    }

    /**
     * Singleton constructor without Attributes.
     * 通过一个地址构建 EquivalentAddressGroup
     */
    public EquivalentAddressGroup(SocketAddress addr) {
        this(addr, Attributes.EMPTY);
    }

    /**
     * Singleton constructor with Attributes.
     * 通过一个地址和属性构建 EquivalentAddressGroup
     */
    public EquivalentAddressGroup(SocketAddress addr, @Attr Attributes attrs) {
        this(Collections.singletonList(addr), attrs);
    }

    /**
     * Returns an immutable list of the addresses.
     */
    public List<SocketAddress> getAddresses() {
        return addrs;
    }

    /**
     * Returns the attributes.
     */
    @Attr
    public Attributes getAttributes() {
        return attrs;
    }

    @Override
    public String toString() {
        // TODO(zpencer): Summarize return value if addr is very large
        return "[" + addrs + "/" + attrs + "]";
    }

    @Override
    public int hashCode() {
        // Avoids creating an iterator on the underlying array list.
        return hashCode;
    }

    /**
     * Returns true if the given object is also an {@link EquivalentAddressGroup} with an equal
     * address list and equal attribute values.
     * 如果地址和属性相同，则返回 EquivalentAddressGroup 相等
     *
     * <p>Note that if the attributes include mutable values, it is possible for two objects to be
     * considered equal at one point in time and not equal at another (due to concurrent mutation of
     * attribute values).
     * 因为属性中包含可变的值，所以两个对象可能在这一时刻相等，但下一时刻不等
     */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EquivalentAddressGroup)) {
            return false;
        }
        EquivalentAddressGroup that = (EquivalentAddressGroup) other;
        if (addrs.size() != that.addrs.size()) {
            return false;
        }
        // Avoids creating an iterator on the underlying array list.
        for (int i = 0; i < addrs.size(); i++) {
            if (!addrs.get(i).equals(that.addrs.get(i))) {
                return false;
            }
        }
        if (!attrs.equals(that.attrs)) {
            return false;
        }
        return true;
    }

    /**
     * Annotation for {@link EquivalentAddressGroup}'s attributes. It follows the annotation semantics
     * defined by {@link Attributes}.
     * EquivalentAddressGroup 属性的注解，遵循 Attributes 定义的注释语义
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4972")
    @Retention(RetentionPolicy.SOURCE)
    @Documented
    public @interface Attr {
    }
}
