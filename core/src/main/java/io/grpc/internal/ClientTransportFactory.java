/*
 * Copyright 2014 The gRPC Authors
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

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.HttpConnectProxiedSocketAddress;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.net.SocketAddress;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Pre-configured factory for creating {@link ConnectionClientTransport} instances.
 * 用于创建 ConnectionClientTransport 实例的预先配置工厂
 */
public interface ClientTransportFactory extends Closeable {
    /**
     * Creates an unstarted transport for exclusive use. Ownership of {@code options} is passed to the
     * callee; the caller should not reuse or read from the options after this method is called.
     * <p>
     * 创建一个未开始的 Transport，所有者通过 options 传递，当这个方法调用之后，不应当再读取或使用 options
     *
     * @param serverAddress the address that the transport is connected to
     *                      连接的地址
     * @param options       additional configuration
     *                      附加的配置
     * @param channelLogger logger for the transport.
     *                      日志
     */
    ConnectionClientTransport newClientTransport(SocketAddress serverAddress,
                                                 ClientTransportOptions options,
                                                 ChannelLogger channelLogger);

    /**
     * Returns an executor for scheduling provided by the transport. The service should be configured
     * to allow cancelled scheduled runnables to be GCed.
     * 返回 transport 提供的用于调度的执行器，这个服务应当配置为允许已取消调度的任务被回收
     *
     * <p>The executor should not be used after the factory has been closed. The caller should ensure
     * any outstanding tasks are cancelled before the factory is closed. However, it is a
     * <a href="https://github.com/grpc/grpc-java/issues/1981">known issue</a> that ClientCallImpl may
     * use this executor after close, so implementations should not go out of their way to prevent
     * usage.
     * 当这个工厂关闭后，不应该再使用这个执行器，在工厂关闭前，调用者应当确保所有的任务都已经被取消了；
     * 已知一个问题是 ClientCallImpl 可能会在关闭之后使用执行器，所以实现者应当绕开避免同样的使用
     */
    ScheduledExecutorService getScheduledExecutorService();

    /**
     * Releases any resources.
     * 释放资源
     * <p>After this method has been called, it's no longer valid to call
     * 当调用这个方法之后，其他的调用变为无效
     * {@link #newClientTransport}. No guarantees about thread-safety are made. 不保证线程安全
     */
    @Override
    void close();

    /**
     * Options passed to {@link #newClientTransport(SocketAddress, ClientTransportOptions)}. Although
     * it is safe to save this object if received, it is generally expected that the useful fields are
     * copied and then the options object is discarded. This allows using {@code final} for those
     * fields as well as avoids retaining unused objects contained in the options.
     * <p>
     * 用于传递给 newClientTransport() 方法的参数，尽管接收到对象后保存是安全的，通常建议复制需要的字段，然后丢弃这个对象，
     * 允许使用 final 定义字段，并避免保留选项中包含的未使用对象
     */
    final class ClientTransportOptions {

        private ChannelLogger channelLogger;
        private String authority = "unknown-authority";
        private Attributes eagAttributes = Attributes.EMPTY;

        @Nullable
        private String userAgent;

        @Nullable
        private HttpConnectProxiedSocketAddress connectProxiedSocketAddr;

        public ChannelLogger getChannelLogger() {
            return channelLogger;
        }

        public ClientTransportOptions setChannelLogger(ChannelLogger channelLogger) {
            this.channelLogger = channelLogger;
            return this;
        }

        public String getAuthority() {
            return authority;
        }

        /**
         * Sets the non-null authority.
         * 设置非空的服务名
         */
        public ClientTransportOptions setAuthority(String authority) {
            this.authority = Preconditions.checkNotNull(authority, "authority");
            return this;
        }

        /**
         * 获取地址的属性
         */
        public Attributes getEagAttributes() {
            return eagAttributes;
        }

        /**
         * Sets the non-null EquivalentAddressGroup's attributes.
         * 设置非空的地址的属性
         */
        public ClientTransportOptions setEagAttributes(Attributes eagAttributes) {
            Preconditions.checkNotNull(eagAttributes, "eagAttributes");
            this.eagAttributes = eagAttributes;
            return this;
        }

        @Nullable
        public String getUserAgent() {
            return userAgent;
        }

        public ClientTransportOptions setUserAgent(@Nullable String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        @Nullable
        public HttpConnectProxiedSocketAddress getHttpConnectProxiedSocketAddress() {
            return connectProxiedSocketAddr;
        }

        public ClientTransportOptions setHttpConnectProxiedSocketAddress(@Nullable HttpConnectProxiedSocketAddress connectProxiedSocketAddr) {
            this.connectProxiedSocketAddr = connectProxiedSocketAddr;
            return this;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(authority, eagAttributes, userAgent, connectProxiedSocketAddr);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ClientTransportOptions)) {
                return false;
            }
            ClientTransportOptions that = (ClientTransportOptions) o;
            return this.authority.equals(that.authority)
                    && this.eagAttributes.equals(that.eagAttributes)
                    && Objects.equal(this.userAgent, that.userAgent)
                    && Objects.equal(this.connectProxiedSocketAddr, that.connectProxiedSocketAddr);
        }
    }
}
