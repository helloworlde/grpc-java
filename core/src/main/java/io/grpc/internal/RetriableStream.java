/*
 * Copyright 2017 The gRPC Authors
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
import com.google.common.base.Objects;
import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.Compressor;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

import javax.annotation.CheckForNull;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * A logical {@link ClientStream} that is retriable.
 * 支持重试的逻辑 ClientStream
 */
abstract class RetriableStream<ReqT> implements ClientStream {
  static final Logger logger = Logger.getLogger(RetriableStream.class.getName());

  @VisibleForTesting
  static final Metadata.Key<String> GRPC_PREVIOUS_RPC_ATTEMPTS =
      Metadata.Key.of("grpc-previous-rpc-attempts", Metadata.ASCII_STRING_MARSHALLER);

  @VisibleForTesting
  static final Metadata.Key<String> GRPC_RETRY_PUSHBACK_MS =
      Metadata.Key.of("grpc-retry-pushback-ms", Metadata.ASCII_STRING_MARSHALLER);

  private static final Status CANCELLED_BECAUSE_COMMITTED =
      Status.CANCELLED.withDescription("Stream thrown away because RetriableStream committed");

  private final MethodDescriptor<ReqT, ?> method;
  private final Executor callExecutor;
  private final ScheduledExecutorService scheduledExecutorService;
  // Must not modify it.
  private final Metadata headers;
  // 重试策略提供器
  private final RetryPolicy.Provider retryPolicyProvider;
  private final HedgingPolicy.Provider hedgingPolicyProvider;
  private RetryPolicy retryPolicy;
  private HedgingPolicy hedgingPolicy;
  private boolean isHedging;

  /** Must be held when updating state, accessing state.buffer, or certain substream attributes. */
  private final Object lock = new Object();

  private final ChannelBufferMeter channelBufferUsed;
  private final long perRpcBufferLimit;
  private final long channelBufferLimit;
  @Nullable
  private final Throttle throttle;
  @GuardedBy("lock")
  private final InsightBuilder closedSubstreamsInsight = new InsightBuilder();

  // 状态
  private volatile State state = new State(new ArrayList<BufferEntry>(8),
          Collections.<Substream>emptyList(),
          null,
          null,
          false,
          false,
          false,
          0);

  /**
   * Either transparent retry happened or reached server's application logic.
   * 是否还有 Transport 层透明重试的请求
   */
  private final AtomicBoolean noMoreTransparentRetry = new AtomicBoolean();

  // Used for recording the share of buffer used for the current call out of the channel buffer.
  // This field would not be necessary if there is no channel buffer limit.
  @GuardedBy("lock")
  private long perRpcBufferUsed;

  private ClientStreamListener masterListener;

  /**
   * 计划的重试
   */
  @GuardedBy("lock")
  private FutureCanceller scheduledRetry;

  /**
   * 已经计划的对冲请求
   */
  @GuardedBy("lock")
  private FutureCanceller scheduledHedging;

  /**
   * 下一次延时的时间间隔
   */
  private long nextBackoffIntervalNanos;

  /**
   * 构造可重试的流
   *
   * @param method                   调用的方法
   * @param headers                  请求头
   * @param channelBufferUsed        channel使用的buffer
   * @param perRpcBufferLimit        每个请求的Buffer限制
   * @param channelBufferLimit       channel的buffer限制
   * @param callExecutor             执行的线程池
   * @param scheduledExecutorService 线程池
   * @param retryPolicyProvider      重试策略提供器
   * @param hedgingPolicyProvider    对冲策略提供器
   * @param throttle                 节流配置
   */
  RetriableStream(MethodDescriptor<ReqT, ?> method,
                  Metadata headers,
                  ChannelBufferMeter channelBufferUsed,
                  long perRpcBufferLimit,
                  long channelBufferLimit,
                  Executor callExecutor,
                  ScheduledExecutorService scheduledExecutorService,
                  RetryPolicy.Provider retryPolicyProvider,
                  HedgingPolicy.Provider hedgingPolicyProvider,
                  @Nullable Throttle throttle) {
    this.method = method;
    this.channelBufferUsed = channelBufferUsed;
    this.perRpcBufferLimit = perRpcBufferLimit;
    this.channelBufferLimit = channelBufferLimit;
    this.callExecutor = callExecutor;
    this.scheduledExecutorService = scheduledExecutorService;
    this.headers = headers;
    this.retryPolicyProvider = checkNotNull(retryPolicyProvider, "retryPolicyProvider");
    this.hedgingPolicyProvider = checkNotNull(hedgingPolicyProvider, "hedgingPolicyProvider");
    this.throttle = throttle;
  }

  @SuppressWarnings("GuardedBy")
  @Nullable // null if already committed
  @CheckReturnValue
  private Runnable commit(final Substream winningSubstream) {
    logger.warning("==> io.grpc.internal.RetriableStream#commit");
    logger.info("提交流");
    synchronized (lock) {
      // 如果已经有流提交了，则返回 null
      if (state.winningSubstream != null) {
        return null;
      }
      final Collection<Substream> savedDrainedSubstreams = state.drainedSubstreams;

      // 没有则提交当前的流
      state = state.committed(winningSubstream);

      // subtract the share of this RPC from channelBufferUsed.
      // buffer 减去使用的
      channelBufferUsed.addAndGet(-perRpcBufferUsed);

      final Future<?> retryFuture;
      // 如果有计划中的重试，则取消
      if (scheduledRetry != null) {
        // TODO(b/145386688): This access should be guarded by 'this.scheduledRetry.lock'; instead
        // found: 'this.lock'
        logger.info("取消计划的重试");
        retryFuture = scheduledRetry.markCancelled();
        scheduledRetry = null;
      } else {
        retryFuture = null;
      }
      // cancel the scheduled hedging if it is scheduled prior to the commitment
      // 如果有计划中的对冲，则取消
      final Future<?> hedgingFuture;
      if (scheduledHedging != null) {
        // TODO(b/145386688): This access should be guarded by 'this.scheduledHedging.lock'; instead
        // found: 'this.lock'
        logger.info("取消计划的对冲");
        hedgingFuture = scheduledHedging.markCancelled();
        scheduledHedging = null;
      } else {
        hedgingFuture = null;
      }

      class CommitTask implements Runnable {
        @Override
        public void run() {
          // For hedging only, not needed for normal retry
          // 遍历保存的枯竭的流，如果不是最后提交的流，则都取消
          logger.warning("==> io.grpc.internal.RetriableStream#commit#CommitTask#run");
          logger.info("遍历保存的枯竭的流，如果不是最后提交的流，则都取消");
          for (Substream substream : savedDrainedSubstreams) {
            if (substream != winningSubstream) {
              substream.stream.cancel(CANCELLED_BECAUSE_COMMITTED);
            }
          }
          // 如果有重试中的，则取消
          if (retryFuture != null) {
            retryFuture.cancel(false);
          }
          // 如果有对冲中的，则取消
          if (hedgingFuture != null) {
            hedgingFuture.cancel(false);
          }

          // 将当前流从未提交的流中移除
          postCommit();
        }
      }

      return new CommitTask();
    }
  }

