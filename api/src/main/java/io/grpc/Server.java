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
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Server for listening for and dispatching incoming calls. It is not expected to be implemented by
 * application code or interceptors.
 * 用于监听和调度调用请求的服务器，不要求应用代码或拦截器实现
 */
@ThreadSafe
public abstract class Server {

    /**
     * Key for accessing the {@link Server} instance inside server RPC {@link Context}. It's
     * unclear to us what users would need. If you think you need to use this, please file an
     * issue for us to discuss a public API.
     * 请求中用于访问 Server 实例的 Context 的键
     */
    static final Context.Key<Server> SERVER_CONTEXT_KEY = Context.key("io.grpc.Server");

    /**
     * Bind and start the server.  After this call returns, clients may begin connecting to the
     * listening socket(s).
     * <p>
     * 绑定并启动 Server，当这个调用返回后，客户端可能会开始连接到监听的 socket 上
     *
     * @return {@code this} object
     * @throws IllegalStateException if already started
     * @throws IOException           if unable to bind
     * @since 1.0.0
     */
    public abstract Server start() throws IOException;

    /**
     * Returns the port number the server is listening on.  This can return -1 if there is no actual
     * port or the result otherwise does not make sense.  Result is undefined after the server is
     * terminated.  If there are multiple possible ports, this will return one arbitrarily.
     * Implementations are encouraged to return the same port on each call.
     * 返回 Server 监听的端口，如果没有真正监听的端口或者没有意义，会返回 -1，当 Server 终止后，结果是未定义的，
     * 如果有多个可能的端口，会任意返回一个，实现每次调用应当返回一致
     *
     * @throws IllegalStateException if the server has not yet been started.
     * @see #getListenSockets()
     * @since 1.0.0
     */
    public int getPort() {
        return -1;
    }

    /**
     * Returns a list of listening sockets for this server.  May be different than the originally
     * requested sockets (e.g. listening on port '0' may end up listening on a different port).
     * The list is unmodifiable.
     * 返回 Server 所有监听的地址，可能和原始的要求的 Socket 不同，如监听 0 可能会返回不同的端口，返回的集合是不可变的
     *
     * @throws IllegalStateException if the server has not yet been started.
     * @since 1.19.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/5332")
    public List<? extends SocketAddress> getListenSockets() {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns all services registered with the server, or an empty list if not supported by the
     * implementation.
     * 返回 Server 所有注册的服务，如果实现不支持则返回一个空集合
     *
     * @since 1.1.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
    public List<ServerServiceDefinition> getServices() {
        return Collections.emptyList();
    }

    /**
     * Returns immutable services registered with the server, or an empty list if not supported by the
     * implementation.
     * 返回 Server 注册的服务的不可变集合，如果实现不支持则返回一个空集合
     *
     * @since 1.1.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
    public List<ServerServiceDefinition> getImmutableServices() {
        return Collections.emptyList();
    }


    /**
     * Returns mutable services registered with the server, or an empty list if not supported by the
     * implementation.
     * 返回 Server 注册的服务的可变集合，如果实现不支持则返回一个空集合
     *
     * @since 1.1.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
    public List<ServerServiceDefinition> getMutableServices() {
        return Collections.emptyList();
    }

    /**
     * Initiates an orderly shutdown in which preexisting calls continue but new calls are rejected.
     * After this call returns, this server has released the listening socket(s) and may be reused by
     * another server.
     * 开始顺序的关闭，已经存在的请求会继续，新的请求将被拒绝，当这个方法返回后， Server 已经释放了监听的 Socket，可能
     * 会被另一个 Server 使用
     *
     * <p>Note that this method will not wait for preexisting calls to finish before returning.
     * {@link #awaitTermination()} or {@link #awaitTermination(long, TimeUnit)} needs to be called to
     * wait for existing calls to finish.
     * 注意这个方法返回前不会等待已经存在的请求完成，需要调用 awaitTermination() 或 awaitTermination(long, TimeUnit)
     * 等待请求完成
     *
     * @return {@code this} object
     * @since 1.0.0
     */
    public abstract Server shutdown();

    /**
     * Initiates a forceful shutdown in which preexisting and new calls are rejected. Although
     * forceful, the shutdown process is still not instantaneous; {@link #isTerminated()} will likely
     * return {@code false} immediately after this method returns. After this call returns, this
     * server has released the listening socket(s) and may be reused by another server.
     * 开始强制的关闭 Server，已经存在的请求和新的请求都会被拒绝，尽管是强制的，但是并不会瞬间关闭，当这个方法返回后，
     * isTerminated() 会返回 false，返回后，Server 已经释放了监听的 Socket，可能会被另一个 Server 使用
     *
     * @return {@code this} object
     * @since 1.0.0
     */
    public abstract Server shutdownNow();

    /**
     * Returns whether the server is shutdown. Shutdown servers reject any new calls, but may still
     * have some calls being processed.
     * 返回 Server 是否关闭了，关闭的 Server 会拒绝新的请求，但是可能会继续处理已经存在的请求
     *
     * @see #shutdown()
     * @see #isTerminated()
     * @since 1.0.0
     */
    public abstract boolean isShutdown();

    /**
     * Returns whether the server is terminated. Terminated servers have no running calls and
     * relevant resources released (like TCP connections).
     * 返回 Server 是否被终止，终止的服务没有执行的请求，相关的资源已经被释放
     *
     * @see #isShutdown()
     * @since 1.0.0
     */
    public abstract boolean isTerminated();

    /**
     * Waits for the server to become terminated, giving up if the timeout is reached.
     * 等待 Server 变为终止，如果超时则放弃
     *
     * @return whether the server is terminated, as would be done by {@link #isTerminated()}.
     * 是否终止可以通过 isTerminated() 获取
     */
    public abstract boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Waits for the server to become terminated.
     * 等待 Server 变为终止
     *
     * @since 1.0.0
     */
    public abstract void awaitTermination() throws InterruptedException;
}
