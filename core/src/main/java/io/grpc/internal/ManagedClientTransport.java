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

import io.grpc.Status;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A {@link ClientTransport} that has life-cycle management.
 *
 * <p>{@link #start} must be the first method call to this interface and return before calling other
 * methods.
 *
 * <p>Typically the transport owns the streams it creates through {@link #newStream}, while some
 * implementations may transfer the streams to somewhere else. Either way they must conform to the
 * contract defined by {@link #shutdown}, {@link Listener#transportShutdown} and
 * {@link Listener#transportTerminated}.
 */
@ThreadSafe
public interface ManagedClientTransport extends ClientTransport {

  /**
   * Starts transport. This method may only be called once.
   * 开始 Transport，这个方法只能调用一次
   *
   * <p>Implementations must not call {@code listener} from within {@link #start}; implementations
   * are expected to notify listener on a separate thread or when the returned {@link Runnable} is
   * run. This method and the returned {@code Runnable} should not throw any exceptions.
   * 实现不能从这个方法里调用 Listener 的 start 方法，期望在返回 Runnable 时通过独立的线程通知，
   * 这个方法和返回的 Runnable 不应当返回异常
   *
   * @param listener non-{@code null} listener of transport events
   *                 非空的 Transport 事件监听器
   * @return a {@link Runnable} that is executed after-the-fact by the original caller, typically
   * after locks are released 返回在原始调用之后执行的 Runnable，通常是在加锁或者释放后
   */
  @CheckReturnValue
  @Nullable
  Runnable start(Listener listener);

  /**
   * Initiates an orderly shutdown of the transport.  Existing streams continue, but the transport
   * will not own any new streams.  New streams will either fail (once
   * {@link Listener#transportShutdown} callback called), or be transferred off this transport (in
   * which case they may succeed).  This method may only be called once.
   */
  void shutdown(Status reason);

  /**
   * Initiates a forceful shutdown in which preexisting and new calls are closed. Existing calls
   * should be closed with the provided {@code reason}.
   */
  void shutdownNow(Status reason);

  /**
   * Receives notifications for the transport life-cycle events. Implementation does not need to be
   * thread-safe, so notifications must be properly synchronized externally.
   */
  interface Listener {
    /**
     * The transport is shutting down. This transport will stop owning new streams, but existing
     * streams may continue, and the transport may still be able to process {@link #newStream} as
     * long as it doesn't own the new streams. Shutdown could have been caused by an error or normal
     * operation.  It is possible that this method is called without {@link #shutdown} being called.
     *
     * <p>This is called exactly once, and must be called prior to {@link #transportTerminated}.
     *
     * @param s the reason for the shutdown.
     */
    void transportShutdown(Status s);

    /**
     * The transport completed shutting down. All resources have been released. All streams have
     * either been closed or transferred off this transport. This transport may still be able to
     * process {@link #newStream} as long as it doesn't own the new streams.
     *
     * <p>This is called exactly once, and must be called after {@link #transportShutdown} has been
     * called.
     */
    void transportTerminated();

    /**
     * The transport is ready to accept traffic, because the connection is established.  This is
     * called at most once.
     * Transport 已经准备就绪可以接收流量，此时连接已经建立，这个方法最多调用一次
     */
    void transportReady();

    /**
     * Called whenever the transport's in-use state has changed. A transport is in-use when it has
     * at least one stream.
     */
    void transportInUse(boolean inUse);
  }
}
