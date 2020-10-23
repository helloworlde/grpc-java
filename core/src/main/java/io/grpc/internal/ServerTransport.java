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

package io.grpc.internal;

import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.Status;

import java.util.concurrent.ScheduledExecutorService;

/**
 * An inbound connection.
 * 接收请求的连接
 */
public interface ServerTransport extends InternalInstrumented<SocketStats> {
    /**
     * Initiates an orderly shutdown of the transport. Existing streams continue, but new streams will
     * eventually begin failing. New streams "eventually" begin failing because shutdown may need to
     * be processed on a separate thread. May only be called once.
     * <p>
     * 开始顺序的关闭 Transport，已经存在的流会继续执行, 但是新的请求最终会失败，因为关闭可能要在单独的线程执行，
     * 只能被调用一次
     */
    void shutdown();

    /**
     * Initiates a forceful shutdown in which preexisting and new calls are closed. Existing calls
     * should be closed with the provided {@code reason}.
     * <p>
     * 开始强制的关闭，已经存在的和新的请求都会失败，存在的请求会使用给定的原因关闭
     */
    void shutdownNow(Status reason);

    /**
     * Returns an executor for scheduling provided by the transport. The service should be configured
     * to allow cancelled scheduled runnables to be GCed.
     * 返回 Transport 提供的用于调度的线程池，这个线程池应该配置为允许取消已经调度的任务以便 GC
     *
     * <p>The executor may not be used after the transport terminates. The caller should ensure any
     * outstanding tasks are cancelled when the transport terminates.
     * 当 Transport 终止后线程池不会再被使用，调用者应该在 Transport 终止时确认所有的任务都取消了
     */
    ScheduledExecutorService getScheduledExecutorService();
}
