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

package io.grpc.internal;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Manages connectivity states of the channel. Used for {@link ManagedChannel#getState} to read the
 * current state of the channel, for {@link ManagedChannel#notifyWhenStateChanged} to add
 * listeners to state change events, and for {@link io.grpc.LoadBalancer.Helper#updateBalancingState
 * LoadBalancer.Helper#updateBalancingState} to update the state and run the {@link #gotoState}s.
 * 管理 Channel 的连接状态，用于 ManagedChannel#getState 读取当前 Channel 的状态， ManagedChannel#notifyWhenStateChanged
 * 添加监听器监听变化事件，LoadBalancer.Helper#updateBalancingState 更新状态
 */
@NotThreadSafe
final class ConnectivityStateManager {
    private ArrayList<Listener> listeners = new ArrayList<>();

    private volatile ConnectivityState state = ConnectivityState.IDLE;

    /**
     * Adds a listener for state change event.
     * 添加监听器
     *
     * <p>The {@code executor} must be one that can run RPC call listeners.
     */
    void notifyWhenStateChanged(Runnable callback, Executor executor, ConnectivityState source) {
        checkNotNull(callback, "callback");
        checkNotNull(executor, "executor");
        checkNotNull(source, "source");

        // 创建监听器
        Listener stateChangeListener = new Listener(callback, executor);
        // 如果状态发生了变化，则调用 callback
        if (state != source) {
            stateChangeListener.runInExecutor();
        } else {
            // 如果状态一致，则添加到监听器集合中
            listeners.add(stateChangeListener);
        }
    }

    /**
     * Connectivity state is changed to the specified value. Will trigger some notifications that have
     * been registered earlier by {@link ManagedChannel#notifyWhenStateChanged}.
     * 将连接状态改为指定的值，会触发已经注册的监听器的调用
     */
    void gotoState(@Nonnull ConnectivityState newState) {
        checkNotNull(newState, "newState");
        if (state != newState && state != ConnectivityState.SHUTDOWN) {
            state = newState;
            if (listeners.isEmpty()) {
                return;
            }
            // Swap out callback list before calling them, because a callback may register new callbacks,
            // if run in direct executor, can cause ConcurrentModificationException.
            // 将已经存在的监听器交换出来，防止新提交的直接执行时产生 ConcurrentModificationException
            ArrayList<Listener> savedListeners = listeners;
            listeners = new ArrayList<>();
            // 执行所有的监听器回调
            for (Listener listener : savedListeners) {
                listener.runInExecutor();
            }
        }
    }

    /**
     * Gets the current connectivity state of the channel. This method is threadsafe.
     * 返回当前 Channel 的连接状态，方法是线程安全的
     */
    ConnectivityState getState() {
        ConnectivityState stateCopy = state;
        if (stateCopy == null) {
            throw new UnsupportedOperationException("Channel state API is not implemented");
        }
        return stateCopy;
    }

    /**
     * 监听器
     */
    private static final class Listener {
        final Runnable callback;
        final Executor executor;

        Listener(Runnable callback, Executor executor) {
            this.callback = callback;
            this.executor = executor;
        }

        void runInExecutor() {
            executor.execute(callback);
        }
    }
}
