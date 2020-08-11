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

package io.grpc;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Interface to initiate processing of incoming remote calls. Advanced applications and generated
 * code will implement this interface to allows {@link Server}s to invoke service methods.
 * <p>
 * 用于启动处理远程调用的接口，实现接口的方法允许调用服务方法
 */
@ThreadSafe
public interface ServerCallHandler<RequestT, ResponseT> {
    /**
     * Produce a non-{@code null} listener for the incoming call. Implementations are free to call
     * methods on {@code call} before this method has returned.
     * 为即将发生的调用生成一个非空的监听器，在方法自己返回前，实现可以在 ServerCall 方法中自由调用
     *
     * <p>Since {@link Metadata} is not thread-safe, the caller must not access (read or write) {@code
     * headers} after this point.
     * Metadata 不是线程安全的，所以调用方在调用之后不允许读写 headers
     *
     * <p>If the implementation throws an exception, {@code call} will be closed with an error.
     * Implementations must not throw an exception if they started processing that may use {@code
     * call} on another thread.
     * 如果实现抛出异常，ServerCall 将会以错误关闭，如果在另一个线程中使用 ServerCall 开始处理，则实现不能抛出异常
     *
     * @param call object for responding to the remote client.
     *             用于响应远程客户端的对象
     * @return listener for processing incoming request messages for {@code call}
     * 返回处理即将发生的 ServerCall 接收的信息的监听器
     */
    ServerCall.Listener<RequestT> startCall(ServerCall<RequestT, ResponseT> call, Metadata headers);
}
