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

package io.grpc.netty;

import io.grpc.Attributes;
import io.grpc.Internal;
import io.grpc.InternalChannelz;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Settings;

import javax.annotation.Nullable;

/**
 * gRPC wrapper for {@link Http2ConnectionHandler}.
 * 用于 gRPC 的 Http2ConnectionHandler 封装
 */
@Internal
public abstract class GrpcHttp2ConnectionHandler extends Http2ConnectionHandler {

    /**
     * 可写入的特殊 ChannelFuture
     */
    @Nullable
    protected final ChannelPromise channelUnused;

    public GrpcHttp2ConnectionHandler(ChannelPromise channelUnused,
                                      Http2ConnectionDecoder decoder,
                                      Http2ConnectionEncoder encoder,
                                      Http2Settings initialSettings) {
        super(decoder, encoder, initialSettings);
        this.channelUnused = channelUnused;
    }

    /**
     * Same as {@link #handleProtocolNegotiationCompleted(
     *Attributes, io.grpc.InternalChannelz.Security)}
     * but with no {@link io.grpc.InternalChannelz.Security}.
     *
     * @deprecated Use the two argument method instead.
     */
    @Deprecated
    public void handleProtocolNegotiationCompleted(Attributes attrs) {
        handleProtocolNegotiationCompleted(attrs, /*securityInfo=*/ null);
    }

    /**
     * Triggered on protocol negotiation completion.
     * 当协议谈判完成时触发
     *
     * <p>It must me called after negotiation is completed but before given handler is added to the
     * channel.
     * 必须在协议谈判完成之后，将处理器添加到 channel 中之前调用
     *
     * @param attrs        arbitrary attributes passed after protocol negotiation (eg. SSLSession).
     *                     当协议谈判完成后传递的属性
     * @param securityInfo informs channelz about the security protocol.
     *                     通知 channelz 有关安全的协议
     */
    public void handleProtocolNegotiationCompleted(Attributes attrs,
                                                   InternalChannelz.Security securityInfo) {
    }

    /**
     * Calling this method indicates that the channel will no longer be used.  This method is roughly
     * the same as calling {@link #close} on the channel, but leaving the channel alive.  This is
     * useful if the channel will soon be deregistered from the executor and used in a non-Netty
     * context.
     * 调用这个方法表明 channel 不再使用，这个方法和调用 close 大致一样，但是会保持 channel 存活，当 channel 从执行器中
     * 注销，用于非 Netty 的上下文中时是有用的
     */
    @SuppressWarnings("FutureReturnValueIgnored")
    public void notifyUnused() {
        channelUnused.setSuccess(null);
    }

    /**
     * Get the attributes of the EquivalentAddressGroup used to create this transport.
     * 获取 EquivalentAddressGroup 的属性用于创建 Transport
     */
    public Attributes getEagAttributes() {
        return Attributes.EMPTY;
    }

    /**
     * Returns the authority of the server. Only available on the client-side.
     * 返回 server 的 authority，只有在客户端是可用的
     *
     * @throws UnsupportedOperationException if on server-side
     */
    public String getAuthority() {
        throw new UnsupportedOperationException();
    }
}
