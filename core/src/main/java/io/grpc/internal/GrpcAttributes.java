/*
 * Copyright 2017 The gRPC Authors
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

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Grpc;
import io.grpc.SecurityLevel;

/**
 * Special attributes that are only useful to gRPC.
 * 仅对 gRPC 有用的特殊的属性
 */
public final class GrpcAttributes {
    /**
     * The security level of the transport.  If it's not present, {@link SecurityLevel#NONE} should be
     * assumed.
     * Transport 的安全级别，如果没有，则应该设置为 SecurityLevel#NONE
     */
    @Grpc.TransportAttr
    public static final Attributes.Key<SecurityLevel> ATTR_SECURITY_LEVEL = Attributes.Key.create("io.grpc.internal.GrpcAttributes.securityLevel");

    /**
     * Attribute key for the attributes of the {@link EquivalentAddressGroup} ({@link
     * EquivalentAddressGroup#getAttributes}) that the transport's server address is from.  This is a
     * client-side-only transport attribute, and available right after the transport is started.
     * <p>
     * EquivalentAddressGroup 的属性的 key，属性来自于 EquivalentAddressGroup#getAttributes，仅用于客户端 Transport 的属性，
     * 当 Transport 开始后可用
     */
    @Grpc.TransportAttr
    public static final Attributes.Key<Attributes> ATTR_CLIENT_EAG_ATTRS = Attributes.Key.create("io.grpc.internal.GrpcAttributes.clientEagAttrs");

    private GrpcAttributes() {
    }
}
