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

package io.grpc;

import javax.net.ssl.SSLSession;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.SocketAddress;

/**
 * Stuff that are part of the public API but are not bound to particular classes, e.g., static
 * methods, constants, attribute and context keys.
 * 属于公共 API，但是未绑定到特定类上的成员，如静态的方法，常量，属性和上下文的键
 */
public final class Grpc {
    private Grpc() {
    }

    /**
     * Attribute key for the remote address of a transport.
     * Transport 的远程地址的属性 key
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1710")
    @TransportAttr
    public static final Attributes.Key<SocketAddress> TRANSPORT_ATTR_REMOTE_ADDR = Attributes.Key.create("remote-addr");

    /**
     * Attribute key for the local address of a transport.
     * Transport 本地地址的属性 key
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1710")
    @TransportAttr
    public static final Attributes.Key<SocketAddress> TRANSPORT_ATTR_LOCAL_ADDR = Attributes.Key.create("local-addr");

    /**
     * Attribute key for SSL session of a transport.
     * Transport SSL 会话的属性的 key
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1710")
    @TransportAttr
    public static final Attributes.Key<SSLSession> TRANSPORT_ATTR_SSL_SESSION = Attributes.Key.create("ssl-session");

    /**
     * Annotation for transport attributes. It follows the annotation semantics defined
     * by {@link Attributes}.
     * Transport 属性的注解，遵循 Attributes 的语义
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4972")
    @Retention(RetentionPolicy.SOURCE)
    @Documented
    public @interface TransportAttr {
    }
}
