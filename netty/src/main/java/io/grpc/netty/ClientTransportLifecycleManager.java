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

import io.grpc.Status;
import io.grpc.internal.ManagedClientTransport;

/**
 * Maintainer of transport lifecycle status.
 * Transport 生命周期状态维护
 */
final class ClientTransportLifecycleManager {

    private final ManagedClientTransport.Listener listener;

    private boolean transportReady;
    private boolean transportShutdown;
    private boolean transportInUse;

    /**
     * null iff !transportShutdown.
     * 当 Transport 不是 Shutdown 状态时为 null
     */
    private Status shutdownStatus;
    /**
     * null iff !transportShutdown.
     * 当 Transport 不是 Shutdown 状态时为 null
     */
    private Throwable shutdownThrowable;
    private boolean transportTerminated;

    public ClientTransportLifecycleManager(ManagedClientTransport.Listener listener) {
        this.listener = listener;
    }

    /**
     * 通知 Transport READY 了
     */
    public void notifyReady() {
        // 如果已经是 Ready 或者处于 SHUTDOWN 状态，则返回
        if (transportReady || transportShutdown) {
            return;
        }
        transportReady = true;
        listener.transportReady();
    }

    /**
     * Marks transport as shutdown, but does not set the error status. This must eventually be
     * followed by a call to notifyShutdown.
     * 标记 Transport SHUTDOWN，不设置错误状态，必须在 notifyShutdown 中调用
     */
    public void notifyGracefulShutdown(Status s) {
        if (transportShutdown) {
            return;
        }
        transportShutdown = true;
        listener.transportShutdown(s);
    }

    /**
     * 根据所给的状态关闭 Transport
     */
    public void notifyShutdown(Status s) {
        notifyGracefulShutdown(s);
        if (shutdownStatus != null) {
            return;
        }
        shutdownStatus = s;
        shutdownThrowable = s.asException();
    }

    /**
     * 修改使用中状态
     */
    public void notifyInUse(boolean inUse) {
        if (inUse == transportInUse) {
            return;
        }
        transportInUse = inUse;
        listener.transportInUse(inUse);
    }

    /**
     * 终止 Transport
     */
    public void notifyTerminated(Status s) {
        if (transportTerminated) {
            return;
        }
        transportTerminated = true;
        notifyShutdown(s);
        listener.transportTerminated();
    }

    /**
     * 获取关闭状态
     */
    public Status getShutdownStatus() {
        return shutdownStatus;
    }

    /**
     * 获取关闭时异常
     */
    public Throwable getShutdownThrowable() {
        return shutdownThrowable;
    }
}
