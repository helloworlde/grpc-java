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

/**
 * Listens on server transport life-cycle events, with the capability to read and/or change
 * transport attributes.  Attributes returned by this filter will be merged into {@link
 * ServerCall#getAttributes}.
 * 监听服务端 Transport 生命周期事件，有读取或修改 Transport 属性的能力，这个过滤器返回的属性将被合并到
 * ServerCall#getAttributes 中
 *
 * <p>Multiple filters maybe registered to a server, in which case the output of a filter is the
 * input of the next filter.  For example, what returned by {@link #transportReady} of a filter is
 * passed to the same method of the next filter, and the last filter's return value is the effective
 * transport attributes.
 * server 可能会注册多个过滤器，一个过滤器的输出是下一个过滤器的输入
 *
 * <p>{@link Grpc} defines commonly used attributes.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2132")
public abstract class ServerTransportFilter {
    /**
     * Called when a transport is ready to process streams.  All necessary handshakes, e.g., TLS
     * handshake, are done at this point.
     * 当 Transport 准备好处理流的时候调用，所有必要的握手，如 TLS 握手，在此之前都已经完成
     *
     * <p>Note the implementation should always inherit the passed-in attributes using {@code
     * Attributes.newBuilder(transportAttrs)}, instead of creating one from scratch.
     * 需要注意实现应当总是通过 Attributes.newBuilder(transportAttrs) 传递属性，而不是创建新的
     *
     * @param transportAttrs current transport attributes
     *                       当前的属性
     * @return new transport attributes. Default implementation returns the passed-in attributes
     * intact.
     * 新的属性，默认的实现完整返回传入的属性
     */
    public Attributes transportReady(Attributes transportAttrs) {
        return transportAttrs;
    }

    /**
     * Called when a transport is terminated.  Default implementation is no-op.
     * 当 Transport 终止时调用，默认的实现没有操作
     *
     * @param transportAttrs the effective transport attributes, which is what returned by {@link
     *                       #transportReady} of the last executed filter.
     *                       有效的 Transport 顺序，由 transportReady 最后执行的过滤器返回
     */
    public void transportTerminated(Attributes transportAttrs) {
    }
}
