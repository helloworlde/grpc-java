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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.CallOptions;
import io.grpc.Context;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * A client transport that queues requests before a real transport is available. When {@link
 * #reprocess} is called, this class applies the provided {@link SubchannelPicker} to pick a
 * transport for each pending stream.
 *
 * <p>This transport owns every stream that it has created until a real transport has been picked
 * for that stream, at which point the ownership of the stream is transferred to the real transport,
 * thus the delayed transport stops owning the stream.
 */
final class DelayedClientTransport implements ManagedClientTransport {
  static final Logger logger = Logger.getLogger(DelayedClientTransport.class.getName());

  // lazily allocated, since it is infrequently used.
  private final InternalLogId logId =
      InternalLogId.allocate(DelayedClientTransport.class, /*details=*/ null);

  private final Object lock = new Object();

  private final Executor defaultAppExecutor;
  private final SynchronizationContext syncContext;

  private Runnable reportTransportInUse;
  private Runnable reportTransportNotInUse;
  private Runnable reportTransportTerminated;
  private Listener listener;

  @Nonnull
  @GuardedBy("lock")
  private Collection<PendingStream> pendingStreams = new LinkedHashSet<>();

  /**
   * When {@code shutdownStatus != null && !hasPendingStreams()}, then the transport is considered
   * terminated.
   */
  @GuardedBy("lock")
  private Status shutdownStatus;

  /**
   * The last picker that {@link #reprocess} has used. May be set to null when the channel has moved
   * to idle.
   */
  @GuardedBy("lock")
  @Nullable
  private SubchannelPicker lastPicker;

  @GuardedBy("lock")
  private long lastPickerVersion;

  /**
   * Creates a new delayed transport.
   *
   * @param defaultAppExecutor pending streams will create real streams and run bufferred operations
   *        in an application executor, which will be this executor, unless there is on provided in
   *        {@link CallOptions}.
   * @param syncContext all listener callbacks of the delayed transport will be run from this
   *        SynchronizationContext.
   */
  DelayedClientTransport(Executor defaultAppExecutor, SynchronizationContext syncContext) {
    this.defaultAppExecutor = defaultAppExecutor;
    this.syncContext = syncContext;
  }

  @Override
  public final Runnable start(final Listener listener) {
    this.listener = listener;
    reportTransportInUse = new Runnable() {
        @Override
        public void run() {
          listener.transportInUse(true);
        }
      };
    reportTransportNotInUse = new Runnable() {
        @Override
        public void run() {
          listener.transportInUse(false);
        }
      };
    reportTransportTerminated = new Runnable() {
        @Override
        public void run() {
          listener.transportTerminated();
        }
      };
    return null;
  }

  /**
   * 创建请求的流
   * If a {@link SubchannelPicker} is being, or has been provided via {@link #reprocess}, the last
   * picker will be consulted.
   * 如果正在使用 SubchannelPicker 或者已经通过 reprocess 提供了 SubchannelPicker 则将查询最后一个*选择器。
   *
   * <p>Otherwise, if the delayed transport is not shutdown, then a {@link PendingStream} is
   * returned; if the transport is shutdown, then a {@link FailingClientStream} is returned.
   * <p>
   * 除此之外，如果 delayed transport 没有关闭，会返回 PendingStream，如果关闭了，则返回 FailingClientStream
   */
  @Override
  public final ClientStream newStream(MethodDescriptor<?, ?> method,
                                      Metadata headers,
                                      CallOptions callOptions) {
    logger.warning("==> io.grpc.internal.DelayedClientTransport#newStream");
    logger.info("创建新的流");
    try {
      // 创建 subchannel 选择参数
      PickSubchannelArgs args = new PickSubchannelArgsImpl(method, headers, callOptions);
      SubchannelPicker picker = null;
      long pickerVersion = -1;
      while (true) {
        synchronized (lock) {
          // 如果关闭，则返回关闭状态
          if (shutdownStatus != null) {
            return new FailingClientStream(shutdownStatus);
          }
          // 如果选择器是空的，则创建等待处理的流
          if (lastPicker == null) {
            return createPendingStream(args);
          }
          // 如果选择器没有变化，则创建等待处理的流
          // Check for second time through the loop, and whether anything changed
          if (picker != null && pickerVersion == lastPickerVersion) {
            return createPendingStream(args);
          }
          picker = lastPicker;
          pickerVersion = lastPickerVersion;
        }

        // 选择 subchannel
        logger.info("pickSubchannel 选择可用的 Subchannel");
        PickResult pickResult = picker.pickSubchannel(args);
        // 选择 Transport
        ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult, callOptions.isWaitForReady());
        if (transport != null) {
          // 创建流
          return transport.newStream(args.getMethodDescriptor(),
                  args.getHeaders(),
                  args.getCallOptions());
        }
        // This picker's conclusion is "buffer".  If there hasn't been a newer picker set (possible
        // race with reprocess()), we will buffer it.  Otherwise, will try with the new picker.
      }
    } finally {
      syncContext.drain();
    }
  }

  /**
   * 创建等待处理的流
   * Caller must call {@code syncContext.drain()} outside of lock because this method may
   * schedule tasks on syncContext.
   */
  @GuardedBy("lock")
  private PendingStream createPendingStream(PickSubchannelArgs args) {
    logger.warning("==> io.grpc.internal.DelayedClientTransport#createPendingStream");
    PendingStream pendingStream = new PendingStream(args);
    pendingStreams.add(pendingStream);
    if (getPendingStreamsCount() == 1) {
      logger.info("创建并提交等待稍后执行的流");
      syncContext.executeLater(reportTransportInUse);
    }
    return pendingStream;
  }

  @Override
  public final void ping(final PingCallback callback, Executor executor) {
    throw new UnsupportedOperationException("This method is not expected to be called");
  }

  @Override
  public ListenableFuture<SocketStats> getStats() {
    SettableFuture<SocketStats> ret = SettableFuture.create();
    ret.set(null);
    return ret;
  }

  /**
   * Prevents creating any new streams.  Buffered streams are not failed and may still proceed
   * when {@link #reprocess} is called.  The delayed transport will be terminated when there is no
   * more buffered streams.
   */
  @Override
  public final void shutdown(final Status status) {
    synchronized (lock) {
      if (shutdownStatus != null) {
        return;
      }
      shutdownStatus = status;
      syncContext.executeLater(new Runnable() {
          @Override
          public void run() {
            listener.transportShutdown(status);
          }
        });
      if (!hasPendingStreams() && reportTransportTerminated != null) {
        syncContext.executeLater(reportTransportTerminated);
        reportTransportTerminated = null;
      }
    }
    syncContext.drain();
  }

  /**
   * Shuts down this transport and cancels all streams that it owns, hence immediately terminates
   * this transport.
   */
  @Override
  public final void shutdownNow(Status status) {
    shutdown(status);
    Collection<PendingStream> savedPendingStreams;
    Runnable savedReportTransportTerminated;
    synchronized (lock) {
      savedPendingStreams = pendingStreams;
      savedReportTransportTerminated = reportTransportTerminated;
      reportTransportTerminated = null;
      if (!pendingStreams.isEmpty()) {
        pendingStreams = Collections.emptyList();
      }
    }
    if (savedReportTransportTerminated != null) {
      for (PendingStream stream : savedPendingStreams) {
        stream.cancel(status);
      }
      syncContext.execute(savedReportTransportTerminated);
    }
    // If savedReportTransportTerminated == null, transportTerminated() has already been called in
    // shutdown().
  }

  public final boolean hasPendingStreams() {
    synchronized (lock) {
      return !pendingStreams.isEmpty();
    }
  }

  @VisibleForTesting
  final int getPendingStreamsCount() {
    synchronized (lock) {
      return pendingStreams.size();
    }
  }

  /**
   * 设置选择器，重新创建 Transport，处理流
   * <p>
   * Use the picker to try picking a transport for every pending stream, proceed the stream if the
   * pick is successful, otherwise keep it pending.
   * <p>
   * 使用选择器尝试为每个等待的流选择 Transport，如果选择成功则处理刘，如果失败则继续等待
   *
   * <p>This method may be called concurrently with {@code newStream()}, and it's safe.  All pending
   * streams will be served by the latest picker (if a same picker is given more than once, they are
   * considered different pickers) as soon as possible.
   * 所有流都会尽可能的被最后一个选择器处理
   *
   * <p>This method <strong>must not</strong> be called concurrently with itself.
   */
  final void reprocess(@Nullable SubchannelPicker picker) {
    ArrayList<PendingStream> toProcess;
    synchronized (lock) {
      lastPicker = picker;
      lastPickerVersion++;
      if (picker == null || !hasPendingStreams()) {
        return;
      }
      toProcess = new ArrayList<>(pendingStreams);
    }

    ArrayList<PendingStream> toRemove = new ArrayList<>();

    //遍历待处理的流
    for (final PendingStream stream : toProcess) {
      // 重新选择 subchannel
      PickResult pickResult = picker.pickSubchannel(stream.args);
      // 调用参数
      CallOptions callOptions = stream.args.getCallOptions();
      // 获取 Transport
      final ClientTransport transport = GrpcUtil.getTransportFromPickResult(pickResult, callOptions.isWaitForReady());
      if (transport != null) {
        Executor executor = defaultAppExecutor;
        // createRealStream may be expensive. It will start real streams on the transport. If
        // there are pending requests, they will be serialized too, which may be expensive. Since
        // we are now on transport thread, we need to offload the work to an executor.
        if (callOptions.getExecutor() != null) {
          executor = callOptions.getExecutor();
        }
        // 创建流
        executor.execute(new Runnable() {
          @Override
          public void run() {
            // 创建流，建立连接
            // 第一个执行的任务
            stream.createRealStream(transport);
          }
        });
        // 从待处理中移除
        toRemove.add(stream);
      }  // else: stay pending
    }

    synchronized (lock) {
      // Between this synchronized and the previous one:
      //   - Streams may have been cancelled, which may turn pendingStreams into emptiness.
      //   - shutdown() may be called, which may turn pendingStreams into null.
      // 如果没有待处理的流，则返回
      if (!hasPendingStreams()) {
        return;
      }
      // 将处理过的流移除
      pendingStreams.removeAll(toRemove);
      // Because delayed transport is long-lived, we take this opportunity to down-size the
      // hashmap.
      if (pendingStreams.isEmpty()) {
        pendingStreams = new LinkedHashSet<>();
      }

      if (!hasPendingStreams()) {
        // There may be a brief gap between delayed transport clearing in-use state, and first real
        // transport starting streams and setting in-use state.  During the gap the whole channel's
        // in-use state may be false. However, it shouldn't cause spurious switching to idleness
        // (which would shutdown the transports and LoadBalancer) because the gap should be shorter
        // than IDLE_MODE_DEFAULT_TIMEOUT_MILLIS (1 second).
        syncContext.executeLater(reportTransportNotInUse);
        if (shutdownStatus != null && reportTransportTerminated != null) {
          syncContext.executeLater(reportTransportTerminated);
          reportTransportTerminated = null;
        }
      }
    }
    syncContext.drain();
  }

  @Override
  public InternalLogId getLogId() {
    return logId;
  }

  private class PendingStream extends DelayedStream {
    private final PickSubchannelArgs args;
    private final Context context = Context.current();

    private PendingStream(PickSubchannelArgs args) {
      this.args = args;
    }

    /**
     * 创建流
     * @param transport
     */
    private void createRealStream(ClientTransport transport) {
      ClientStream realStream;
      Context origContext = context.attach();
      try {
        // 创建流
        realStream = transport.newStream(args.getMethodDescriptor(), args.getHeaders(), args.getCallOptions());
      } finally {
        context.detach(origContext);
      }
      // 设置流
      setStream(realStream);
    }

    @Override
    public void cancel(Status reason) {
      super.cancel(reason);
      synchronized (lock) {
        if (reportTransportTerminated != null) {
          boolean justRemovedAnElement = pendingStreams.remove(this);
          if (!hasPendingStreams() && justRemovedAnElement) {
            syncContext.executeLater(reportTransportNotInUse);
            if (shutdownStatus != null) {
              syncContext.executeLater(reportTransportTerminated);
              reportTransportTerminated = null;
            }
          }
        }
      }
      syncContext.drain();
    }
  }
}
