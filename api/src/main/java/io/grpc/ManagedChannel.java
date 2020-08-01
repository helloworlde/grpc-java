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

import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Channel} that provides lifecycle management.
 * 提供生命周期管理的 Channel
 */
@ThreadSafe
public abstract class ManagedChannel extends Channel {
    /**
     * Initiates an orderly shutdown in which preexisting calls continue but new calls are immediately
     * cancelled.
     * 初始化一个顺序的关闭，既有的调用会继续执行，但是新的调用会被立即取消
     *
     * @return this
     * @since 1.0.0
     */
    public abstract ManagedChannel shutdown();

    /**
     * Returns whether the channel is shutdown. Shutdown channels immediately cancel any new calls,
     * but may still have some calls being processed.
     * 返回 Channel 是否关闭，关闭的 Channel 会立即取消新的调用，但是可能会继续执行已有的调用
     *
     * @see #shutdown()
     * @see #isTerminated()
     * @since 1.0.0
     */
    public abstract boolean isShutdown();

    /**
     * Returns whether the channel is terminated. Terminated channels have no running calls and
     * relevant resources released (like TCP connections).
     * 返回 Channel 是否被终止，终止的 Channel 没有执行中的调用，相关的资源被释放(如 TCP 连接)
     *
     * @see #isShutdown()
     * @since 1.0.0
     */
    public abstract boolean isTerminated();

    /**
     * Initiates a forceful shutdown in which preexisting and new calls are cancelled. Although
     * forceful, the shutdown process is still not instantaneous; {@link #isTerminated()} will likely
     * return {@code false} immediately after this method returns.
     * 初始化一个强制的关闭，会取消所有的调用，即使是强制关闭，也不是瞬间停止，这个方法调用后 isTerminated
     * 方法可能会立即返回 false
     *
     * @return this
     * @since 1.0.0
     */
    public abstract ManagedChannel shutdownNow();

    /**
     * Waits for the channel to become terminated, giving up if the timeout is reached.
     * 等待 Channel 变为终止，如果超时则放弃等待
     *
     * @return whether the channel is terminated, as would be done by {@link #isTerminated()}.
     * 返回 Channel 是否终止
     * @since 1.0.0
     */
    public abstract boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * Gets the current connectivity state. Note the result may soon become outdated.
     * 获取当前连接状态，需要注意结果可能会很快过时
     *
     * <p>Note that the core library did not provide an implementation of this method until v1.6.1.
     * 核心库在 1.6.1 之前，这个类没有提供实现
     *
     * @param requestConnection if {@code true}, the channel will try to make a connection if it is
     *                          currently IDLE
     *                          如果是true，且当前是 IDLE 模式，则 Channel 会尝试创建连接
     * @throws UnsupportedOperationException if not supported by implementation
     * @since 1.1.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4359")
    public ConnectivityState getState(boolean requestConnection) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Registers a one-off callback that will be run if the connectivity state of the channel diverges
     * from the given {@code source}, which is typically what has just been returned by {@link
     * #getState}.  If the states are already different, the callback will be called immediately.  The
     * callback is run in the same executor that runs Call listeners.
     * 注册一次性的回调，当 Channel 状态和给定的不同时触发，状态通常由 getState 返回，如果状态已经不同，则回调会立即
     * 调用，回调在与调用监听器相同的线程池中运行
     *
     * <p>There is an inherent race between the notification to {@code callback} and any call to
     * {@code getState()}. There is a similar race between {@code getState()} and a call to {@code
     * notifyWhenStateChanged()}. The state can change during those races, so there is not a way to
     * see every state transition. "Transitions" to the same state are possible, because intermediate
     * states may not have been observed. The API is only reliable in tracking the <em>current</em>
     * state.
     * callback 回调和调用 getState 之间有竞争，getState 和 notifyWhenStateChanged 调用之间有同样的竞争，
     * 状态可能在竞争中发生改变，因此没有一种方法可以查看每个状态转换，因为可能未观察到中间*状态，所以可能"过渡"到相同状态
     * 该 API 仅在并发状态中可靠
     *
     * <p>Note that the core library did not provide an implementation of this method until v1.6.1.
     * 核心库在 1.6.1 之前没有提供实现
     *
     * @param source   the assumed current state, typically just returned by {@link #getState}
     *                 假定的当前状态，通常是由 getState 返回的
     * @param callback the one-off callback
     *                 一次性的回调
     * @throws UnsupportedOperationException if not supported by implementation
     * @since 1.1.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4359")
    public void notifyWhenStateChanged(ConnectivityState source, Runnable callback) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * For subchannels that are in TRANSIENT_FAILURE state, short-circuit the backoff timer and make
     * them reconnect immediately. May also attempt to invoke {@link NameResolver#refresh}.
     * 对于 TRANSIENT_FAILURE 状态的 Subchannel，使退避计时器短路，并立即重新连接，也会尝试调用 NameResolver#refresh
     *
     *
     * <p>This is primarily intended for Android users, where the network may experience frequent
     * temporary drops. Rather than waiting for gRPC's name resolution and reconnect timers to elapse
     * before reconnecting, the app may use this method as a mechanism to notify gRPC that the network
     * is now available and a reconnection attempt may occur immediately.
     * 主要针对 Android 用户，网络可能会出现频繁掉线，与其等待 gRPC 的名称解析和重新连接计时器在重新连接之前，
     * 应用程序可能使用此方法作为通知 gRPC 的机制，网络现在可用，并且可能立即发生重新连接尝试
     *
     * <p>No-op if not supported by the implementation.
     *
     * @since 1.8.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4056")
    public void resetConnectBackoff() {
    }

    /**
     * Invoking this method moves the channel into the IDLE state and triggers tear-down of the
     * channel's name resolver and load balancer, while still allowing on-going RPCs on the channel to
     * continue. New RPCs on the channel will trigger creation of a new connection.
     * 调用这个方法会将 Channel 变为 IDLE 状态，触发 Channel 的名称解析和负载均衡拆除，Channel 上执行中的请求会
     * 继续执行，新的 RPC 请求触发建立新的连接
     *
     * <p>This is primarily intended for Android users when a device is transitioning from a cellular
     * to a wifi connection. The OS will issue a notification that a new network (wifi) has been made
     * the default, but for approximately 30 seconds the device will maintain both the cellular
     * and wifi connections. Apps may invoke this method to ensure that new RPCs are created using the
     * new default wifi network, rather than the soon-to-be-disconnected cellular network.
     * 主要用于 Android，当设备从蜂窝过渡到 wifi 连接时，OS 会通知 WiFi 网络被设置为默认的，30s内应用会保持蜂窝
     * 和 WiFi 同时连接，应用调用这个方法确保新的请求会使用默认的 WiFi 创建，而不是使用即将断开的蜂窝
     *
     * <p>No-op if not supported by implementation.
     *
     * @since 1.11.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4056")
    public void enterIdle() {
    }
}