  abstract void postCommit();

  /**
   * Calls commit() and if successful runs the post commit task.
   * 当成功后调用 commit 方法
   */
  private void commitAndRun(Substream winningSubstream) {
    logger.warning("==> io.grpc.internal.RetriableStream#commitAndRun");
    logger.info("提交流并执行任务");
    Runnable postCommitTask = commit(winningSubstream);

    if (postCommitTask != null) {
      postCommitTask.run();
    }
  }

  /**
   * 开始重试流程，创建 Substream
   *
   * @param previousAttemptCount 之前重试的次数
   * @return
   */
  private Substream createSubstream(int previousAttemptCount) {
    logger.warning("==> io.grpc.internal.RetriableStream#createSubstream");
    logger.info("创建 Substream, previousAttemptCount:" + previousAttemptCount);

    // 重试流
    Substream sub = new Substream(previousAttemptCount);
    // one tracer per substream
    // 监控 Substream 用的 buffer
    final ClientStreamTracer bufferSizeTracer = new BufferSizeTracer(sub);

    // 监控工厂
    ClientStreamTracer.Factory tracerFactory = new ClientStreamTracer.Factory() {
      @Override
      public ClientStreamTracer newClientStreamTracer(ClientStreamTracer.StreamInfo info, Metadata headers) {
        return bufferSizeTracer;
      }
    };

    // 更新请求头，添加/修改重试次数
    Metadata newHeaders = updateHeaders(headers, previousAttemptCount);
    // NOTICE: This set _must_ be done before stream.start() and it actually is.
    sub.stream = newSubstream(tracerFactory, newHeaders);
    return sub;
  }

  /**
   * Creates a new physical ClientStream that represents a retry/hedging attempt. The returned
   * Client stream is not yet started.
   */
  abstract ClientStream newSubstream(
      ClientStreamTracer.Factory tracerFactory, Metadata headers);

  /** Adds grpc-previous-rpc-attempts in the headers of a retry/hedging RPC. */
  @VisibleForTesting
  final Metadata updateHeaders(Metadata originalHeaders,
                               int previousAttemptCount) {
    logger.warning("==> io.grpc.internal.RetriableStream#updateHeaders");
    logger.info("更新请求头，修改重试次数");
    Metadata newHeaders = new Metadata();
    newHeaders.merge(originalHeaders);
    if (previousAttemptCount > 0) {
      newHeaders.put(GRPC_PREVIOUS_RPC_ATTEMPTS, String.valueOf(previousAttemptCount));
    }
    return newHeaders;
  }

  /**
   * 消耗流中缓冲的请求
   * 可以理解流为水槽，event 是水，drain 相当于拔掉塞子让水流出去
   *
   * @param substream 流
   */
  private void drain(Substream substream) {
    logger.warning("==> io.grpc.internal.RetriableStream#drain");
    logger.info("消耗流中缓冲的请求");
    int index = 0;
    // 128
    int chunk = 0x80;
    List<BufferEntry> list = null;

    while (true) {
      State savedState;

      // 加锁
      synchronized (lock) {
        savedState = state;
        // 如果已经有被提交的流，且提交的流不是当前流，则退出
        if (savedState.winningSubstream != null && savedState.winningSubstream != substream) {
          // committed but not me
          break;
        }

        // 如果缓冲区满了，则将这个流添加的已经枯竭的流中并返回
        if (index == savedState.buffer.size()) { // I'm drained
          state = savedState.substreamDrained(substream);
          return;
        }

        // 如果流已经被关闭，则返回
        if (substream.closed) {
          return;
        }

        // 只能缓冲 128 个流，如果有更多的，则截断
        int stop = Math.min(index + chunk, savedState.buffer.size());
        if (list == null) {
          list = new ArrayList<>(savedState.buffer.subList(index, stop));
        } else {
          list.clear();
          list.addAll(savedState.buffer.subList(index, stop));
        }
        index = stop;
      }

      // 遍历所有缓冲的流
      for (BufferEntry bufferEntry : list) {
        savedState = state;
        // 如果请求已经提交了，且不是当前流提交的，则跳出
        if (savedState.winningSubstream != null && savedState.winningSubstream != substream) {
          // committed but not me
          break;
        }
        // 如果流被取消了，则检查是否是是当前流提交的，并跳出
        if (savedState.cancelled) {
          checkState(savedState.winningSubstream == substream, "substream should be CANCELLED_BECAUSE_COMMITTED already");
          return;
        }

        // 执行流的操作
        logger.info("执行流的操作，当前 BufferEntry:" + bufferEntry.getClass().getName());
        bufferEntry.runWith(substream);
      }
    }

    // 如果请求已经被提交，则取消
    substream.stream.cancel(CANCELLED_BECAUSE_COMMITTED);
  }

  /**
   * 执行 pre-start 任务，如果 channel 被关闭，则返回状态
   * Runs pre-start tasks. Returns the Status of shutdown if the channel is shutdown.
   */
  @CheckReturnValue
  @Nullable
  abstract Status prestart();

  /**
   * 开始第一个 RPC 调用
   * Starts the first PRC attempt.
   */
  @Override
  public final void start(ClientStreamListener listener) {
    logger.warning("==> io.grpc.internal.RetriableStream#start");
    logger.info("开始 Retriable 流");
    masterListener = listener;

    // 调用监听器的 prestart 方法，将流添加到未提交的流注册器中
    Status shutdownStatus = prestart();

    // 如果返回 channel 关闭状态不为空则取消
    if (shutdownStatus != null) {
      cancel(shutdownStatus);
      return;
    }

    // 构造一个 BufferEntry
    class StartEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        logger.warning("==> io.grpc.internal.RetriableStream#start#StartEntry#runWith");
        logger.info("开始执行 StartEntry");
        substream.stream.start(new Sublistener(substream));
      }
    }

    synchronized (lock) {
      // 新建 BufferEntry，添加到 buffer 中
      state.buffer.add(new StartEntry());
    }

    // 创建 Substream
    Substream substream = createSubstream(0);
    checkState(hedgingPolicy == null, "hedgingPolicy has been initialized unexpectedly");
    // TODO(zdapeng): if substream is a DelayedStream, do this when name resolution finishes
    hedgingPolicy = hedgingPolicyProvider.get();
    if (!HedgingPolicy.DEFAULT.equals(hedgingPolicy)) {
      isHedging = true;
      retryPolicy = RetryPolicy.DEFAULT;

      FutureCanceller scheduledHedgingRef = null;

      synchronized (lock) {
        logger.info("对冲策略生效，将流添加到对冲流中");
        state = state.addActiveHedge(substream);
        if (hasPotentialHedging(state)
                && (throttle == null || throttle.isAboveThreshold())) {
          scheduledHedging = scheduledHedgingRef = new FutureCanceller(lock);
        }
      }

      if (scheduledHedgingRef != null) {
        logger.info("提交计划的将对冲任务到线程池，" + hedgingPolicy.hedgingDelayNanos + "ns 后执行");
        scheduledHedgingRef.setFuture(
                scheduledExecutorService.schedule(
                        new HedgingRunnable(scheduledHedgingRef),
                        hedgingPolicy.hedgingDelayNanos,
                        TimeUnit.NANOSECONDS));
      }
    }

    drain(substream);
  }

  /**
   * 如果有回推延时时间，则取消原来的对冲，根据回推时间重新调度一个对冲请求
   *
   * @param delayMillis 回推的延时时间
   */
  @SuppressWarnings("GuardedBy")
  private void pushbackHedging(@Nullable Integer delayMillis) {
    logger.warning("==> io.grpc.internal.RetriableStream#pushbackHedging");
    logger.info("根据服务端返回的回推时间，重新调度对冲请求");
    // 如果回推的时间为 null，则返回
    if (delayMillis == null) {
      return;
    }

    // 如果回推的时间小于 0，则取消所有的对冲，更新状态
    if (delayMillis < 0) {
      freezeHedging();
      return;
    }

    // Cancels the current scheduledHedging and reschedules a new one.
    // 取消当前计划的对冲，并重新调度一个
    FutureCanceller future;
    Future<?> futureToBeCancelled;

    synchronized (lock) {
      // 如果没有已经计划的对冲请求则返回
      if (scheduledHedging == null) {
        return;
      }

      // TODO(b/145386688): This access should be guarded by 'this.scheduledHedging.lock'; instead
      // found: 'this.lock'
      // 标记已经计划的对冲请求为取消
      futureToBeCancelled = scheduledHedging.markCancelled();
      // 创建一个新的对冲请求
      scheduledHedging = future = new FutureCanceller(lock);
    }

    // 取消已经计划的对冲
    if (futureToBeCancelled != null) {
      futureToBeCancelled.cancel(false);
    }

    // 根据延时时间，重新提交对冲请求
    future.setFuture(scheduledExecutorService.schedule(new HedgingRunnable(future), delayMillis, TimeUnit.MILLISECONDS));
  }

  private final class HedgingRunnable implements Runnable {

    // Need to hold a ref to the FutureCanceller in case RetriableStrea.scheduledHedging is renewed
    // by a positive push-back just after newSubstream is instantiated, so that we can double check.
    final FutureCanceller scheduledHedgingRef;

    HedgingRunnable(FutureCanceller scheduledHedging) {
      scheduledHedgingRef = scheduledHedging;
    }

    @Override
    public void run() {
      callExecutor.execute(
          new Runnable() {
            @SuppressWarnings("GuardedBy")
            @Override
            public void run() {
              // It's safe to read state.hedgingAttemptCount here.
              // If this run is not cancelled, the value of state.hedgingAttemptCount won't change
              // until state.addActiveHedge() is called subsequently, even the state could possibly
              // change.
              Substream newSubstream = createSubstream(state.hedgingAttemptCount);
              boolean cancelled = false;
              FutureCanceller future = null;

              synchronized (lock) {
                // TODO(b/145386688): This access should be guarded by
                // 'HedgingRunnable.this.scheduledHedgingRef.lock'; instead found:
                // 'RetriableStream.this.lock'
                if (scheduledHedgingRef.isCancelled()) {
                  cancelled = true;
                } else {
                  state = state.addActiveHedge(newSubstream);
                  if (hasPotentialHedging(state)
                      && (throttle == null || throttle.isAboveThreshold())) {
                    scheduledHedging = future = new FutureCanceller(lock);
                  } else {
                    state = state.freezeHedging();
                    scheduledHedging = null;
                  }
                }
              }

              if (cancelled) {
                newSubstream.stream.cancel(Status.CANCELLED.withDescription("Unneeded hedging"));
                return;
              }
              if (future != null) {
                future.setFuture(
                    scheduledExecutorService.schedule(
                        new HedgingRunnable(future),
                        hedgingPolicy.hedgingDelayNanos,
                        TimeUnit.NANOSECONDS));
              }
              drain(newSubstream);
            }
          });
    }
  }

  /**
   * 取消请求并说明原因
   *
   * @param reason must be non-OK
   */
  @Override
  public final void cancel(Status reason) {
    logger.warning("==> io.grpc.internal.RetriableStream#cancel");
    logger.info("取消流");
    // 创建一个空的 Substream
    Substream noopSubstream = new Substream(0 /* previousAttempts doesn't matter here */);
    noopSubstream.stream = new NoopClientStream();
    // 提交，返回的 runnable 是取消保存的对冲、重试以及当前提交的请求
    Runnable runnable = commit(noopSubstream);

    // 执行返回的任务，关闭监听器
    // 如果已经有流提交了，则返回的 Runnable 是 null
    if (runnable != null) {
      masterListener.closed(reason, new Metadata());
      runnable.run();
      return;
    }

    // 指定原因
    state.winningSubstream.stream.cancel(reason);
    synchronized (lock) {
      // This is not required, but causes a short-circuit in the draining process.
      // 返回取消的状态
      state = state.cancelled();
    }
  }

  /**
   * 执行指定的流请求
   *
   * @param bufferEntry
   */
  private void delayOrExecute(BufferEntry bufferEntry) {
    Collection<Substream> savedDrainedSubstreams;
    synchronized (lock) {
      if (!state.passThrough) {
        state.buffer.add(bufferEntry);
      }
      savedDrainedSubstreams = state.drainedSubstreams;
    }

    for (Substream substream : savedDrainedSubstreams) {
      bufferEntry.runWith(substream);
    }
  }

  /**
   * Do not use it directly. Use {@link #sendMessage(ReqT)} instead because we don't use InputStream
   * for buffering.
   */
  @Override
  public final void writeMessage(InputStream message) {
    throw new IllegalStateException("RetriableStream.writeMessage() should not be called directly");
  }

  /**
   * 发送消息
   *
   * @param message
   */
  final void sendMessage(final ReqT message) {
    logger.info("发送重试消息");
    State savedState = state;
    if (savedState.passThrough) {
      savedState.winningSubstream.stream.writeMessage(method.streamRequest(message));
      return;
    }

    class SendMessageEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.writeMessage(method.streamRequest(message));
      }
    }

    delayOrExecute(new SendMessageEntry());
  }

  /**
   * 调用指定数量的请求
   */
  @Override
  public final void request(final int numMessages) {
    logger.warning("==> io.grpc.internal.RetriableStream#request");
    logger.info("调用指定数量的请求:" + numMessages);
    State savedState = state;
    if (savedState.passThrough) {
      savedState.winningSubstream.stream.request(numMessages);
      return;
    }

    class RequestEntry implements BufferEntry {
      /**
       * 用给定的流重放缓冲区
       *
       * @param substream
       */
      @Override
      public void runWith(Substream substream) {
        substream.stream.request(numMessages);
      }
    }

    delayOrExecute(new RequestEntry());
  }

  @Override
  public final void flush() {

    State savedState = state;
    if (savedState.passThrough) {
      savedState.winningSubstream.stream.flush();
      return;
    }

    class FlushEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.flush();
      }
    }

    delayOrExecute(new FlushEntry());
  }

  @Override
  public final boolean isReady() {
    for (Substream substream : state.drainedSubstreams) {
      if (substream.stream.isReady()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void optimizeForDirectExecutor() {
    class OptimizeDirectEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.optimizeForDirectExecutor();
      }
    }

    delayOrExecute(new OptimizeDirectEntry());
  }

  @Override
  public final void setCompressor(final Compressor compressor) {
    class CompressorEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setCompressor(compressor);
      }
    }

    delayOrExecute(new CompressorEntry());
  }

  @Override
  public final void setFullStreamDecompression(final boolean fullStreamDecompression) {
    class FullStreamDecompressionEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setFullStreamDecompression(fullStreamDecompression);
      }
    }

    delayOrExecute(new FullStreamDecompressionEntry());
  }

  @Override
  public final void setMessageCompression(final boolean enable) {
    class MessageCompressionEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setMessageCompression(enable);
      }
    }

    delayOrExecute(new MessageCompressionEntry());
  }

  @Override
  public final void halfClose() {
    logger.warning("==> io.grpc.internal.RetriableStream#halfClose");
    // 从客户端关闭流
    class HalfCloseEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.halfClose();
      }
    }

    // 延迟执行
    delayOrExecute(new HalfCloseEntry());
  }

  @Override
  public final void setAuthority(final String authority) {
    class AuthorityEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setAuthority(authority);
      }
    }

    delayOrExecute(new AuthorityEntry());
  }

  @Override
  public final void setDecompressorRegistry(final DecompressorRegistry decompressorRegistry) {
    class DecompressorRegistryEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setDecompressorRegistry(decompressorRegistry);
      }
    }

    delayOrExecute(new DecompressorRegistryEntry());
  }

  @Override
  public final void setMaxInboundMessageSize(final int maxSize) {
    class MaxInboundMessageSizeEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setMaxInboundMessageSize(maxSize);
      }
    }

    delayOrExecute(new MaxInboundMessageSizeEntry());
  }

  @Override
  public final void setMaxOutboundMessageSize(final int maxSize) {
    class MaxOutboundMessageSizeEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setMaxOutboundMessageSize(maxSize);
      }
    }

    delayOrExecute(new MaxOutboundMessageSizeEntry());
  }

  @Override
  public final void setDeadline(final Deadline deadline) {
    class DeadlineEntry implements BufferEntry {
      @Override
      public void runWith(Substream substream) {
        substream.stream.setDeadline(deadline);
      }
    }

    delayOrExecute(new DeadlineEntry());
  }

  @Override
  public final Attributes getAttributes() {
    if (state.winningSubstream != null) {
      return state.winningSubstream.stream.getAttributes();
    }
    return Attributes.EMPTY;
  }

  @Override
  public void appendTimeoutInsight(InsightBuilder insight) {
    State currentState;
    synchronized (lock) {
      insight.appendKeyValue("closed", closedSubstreamsInsight);
      currentState = state;
    }
    if (currentState.winningSubstream != null) {
      // TODO(zhangkun83): in this case while other drained substreams have been cancelled in favor
      // of the winning substream, they may not have received closed() notifications yet, thus they
      // may be missing from closedSubstreamsInsight.  This may be a little confusing to the user.
      // We need to figure out how to include them.
      InsightBuilder substreamInsight = new InsightBuilder();
      currentState.winningSubstream.stream.appendTimeoutInsight(substreamInsight);
      insight.appendKeyValue("committed", substreamInsight);
    } else {
      InsightBuilder openSubstreamsInsight = new InsightBuilder();
      // drainedSubstreams doesn't include all open substreams.  Those which have just been created
      // and are still catching up with buffered requests (in other words, still draining) will not
      // show up.  We think this is benign, because the draining should be typically fast, and it'd
      // be indistinguishable from the case where those streams are to be created a little late due
      // to delays in the timer.
      for (Substream sub : currentState.drainedSubstreams) {
        InsightBuilder substreamInsight = new InsightBuilder();
        sub.stream.appendTimeoutInsight(substreamInsight);
        openSubstreamsInsight.append(substreamInsight);
      }
      insight.appendKeyValue("open", openSubstreamsInsight);
    }
  }

  /**
   * 用于计算随机延时的随机器
   */
  private static Random random = new Random();

  @VisibleForTesting
  static void setRandom(Random random) {
    RetriableStream.random = random;
  }

  /**
   * Whether there is any potential hedge at the moment. A false return value implies there is
   * absolutely no potential hedge. At least one of the hedges will observe a false return value
   * when calling this method, unless otherwise the rpc is committed.
   * <p>
   * 目前是否可以对冲，返回 false 意味着没有，调用此方法时，意味着至少有一个监听器监听到错误的返回值，
   * 除非这个 RPC 被提交了
   */
  // only called when isHedging is true
  @GuardedBy("lock")
  private boolean hasPotentialHedging(State state) {
    // 没有提交的流，且没有达到最大对冲次数，且没有终止
    return state.winningSubstream == null
            && state.hedgingAttemptCount < hedgingPolicy.maxAttempts
            && !state.hedgingFrozen;
  }

  /**
   * 取消所有的对冲，更新状态
   */
  @SuppressWarnings("GuardedBy")
  private void freezeHedging() {
    Future<?> futureToBeCancelled = null;
    // 加锁
    synchronized (lock) {
      // 如果有已经计划的对冲，则取消，并将 scheduledHedging 置为 null
      if (scheduledHedging != null) {
        // TODO(b/145386688): This access should be guarded by 'this.scheduledHedging.lock'; instead
        // found: 'this.lock'
        futureToBeCancelled = scheduledHedging.markCancelled();
        scheduledHedging = null;
      }
      // 返回状态
      state = state.freezeHedging();
    }

    // 取消相应的 Future
    if (futureToBeCancelled != null) {
      futureToBeCancelled.cancel(false);
    }
  }

  private interface BufferEntry {
    /**
     * Replays the buffer entry with the given stream.
     * 用给定的流重放缓冲区
     */
    void runWith(Substream substream);
  }

  /**
   * Substream 监听器
   */
  private final class Sublistener implements ClientStreamListener {
    final Substream substream;

    Sublistener(Substream substream) {
      this.substream = substream;
    }

    @Override
    public void headersRead(Metadata headers) {
      commitAndRun(substream);
      if (state.winningSubstream == substream) {
        masterListener.headersRead(headers);
        if (throttle != null) {
          throttle.onSuccess();
        }
      }
    }

    @Override
    public void closed(Status status, Metadata trailers) {
      closed(status, RpcProgress.PROCESSED, trailers);
    }

    /**
     * 流关闭时调用
     *
     * @param status      server 端关闭时包的状态
     * @param rpcProgress 客户端关闭时，RPC 的进程
     * @param trailers    响应的元数据
     */
    @Override
    public void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
      logger.warning("==> io.grpc.internal.RetriableStream.Sublistener#closed(io.grpc.Status, io.grpc.internal.ClientStreamListener.RpcProgress, io.grpc.Metadata)");
      logger.info("流关闭时执行操作");
      synchronized (lock) {
        // 将 Substream 从 drainedSubstreams 中移除
        state = state.substreamClosed(substream);
        // 追加当前的状态
        closedSubstreamsInsight.append(status.getCode());
      }

      // handle a race between buffer limit exceeded and closed, when setting
      // substream.bufferLimitExceeded = true happens before state.substreamClosed(substream).

      // 当设置 substream.bufferLimitExceeded = true 在 state.substreamClosed(substream)
      // 之前发生时，缓冲区溢出，则直接提交请求，关闭监听器
      if (substream.bufferLimitExceeded) {
        commitAndRun(substream);
        if (state.winningSubstream == substream) {
          masterListener.closed(status, trailers);
        }
        return;
      }

      if (state.winningSubstream == null) {
        boolean isFatal = false;
        logger.info("根据服务端返回的状态执行后续操作,当前状态:"+rpcProgress.name());
        // 如果服务端的状态是被拒绝
        if (rpcProgress == RpcProgress.REFUSED &&
                noMoreTransparentRetry.compareAndSet(false, true)) {
          // transparent retry
          // 则在 Transport 层重试
          logger.info("请求 REFUSED 透明重试");
          final Substream newSubstream = createSubstream(substream.previousAttemptCount);
          if (isHedging) {
            boolean commit = false;
            synchronized (lock) {
              logger.info("开始透明对冲");
              // Although this operation is not done atomically with
              // noMoreTransparentRetry.compareAndSet(false, true), it does not change the size() of
              // activeHedges, so neither does it affect the commitment decision of other threads,
              // nor do the commitment decision making threads affect itself.
              // 尽管不是通过 noMoreTransparentRetry.compareAndSet(false, true) 操作的，但是并不会改变
              // activeHedges 的 size，所以它也不影响其他线程的承诺决策，线程也不会影响它本身
              // 用新的流替换原有的对冲流
              state = state.replaceActiveHedge(substream, newSubstream);

              // optimization for early commit
              // 如果不可以对冲，且活跃的流为 1，则提交
              if (!hasPotentialHedging(state) && state.activeHedges.size() == 1) {
                commit = true;
              }
            }
            // 提交流
            if (commit) {
              commitAndRun(newSubstream);
            }
          } else {
            logger.info("开始透明重试");
            // 如果是重试，重试策略为空，则获取
            if (retryPolicy == null) {
              retryPolicy = retryPolicyProvider.get();
            }
            // 如果不能重试，则提交流
            if (retryPolicy.maxAttempts == 1) {
              // optimization for early commit
              commitAndRun(newSubstream);
            }
          }
          // 执行新的请求
          callExecutor.execute(new Runnable() {
            @Override
            public void run() {
              // 处理流中的请求
              drain(newSubstream);
            }
          });
          return;
        } else if (rpcProgress == RpcProgress.DROPPED) {
          logger.info("请求 DROPPED，取消所有的对冲");
          // DROPPED 表示请求被负载均衡丢弃了
          // For normal retry, nothing need be done here, will just commit.
          // For hedging, cancel scheduled hedge that is scheduled prior to the drop
          // 对于正常的重试，不会做任何操作，直接提交；对于对冲，取消之前计划的对冲
          if (isHedging) {
            // 取消所有的对冲，更新状态
            freezeHedging();
          }
        } else {
          logger.info("请求 PROCESSED，根据策略执行后续操作");
          // PROCESSED 状态
          // 没有更多 Transport 层的透明重试
          noMoreTransparentRetry.set(true);

          // 如果重试策略为空，则从 Provider 中获取
          if (retryPolicy == null) {
            retryPolicy = retryPolicyProvider.get();
            // 下一次重试的延迟时间，nextBackoffIntervalNanos * random.netDouble()
            nextBackoffIntervalNanos = retryPolicy.initialBackoffNanos;
          }

          // 决定是否重试
          RetryPlan retryPlan = makeRetryDecision(status, trailers);
          // 判断是否需要重试
          if (retryPlan.shouldRetry) {
            // The check state.winningSubstream == null, checking if is not already committed, is
            // racy, but is still safe b/c the retry will also handle committed/cancellation
            // 检查state.winningSubstream == null，检查是否尚未提交，有风险，但是会通过 committed/cancellation 保证安全
            // 允许取消的 Future
            FutureCanceller scheduledRetryCopy;
            synchronized (lock) {
              scheduledRetry = scheduledRetryCopy = new FutureCanceller(lock);
            }

            logger.info("提交重试任务");
            // 通过执行新的 Runnable，将返回的 Future 作为参数，设置给 scheduledRetryCopy
            scheduledRetryCopy.setFuture(scheduledExecutorService.schedule(
                //  提交的新的任务
                new Runnable() {
                  @Override
                  public void run() {
                    // 创建新的流，重试
                    callExecutor.execute(new Runnable() {
                      @Override
                      public void run() {
                        // retry 开始重试，将重试次数加一
                        Substream newSubstream = createSubstream(substream.previousAttemptCount + 1);
                        drain(newSubstream);
                      }
                    });
                  }
                },
                retryPlan.backoffNanos,
                TimeUnit.NANOSECONDS));
            return;
          }
          // 是否返回的状态不在 nonFatalStatusCodes 中
          isFatal = retryPlan.isFatal;
          // 如果有回推延时时间，则取消原来的对冲，根据回推时间重新调度一个对冲请求
          pushbackHedging(retryPlan.hedgingPushbackMillis);
        }

        // 如果是对冲请求，
        if (isHedging) {
          logger.info("对冲请求");
          synchronized (lock) {
            // 从所有活跃的对冲请求流中移除当前的流并返回状态
            state = state.removeActiveHedge(substream);

            // The invariant is whether or not #(Potential Hedge + active hedges) > 0.
            // Once hasPotentialHedging(state) is false, it will always be false, and then
            // #(state.activeHedges) will be decreasing. This guarantees that even there may be
            // multiple concurrent hedges, one of the hedges will end up committed.
            // 不变的是  #(Potential Hedge + active hedges) > 0.
            // 一旦 hasPotentialHedging(state) 是 false，那么会一直是 false，然后#(state.activeHedges)
            // 会减少，这保证了即使有多个并发，其中会有一个被提交
            if (!isFatal) {
              // 如果有对冲或者对话从请求不为空，则返回，继续执行对冲
              if (hasPotentialHedging(state) || !state.activeHedges.isEmpty()) {
                return;
              }
              // else, no activeHedges, no new hedges possible, try to commit
            } // else, fatal, try to commit
          }
        }
      }

      // 提交流
      commitAndRun(substream);
      // 如果当前流是成功关闭的流，则关闭监听器
      if (state.winningSubstream == substream) {
        masterListener.closed(status, trailers);
      }
    }

    /**
     * Decides in current situation whether or not the RPC should retry and if it should retry how
     * long the backoff should be. The decision does not take the commitment status into account, so
     * caller should check it separately. It also updates the throttle. It does not change state.
     * 根据当前的条件决定是否需要重试，如果重试则设置延时时间，该决定未考虑承诺状态，所以调用者应当自己分别检查，
     * 同时更新了节流配置，不改变状态
     */
    private RetryPlan makeRetryDecision(Status status, Metadata trailer) {
      logger.warning("==> io.grpc.internal.RetriableStream.Sublistener#makeRetryDecision");
      logger.info("根据当前条件决定是否需要重试");
      boolean shouldRetry = false;
      long backoffNanos = 0L;
      // 可以重试的状态
      boolean isRetryableStatusCode = retryPolicy.retryableStatusCodes.contains(status.getCode());
      // 非失败的状态
      boolean isNonFatalStatusCode = hedgingPolicy.nonFatalStatusCodes.contains(status.getCode());
      // 如果是对冲，且是失败状态，则直接返回不能重试的策略
      if (isHedging && !isNonFatalStatusCode) {
        logger.info("当前是对冲，不重试");
        // isFatal is true, no pushback
        return new RetryPlan(/* shouldRetry = */ false, /* isFatal = */ true, 0, null);
      }

      // 从元数据获取 Server 返回的重试回推时间
      String pushbackStr = trailer.get(GRPC_RETRY_PUSHBACK_MS);
      Integer pushbackMillis = null;
      if (pushbackStr != null) {
        try {
          pushbackMillis = Integer.valueOf(pushbackStr);
          logger.info("服务端返回的重试回退时间:" + pushbackMillis + "ms");
        } catch (NumberFormatException e) {
          pushbackMillis = -1;
        }
      }

      // 是否节流
      boolean isThrottled = false;
      if (throttle != null) {
        if (isRetryableStatusCode ||
                isNonFatalStatusCode ||
                (pushbackMillis != null && pushbackMillis < 0)) {
          isThrottled = !throttle.onQualifiedFailureThenCheckIsAboveThreshold();
        }
      }

      // 没有达到最大重试次数，且没有开启节流
      if (retryPolicy.maxAttempts > substream.previousAttemptCount + 1 && !isThrottled) {
        // 如果回推延时为空，且是可以重试的状态
        if (pushbackMillis == null) {
          if (isRetryableStatusCode) {
            shouldRetry = true;
            // 计算延时时间
            backoffNanos = (long) (nextBackoffIntervalNanos * random.nextDouble());
            // 根据随机的延时时间和最大延时时间，选取最小的
            nextBackoffIntervalNanos = Math.min((long) (nextBackoffIntervalNanos * retryPolicy.backoffMultiplier), retryPolicy.maxBackoffNanos);
          } // else no retry
        } else if (pushbackMillis >= 0) {
          // 如果有回推延时时间，则将延时时间作为下次重试时间间隔
          shouldRetry = true;
          backoffNanos = TimeUnit.MILLISECONDS.toNanos(pushbackMillis);
          nextBackoffIntervalNanos = retryPolicy.initialBackoffNanos;
        } // else no retry
      } // else no retry
      logger.info("重试的延迟时间:" + backoffNanos + "ns, 下一次初始延迟时间:" + nextBackoffIntervalNanos + "ns");
      return new RetryPlan(shouldRetry, /* isFatal = */ false, backoffNanos, isHedging ? pushbackMillis : null);
    }

    @Override
    public void messagesAvailable(MessageProducer producer) {
      State savedState = state;
      checkState(
          savedState.winningSubstream != null, "Headers should be received prior to messages.");
      if (savedState.winningSubstream != substream) {
        return;
      }
      masterListener.messagesAvailable(producer);
    }

    @Override
    public void onReady() {
      // FIXME(#7089): hedging case is broken.
      // TODO(zdapeng): optimization: if the substream is not drained yet, delay onReady() once
      // drained and if is still ready.
      masterListener.onReady();
    }
  }

  /**
   * 状态
   */
  private static final class State {
    /**
     * Committed and the winning substream drained.
     * 成功提交且释放流
     */
    final boolean passThrough;

    /**
     * A list of buffered ClientStream runnables. Set to Null once passThrough.
     * 缓冲的 ClientStream 的任务集合，只要完成了就设置为 null
     */
    @Nullable
    final List<BufferEntry> buffer;

    /**
     * Unmodifiable collection of all the open substreams that are drained. Singleton once
     * passThrough; Empty if committed but not passTrough.
     * 所有已释放的打开的 Substream 的不可变集合
     * 一旦成功提交且释放流则是单例的，如果提交了但是没有释放流则是空
     */
    final Collection<Substream> drainedSubstreams;

    /**
     * Unmodifiable collection of all the active hedging substreams.
     * 所有活跃的对冲请求流的不可变集合
     *
     * <p>A substream even with the attribute substream.closed being true may be considered still
     * "active" at the moment as long as it is in this collection.
     * 如果 一个 Substream 的 substream.closed 是 true，且在这个集合中，就认为它是活跃的
     */
    final Collection<Substream> activeHedges; // not null once isHedging = true

    /**
     * 对冲请求的次数
     */
    final int hedgingAttemptCount;

    /**
     * Null until committed.
     * 提交之前最后一个处理的流，在提交之前是 null
     */
    @Nullable
    final Substream winningSubstream;

    /**
     * Not required to set to true when cancelled, but can short-circuit the draining process.
     * 流是否取消，当取消时不要求设置为 true，但是会短路流
     */
    final boolean cancelled;

    /**
     * No more hedging due to events like drop or pushback.
     * 因为回推或终止事件导致没有更多的对冲请求
     */
    final boolean hedgingFrozen;

    /**
     * 构建 State
     *
     * @param buffer              缓冲的 ClientStream 的任务集合，只要完成了就设置为 null
     * @param drainedSubstreams   所有已释放的打开的 Substream 的不可变集合
     * @param activeHedges        所有活跃的对冲请求流的不可变集合
     * @param winningSubstream    提交之前最后一个处理的流，在提交之前是 null
     * @param cancelled           流是否取消，当取消时不要求设置为 true，但是会短路流
     * @param passThrough         成功提交且释放流
     * @param hedgingFrozen       因为回推或终止事件导致没有更多的对冲请求
     * @param hedgingAttemptCount 对冲请求的次数
     */
    State(@Nullable List<BufferEntry> buffer,
          Collection<Substream> drainedSubstreams,
          Collection<Substream> activeHedges,
          @Nullable Substream winningSubstream,
          boolean cancelled,
          boolean passThrough,
          boolean hedgingFrozen,
          int hedgingAttemptCount) {
      this.buffer = buffer;
      this.drainedSubstreams = checkNotNull(drainedSubstreams, "drainedSubstreams");
      this.winningSubstream = winningSubstream;
      this.activeHedges = activeHedges;
      this.cancelled = cancelled;
      this.passThrough = passThrough;
      this.hedgingFrozen = hedgingFrozen;
      this.hedgingAttemptCount = hedgingAttemptCount;

      checkState(!passThrough || buffer == null, "passThrough should imply buffer is null");
      checkState(!passThrough || winningSubstream != null, "passThrough should imply winningSubstream != null");
      checkState(!passThrough ||
                      (drainedSubstreams.size() == 1 && drainedSubstreams.contains(winningSubstream)) ||
                      (drainedSubstreams.size() == 0 && winningSubstream.closed),
              "passThrough should imply winningSubstream is drained");
      checkState(!cancelled || winningSubstream != null, "cancelled should imply committed");
    }

    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    State cancelled() {
      return new State(
          buffer, drainedSubstreams, activeHedges, winningSubstream, true, passThrough,
          hedgingFrozen, hedgingAttemptCount);
    }

    /**
     * 将 Substream 移除
     * The given substream is drained.
     */
    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    State substreamDrained(Substream substream) {
      logger.warning("==> io.grpc.internal.RetriableStream.State#substreamDrained");
      logger.info("当前流变为提交的流，并返回状态");
      checkState(!passThrough, "Already passThrough");

      Collection<Substream> drainedSubstreams;

      // 如果这个流已经是关闭状态，则
      if (substream.closed) {
        drainedSubstreams = this.drainedSubstreams;
      } else if (this.drainedSubstreams.isEmpty()) {
        // 如果没有枯竭的流，则使用这个流创建
        // optimize for 0-retry, which is most of the cases.
        drainedSubstreams = Collections.singletonList(substream);
      } else {
        // 否则将这个流加入
        drainedSubstreams = new ArrayList<>(this.drainedSubstreams);
        drainedSubstreams.add(substream);
        drainedSubstreams = Collections.unmodifiableCollection(drainedSubstreams);
      }

      // 是否已经有提交的流
      boolean passThrough = winningSubstream != null;

      List<BufferEntry> buffer = this.buffer;
      // 如果有提交的流，则检查是否是当前的流
      if (passThrough) {
        checkState(winningSubstream == substream, "Another RPC attempt has already committed");
        buffer = null;
      }

      // 返回 State
      return new State(
              buffer,
              drainedSubstreams,
              activeHedges,
              winningSubstream,
              cancelled,
              passThrough,
              hedgingFrozen,
              hedgingAttemptCount);
    }

    /**
     * 将 Substream 从 drainedSubstreams 中移除，并关闭流
     * The given substream is closed.
     */
    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    State substreamClosed(Substream substream) {
      logger.warning("==> io.grpc.internal.RetriableStream.State#substreamClosed");
      logger.info("移除当前流，并返回状态");

      substream.closed = true;
      // 如果包含这个流
      if (this.drainedSubstreams.contains(substream)) {
        // 构建新的集合，将当前流移除
        Collection<Substream> drainedSubstreams = new ArrayList<>(this.drainedSubstreams);
        drainedSubstreams.remove(substream);
        drainedSubstreams = Collections.unmodifiableCollection(drainedSubstreams);

        // 返回状态
        return new State(buffer,
                drainedSubstreams,
                activeHedges,
                winningSubstream,
                cancelled,
                passThrough,
                hedgingFrozen,
                hedgingAttemptCount);
      } else {
        return this;
      }
    }

    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    State committed(Substream winningSubstream) {
      logger.warning("==> io.grpc.internal.RetriableStream.State#committed");
      logger.warning("提交流并返回状态");
      checkState(this.winningSubstream == null, "Already committed");

      boolean passThrough = false;
      List<BufferEntry> buffer = this.buffer;
      Collection<Substream> drainedSubstreams;

      if (this.drainedSubstreams.contains(winningSubstream)) {
        passThrough = true;
        buffer = null;
        drainedSubstreams = Collections.singleton(winningSubstream);
      } else {
        drainedSubstreams = Collections.emptyList();
      }

      return new State(
          buffer, drainedSubstreams, activeHedges, winningSubstream, cancelled, passThrough,
          hedgingFrozen, hedgingAttemptCount);
    }

    /**
     * 返回冻结对冲请求的状态
     *
     * @return
     */
    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    State freezeHedging() {
      if (hedgingFrozen) {
        return this;
      }
      return new State(buffer,
              drainedSubstreams,
              activeHedges,
              winningSubstream,
              cancelled,
              passThrough,
              true,
              hedgingAttemptCount);
    }

    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    // state.hedgingAttemptCount is modified only here.
    // The method is only called in RetriableStream.start() and HedgingRunnable.run()
    State addActiveHedge(Substream substream) {
      // hasPotentialHedging must be true
      checkState(!hedgingFrozen, "hedging frozen");
      checkState(winningSubstream == null, "already committed");

      Collection<Substream> activeHedges;
      if (this.activeHedges == null) {
        activeHedges = Collections.singleton(substream);
      } else {
        activeHedges = new ArrayList<>(this.activeHedges);
        activeHedges.add(substream);
        activeHedges = Collections.unmodifiableCollection(activeHedges);
      }

      int hedgingAttemptCount = this.hedgingAttemptCount + 1;
      return new State(
          buffer, drainedSubstreams, activeHedges, winningSubstream, cancelled, passThrough,
          hedgingFrozen, hedgingAttemptCount);
    }

    /**
     * 从所有活跃的对冲请求流中移除当前的流并返回状态
     *
     * @param substream
     * @return
     */
    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    // The method is only called in Sublistener.closed()
    State removeActiveHedge(Substream substream) {
      // 从所有活跃的对冲请求流中移除当前的流
      Collection<Substream> activeHedges = new ArrayList<>(this.activeHedges);
      activeHedges.remove(substream);
      activeHedges = Collections.unmodifiableCollection(activeHedges);

      // 返回状态
      return new State(buffer,
              drainedSubstreams,
              activeHedges,
              winningSubstream,
              cancelled,
              passThrough,
              hedgingFrozen,
              hedgingAttemptCount);
    }

    /**
     * Transport 重试
     *
     * @param oldOne
     * @param newOne
     * @return
     */
    @CheckReturnValue
    // GuardedBy RetriableStream.lock
    // The method is only called for transparent retry.
    State replaceActiveHedge(Substream oldOne, Substream newOne) {
      // 根据之前的对冲请求 Substream 创建新的集合
      Collection<Substream> activeHedges = new ArrayList<>(this.activeHedges);
      // 移除旧的 Substream，并加入新的
      activeHedges.remove(oldOne);
      activeHedges.add(newOne);
      // 变为不可变的集合
      activeHedges = Collections.unmodifiableCollection(activeHedges);

      // 返回状态
      return new State(buffer,
              drainedSubstreams,
              activeHedges,
              winningSubstream,
              cancelled,
              passThrough,
              hedgingFrozen,
              hedgingAttemptCount);
    }
  }

  /**
   * A wrapper of a physical stream of a retry/hedging attempt, that comes with some useful
   * attributes.
   * 重试/对冲的流的包装
   */
  private static final class Substream {
    ClientStream stream;

    // GuardedBy RetriableStream.lock
    /**
     * 流是关闭状态
     */
    boolean closed;

    // setting to true must be GuardedBy RetriableStream.lock
    /**
     * 缓冲区超出限制
     */
    boolean bufferLimitExceeded;

    /**
     * 之前的重试次数
     */
    final int previousAttemptCount;

    Substream(int previousAttemptCount) {
      this.previousAttemptCount = previousAttemptCount;
    }
  }


  /**
   * Traces the buffer used by a substream.
   * 追踪 Substream 用的 buffer
   */
  class BufferSizeTracer extends ClientStreamTracer {
    // Each buffer size tracer is dedicated to one specific substream.
    private final Substream substream;

    @GuardedBy("lock")
    long bufferNeeded;

    BufferSizeTracer(Substream substream) {
      this.substream = substream;
    }

    /**
     * A message is sent to the wire, so its reference would be released if no retry or
     * hedging were involved. So at this point we have to hold the reference of the message longer
     * for retry, and we need to increment {@code substream.bufferNeeded}.
     */
    @Override
    public void outboundWireSize(long bytes) {
      if (state.winningSubstream != null) {
        return;
      }

      Runnable postCommitTask = null;

      // TODO(zdapeng): avoid using the same lock for both in-bound and out-bound.
      synchronized (lock) {
        if (state.winningSubstream != null || substream.closed) {
          return;
        }
        bufferNeeded += bytes;
        if (bufferNeeded <= perRpcBufferUsed) {
          return;
        }

        if (bufferNeeded > perRpcBufferLimit) {
          substream.bufferLimitExceeded = true;
        } else {
          // Only update channelBufferUsed when perRpcBufferUsed is not exceeding perRpcBufferLimit.
          long savedChannelBufferUsed =
              channelBufferUsed.addAndGet(bufferNeeded - perRpcBufferUsed);
          perRpcBufferUsed = bufferNeeded;

          if (savedChannelBufferUsed > channelBufferLimit) {
            substream.bufferLimitExceeded = true;
          }
        }

        if (substream.bufferLimitExceeded) {
          postCommitTask = commit(substream);
        }
      }

      if (postCommitTask != null) {
        postCommitTask.run();
      }
    }
  }

  /**
   *  Used to keep track of the total amount of memory used to buffer retryable or hedged RPCs for
   *  the Channel. There should be a single instance of it for each channel.
   */
  static final class ChannelBufferMeter {
    private final AtomicLong bufferUsed = new AtomicLong();

    @VisibleForTesting
    long addAndGet(long newBytesUsed) {
      return bufferUsed.addAndGet(newBytesUsed);
    }
  }

  /**
   * 重试节流策略
   * Used for retry throttling.
   */
  static final class Throttle {

    private static final int THREE_DECIMAL_PLACES_SCALE_UP = 1000;

    /**
     * 1000 times the maxTokens field of the retryThrottling policy in service config.
     * The number of tokens starts at maxTokens. The token_count will always be between 0 and
     * maxTokens.
     * 服务配置中 retryThrottling 策略的 maxTokens 字段的 1000 倍
     * 初始的值是 maxTokens, token_count 的值在 0-maxTokens 之间
     */
    final int maxTokens;

    /**
     * maxTokens 的一半
     * Half of {@code maxTokens}.
     */
    final int threshold;

    /**
     * 服务配置中 retryThrottling 策略的 tokenRatio 字段的1000倍
     * 1000 times the tokenRatio field of the retryThrottling policy in service config.
     */
    final int tokenRatio;

    /**
     * 当前token 数量
     */
    final AtomicInteger tokenCount = new AtomicInteger();

    Throttle(float maxTokens, float tokenRatio) {
      // 因为支持小数点后三位，所以乘 1000 作为整数
      // tokenRatio is up to 3 decimal places
      this.tokenRatio = (int) (tokenRatio * THREE_DECIMAL_PLACES_SCALE_UP);
      this.maxTokens = (int) (maxTokens * THREE_DECIMAL_PLACES_SCALE_UP);
      this.threshold = this.maxTokens / 2;
      tokenCount.set(this.maxTokens);
    }

    /**
     * 判断是否大于节流的值
     *
     * @return
     */
    @VisibleForTesting
    boolean isAboveThreshold() {
      return tokenCount.get() > threshold;
    }

    /**
     * Counts down the token on qualified failure and checks if it is above the threshold
     * atomically. Qualified failure is a failure with a retryable or non-fatal status code or with
     * a not-to-retry pushback.
     *
     * 当失败后将 token 数量减少，自动检查是否大于节流的值，合格故障是指具有可重试或非致命状态代码或带有不可重试回推的故障
     */
    @VisibleForTesting
    boolean onQualifiedFailureThenCheckIsAboveThreshold() {
      while (true) {
        // 如果 token 数量为 0 则返回
        int currentCount = tokenCount.get();
        if (currentCount == 0) {
          return false;
        }
        // 减少的数量
        int decremented = currentCount - (1 * THREE_DECIMAL_PLACES_SCALE_UP);
        // 更新 token 数量
        boolean updated = tokenCount.compareAndSet(currentCount, Math.max(decremented, 0));
        // 更新成功后返回是否大于节流的
        if (updated) {
          return decremented > threshold;
        }
      }
    }

    /**
     * 成功后更新 token 数量
     */
    @VisibleForTesting
    void onSuccess() {
      while (true) {
        // 当已经达到最大 token 数量时不再更新
        int currentCount = tokenCount.get();
        if (currentCount == maxTokens) {
          break;
        }
        // 将 token 数量增加 tokenRatio 并更新值
        int incremented = currentCount + tokenRatio;
        boolean updated = tokenCount.compareAndSet(currentCount, Math.min(incremented, maxTokens));
        if (updated) {
          break;
        }
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Throttle)) {
        return false;
      }
      Throttle that = (Throttle) o;
      return maxTokens == that.maxTokens && tokenRatio == that.tokenRatio;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(maxTokens, tokenRatio);
    }
  }

  /**
   * 重试计划
   */
  private static final class RetryPlan {
    final boolean shouldRetry;
    /**
     * 返回的状态不在 nonFatalStatusCodes 中
     */
    final boolean isFatal; // receiving a status not among the nonFatalStatusCodes
    final long backoffNanos;
    @Nullable
    final Integer hedgingPushbackMillis;

    RetryPlan(boolean shouldRetry,
              boolean isFatal,
              long backoffNanos,
              @Nullable Integer hedgingPushbackMillis) {
      this.shouldRetry = shouldRetry;
      this.isFatal = isFatal;
      this.backoffNanos = backoffNanos;
      this.hedgingPushbackMillis = hedgingPushbackMillis;
    }
  }

  /**
   * Allows cancelling a Future without racing with setting the future.
   * 允许取消Future，而无需设置Future
   */
  private static final class FutureCanceller {

    final Object lock;
    @GuardedBy("lock")
    Future<?> future;
    @GuardedBy("lock")
    boolean cancelled;

    FutureCanceller(Object lock) {
      this.lock = lock;
    }

    void setFuture(Future<?> future) {
      synchronized (lock) {
        if (!cancelled) {
          this.future = future;
        }
      }
    }

    /**
     * 标记为取消
     *
     * @return
     */
    @GuardedBy("lock")
    @CheckForNull
    // Must cancel the returned future if not null.
    Future<?> markCancelled() {
      cancelled = true;
      return future;
    }

    @GuardedBy("lock")
    boolean isCancelled() {
      return cancelled;
    }
  }
}
