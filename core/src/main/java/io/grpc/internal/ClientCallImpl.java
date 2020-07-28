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

package io.grpc.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.InternalDecompressorRegistry;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.perfmark.Link;
import io.perfmark.PerfMark;
import io.perfmark.Tag;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.grpc.Contexts.statusFromCancelled;
import static io.grpc.Status.DEADLINE_EXCEEDED;
import static io.grpc.internal.GrpcUtil.CONTENT_ACCEPT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.CONTENT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.MESSAGE_ENCODING_KEY;
import static java.lang.Math.max;

/**
 * ClientCall 的实现，用于执行调用及监听回调
 * Implementation of {@link ClientCall}.
 */
final class ClientCallImpl<ReqT, RespT> extends ClientCall<ReqT, RespT> {

  private static final Logger log = Logger.getLogger(ClientCallImpl.class.getName());
  private static final byte[] FULL_STREAM_DECOMPRESSION_ENCODINGS
      = "gzip".getBytes(Charset.forName("US-ASCII"));
  // When a deadline is exceeded, there is a race between the server receiving the cancellation from
  // the client and the server cancelling the stream itself. If the client's cancellation is
  // received first, then the stream's status will be CANCELLED instead of DEADLINE_EXCEEDED.
  // This prevents server monitoring from noticing high rate of DEADLINE_EXCEEDED, a common
  // monitoring metric (b/118879795). Mitigate this by delayed sending of the client's cancellation.
  @VisibleForTesting
  static final long DEADLINE_EXPIRATION_CANCEL_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final MethodDescriptor<ReqT, RespT> method;
  private final Tag tag;
  private final Executor callExecutor;
  // 是否是直接执行
  private final boolean callExecutorIsDirect;
  private final CallTracer channelCallsTracer;
  private final Context context;
  // 是否是 unary 的请求
  private final boolean unaryRequest;
  // 调用的选项
  private final CallOptions callOptions;
  // 是否开启了重试
  private final boolean retryEnabled;
  private ClientStream stream;
  private volatile boolean cancelListenersShouldBeRemoved;
  private boolean cancelCalled;
  private boolean halfCloseCalled;
  // Transport 提供器
  private final ClientTransportProvider clientTransportProvider;
  private ContextCancellationListener cancellationListener;
  // 用于调度的执行器
  private final ScheduledExecutorService deadlineCancellationExecutor;
  private boolean fullStreamDecompression;
  private DecompressorRegistry decompressorRegistry = DecompressorRegistry.getDefaultInstance();
  private CompressorRegistry compressorRegistry = CompressorRegistry.getDefaultInstance();
  private volatile ScheduledFuture<?> deadlineCancellationNotifyApplicationFuture;
  private volatile ScheduledFuture<?> deadlineCancellationSendToServerFuture;
  private boolean observerClosed = false;

  /**
   * 构建 ClientCall 实例
   *
   * @param method                       调用的方法
   * @param executor                     执行的线程池
   * @param callOptions                  调用的选项，包括调用类型，线程池，重试策略，对冲策略
   * @param clientTransportProvider      Transport 提供器
   * @param deadlineCancellationExecutor 用于调度的执行器
   * @param channelCallsTracer           统计 Channel 调用信息
   * @param retryEnabled                 是否重试
   */
  ClientCallImpl(
          MethodDescriptor<ReqT, RespT> method,
          Executor executor,
          CallOptions callOptions,
          ClientTransportProvider clientTransportProvider,
          ScheduledExecutorService deadlineCancellationExecutor,
          CallTracer channelCallsTracer,
          boolean retryEnabled) {
    this.method = method;
    // TODO(carl-mastrangelo): consider moving this construction to ManagedChannelImpl.
    // 用于性能追踪的工具
    this.tag = PerfMark.createTag(method.getFullMethodName(), System.identityHashCode(this));
    // If we know that the executor is a direct executor, we don't need to wrap it with a
    // SerializingExecutor. This is purely for performance reasons.
    // See https://github.com/grpc/grpc-java/issues/368
    // 如果是直接执行器，则不需要包装为 SerializingExecutor，出于性能原因
    if (executor == directExecutor()) {
      this.callExecutor = new SerializeReentrantCallsDirectExecutor();
      callExecutorIsDirect = true;
    } else {
      this.callExecutor = new SerializingExecutor(executor);
      callExecutorIsDirect = false;
    }
    this.channelCallsTracer = channelCallsTracer;
    // Propagate the context from the thread which initiated the call to all callbacks.
    // 将当前上下文传播给所有的回调
    this.context = Context.current();
    // 是否是 unary 的请求
    this.unaryRequest = method.getType() == MethodType.UNARY || method.getType() == MethodType.SERVER_STREAMING;
    // 调用的选项
    this.callOptions = callOptions;
    // Transport 提供器
    this.clientTransportProvider = clientTransportProvider;
    // 用于调度的执行器
    this.deadlineCancellationExecutor = deadlineCancellationExecutor;
    this.retryEnabled = retryEnabled;
    PerfMark.event("ClientCall.<init>", tag);
  }

  /**
   * 支持取消流的监听器
   */
  private final class ContextCancellationListener implements CancellationListener {
    private Listener<RespT> observer;

    private ContextCancellationListener(Listener<RespT> observer) {
      this.observer = observer;
    }

    @Override
    public void cancelled(Context context) {
      if (context.getDeadline() == null || !context.getDeadline().isExpired()) {
        stream.cancel(statusFromCancelled(context));
      } else {
        Status status = statusFromCancelled(context);
        delayedCancelOnDeadlineExceeded(status, observer);
      }
    }
  }

  /**
   * 用于提供 ClientTransport
   * Provider of {@link ClientTransport}s.
   */
  // TODO(zdapeng): replace the two APIs with a single API: newStream()
  interface ClientTransportProvider {
    /**
     * Returns a transport for a new call.
     * 返回一个用于新的调用的 transport
     *
     * @param args object containing call arguments.
     */
    ClientTransport get(PickSubchannelArgs args);

    <ReqT> ClientStream newRetriableStream(
            MethodDescriptor<ReqT, ?> method,
            CallOptions callOptions,
            Metadata headers,
            Context context);

  }


  /**
   * 设置流是否解压缩
   *
   * @param fullStreamDecompression
   * @return
   */
  ClientCallImpl<ReqT, RespT> setFullStreamDecompression(boolean fullStreamDecompression) {
    this.fullStreamDecompression = fullStreamDecompression;
    return this;
  }

  /**
   * 设置流解压缩注册器
   *
   * @param decompressorRegistry
   * @return
   */
  ClientCallImpl<ReqT, RespT> setDecompressorRegistry(DecompressorRegistry decompressorRegistry) {
    this.decompressorRegistry = decompressorRegistry;
    return this;
  }

  /**
   * 设置流压缩注册器
   *
   * @param compressorRegistry
   * @return
   */
  ClientCallImpl<ReqT, RespT> setCompressorRegistry(CompressorRegistry compressorRegistry) {
    this.compressorRegistry = compressorRegistry;
    return this;
  }

  /**
   * 初始化 Header
   */
  @VisibleForTesting
  static void prepareHeaders(Metadata headers,
                             DecompressorRegistry decompressorRegistry,
                             Compressor compressor,
                             boolean fullStreamDecompression) {
    log.warning("==> io.grpc.internal.ClientCallImpl.prepareHeaders");
    // 移除编码的 header，如果有压缩，则根据压缩编码重新设置
    headers.discardAll(MESSAGE_ENCODING_KEY);
    if (compressor != Codec.Identity.NONE) {
      headers.put(MESSAGE_ENCODING_KEY, compressor.getMessageEncoding());
    }

    // 能接受的消息编码格式的头
    headers.discardAll(MESSAGE_ACCEPT_ENCODING_KEY);
    byte[] advertisedEncodings = InternalDecompressorRegistry.getRawAdvertisedMessageEncodings(decompressorRegistry);
    if (advertisedEncodings.length != 0) {
      headers.put(MESSAGE_ACCEPT_ENCODING_KEY, advertisedEncodings);
    }
    // 移除 stream 内容编码格式的 header
    headers.discardAll(CONTENT_ENCODING_KEY);
    // 移除 stream 接收内容的编码格式 header
    headers.discardAll(CONTENT_ACCEPT_ENCODING_KEY);
    // 如果开启了流的压缩，则重新设置 stream 接收内容的编码格式 header
    if (fullStreamDecompression) {
      headers.put(CONTENT_ACCEPT_ENCODING_KEY, FULL_STREAM_DECOMPRESSION_ENCODINGS);
    }
  }

  /**
   * 开始一次调用，通过 responseListener 处理返回响应
   *
   * @param observer 响应监听器
   * @param headers  包含额外的元数据，如鉴权
   */
  @Override
  public void start(Listener<RespT> observer, Metadata headers) {
    log.warning("==> io.grpc.internal.ClientCallImpl.start");

    PerfMark.startTask("ClientCall.start", tag);
    try {
      // 开始调用
      startInternal(observer, headers);
    } finally {
      PerfMark.stopTask("ClientCall.start", tag);
    }
  }

  /**
   * 执行请求调用
   *
   * @param observer 响应监听器
   * @param headers  包含额外的元数据，如鉴权
   */
  private void startInternal(final Listener<RespT> observer, Metadata headers) {
    log.warning("==> io.grpc.internal.ClientCallImpl.startInternal");

    checkState(stream == null, "Already started");
    checkState(!cancelCalled, "call was cancelled");
    checkNotNull(observer, "observer");
    checkNotNull(headers, "headers");

    // 如果已经取消了，则不创建流，通知监听器取消回调
    if (context.isCancelled()) {
      // Context is already cancelled so no need to create a real stream, just notify the observer
      // of cancellation via callback on the executor
      stream = NoopClientStream.INSTANCE;
      executeCloseObserverInContext(observer, statusFromCancelled(context));
      return;
    }

    // 压缩器
    log.info("设置压缩器");
    final String compressorName = callOptions.getCompressor();
    Compressor compressor;
    if (compressorName != null) {
      compressor = compressorRegistry.lookupCompressor(compressorName);
      // 如果设置了压缩器名称，但是没有相应的压缩器，则返回错误，关闭监听器
      if (compressor == null) {
        stream = NoopClientStream.INSTANCE;
        Status status = Status.INTERNAL.withDescription(String.format("Unable to find compressor by name %s", compressorName));
        executeCloseObserverInContext(observer, status);
        return;
      }
    } else {
      compressor = Codec.Identity.NONE;
    }

    // 根据参数添加 Header
    log.info("准备 Headers");
    prepareHeaders(headers, decompressorRegistry, compressor, fullStreamDecompression);

    // 最后期限
    log.info("准备 Deadline");
    Deadline effectiveDeadline = effectiveDeadline();
    boolean deadlineExceeded = effectiveDeadline != null && effectiveDeadline.isExpired();
    // 如果没有过期
    if (!deadlineExceeded) {
      // 如果超时则记录日志
      logIfContextNarrowedTimeout(effectiveDeadline, context.getDeadline(), callOptions.getDeadline());
      // 如果打开了重试，则创建重试流
      if (retryEnabled) {
        log.info("开启了重试，创建重试 Stream");
        stream = clientTransportProvider.newRetriableStream(method, callOptions, headers, context);
      } else {
        // 根据获取 ClientTransport
        ClientTransport transport = clientTransportProvider.get(new PickSubchannelArgsImpl(method, headers, callOptions));
        Context origContext = context.attach();
        try {
          // 创建流
          stream = transport.newStream(method, headers, callOptions);
        } finally {
          context.detach(origContext);
        }
      }
    } else {
      // 初始化超时失败的流
      stream = new FailingClientStream(DEADLINE_EXCEEDED.withDescription("ClientCall started after deadline exceeded: " + effectiveDeadline));
    }

    // 是否是直接执行
    if (callExecutorIsDirect) {
      stream.optimizeForDirectExecutor();
    }
    // 调用地址
    if (callOptions.getAuthority() != null) {
      stream.setAuthority(callOptions.getAuthority());
    }
    // 最大传入字节数
    if (callOptions.getMaxInboundMessageSize() != null) {
      stream.setMaxInboundMessageSize(callOptions.getMaxInboundMessageSize());
    }
    // 最大传出字节数
    if (callOptions.getMaxOutboundMessageSize() != null) {
      stream.setMaxOutboundMessageSize(callOptions.getMaxOutboundMessageSize());
    }
    // 最后执行时间
    if (effectiveDeadline != null) {
      stream.setDeadline(effectiveDeadline);
    }
    // 压缩器
    stream.setCompressor(compressor);
    // 解压缩流
    if (fullStreamDecompression) {
      stream.setFullStreamDecompression(fullStreamDecompression);
    }
    // 解压流注册器
    stream.setDecompressorRegistry(decompressorRegistry);
    // 记录开始调用
    channelCallsTracer.reportCallStarted();
    // 封装支持取消的流监听器
    cancellationListener = new ContextCancellationListener(observer);
    // 初始化支持 header 操作的 ClientStreamListener
    // 调用 start 方法，修改流的状态
    stream.start(new ClientStreamListenerImpl(observer));

    // Delay any sources of cancellation after start(), because most of the transports are broken if
    // they receive cancel before start. Issue #1343 has more details

    // Propagate later Context cancellation to the remote side.
    // 稍后将上下文取消传播到被调用端
    context.addListener(cancellationListener, directExecutor());

    if (effectiveDeadline != null
            // If the context has the effective deadline, we don't need to schedule an extra task.
            // 如果上下文具有有效的截止日期，则无需安排额外的任务
            && !effectiveDeadline.equals(context.getDeadline())
            // If the channel has been terminated, we don't need to schedule an extra task.
            // 如果 channel 已终止，则无需安排额外的任务
            && deadlineCancellationExecutor != null
            // if already expired deadline let failing stream handle
            // 如果截止时间已经到期，请让失败的流处理
            && !(stream instanceof FailingClientStream)) {
      // 提交任务，并返回回调
      deadlineCancellationNotifyApplicationFuture = startDeadlineNotifyApplicationTimer(effectiveDeadline, observer);
    }

    // 移除 context 和监听器
    if (cancelListenersShouldBeRemoved) {
      // Race detected! ClientStreamListener.closed may have been called before
      // deadlineCancellationFuture was set / context listener added, thereby preventing the future
      // and listener from being cancelled. Go ahead and cancel again, just to be sure it
      // was cancelled.
      removeContextListenerAndCancelDeadlineFuture();
    }
  }

  /**
   * 如果上下文超时则记录日志
   */
  private static void logIfContextNarrowedTimeout(Deadline effectiveDeadline,
                                                  @Nullable Deadline outerCallDeadline,
                                                  @Nullable Deadline callDeadline) {
    if (!log.isLoggable(Level.FINE) ||
            effectiveDeadline == null ||
            !effectiveDeadline.equals(outerCallDeadline)) {
      return;
    }

    long effectiveTimeout = max(0, effectiveDeadline.timeRemaining(TimeUnit.NANOSECONDS));
    StringBuilder builder = new StringBuilder(String.format("Call timeout set to '%d' ns, due to context deadline.", effectiveTimeout));

    if (callDeadline == null) {
      builder.append(" Explicit call timeout was not set.");
    } else {
      long callTimeout = callDeadline.timeRemaining(TimeUnit.NANOSECONDS);
      builder.append(String.format(" Explicit call timeout was '%d' ns.", callTimeout));
    }

    log.fine(builder.toString());
  }

  /**
   * 移除 context 和监听器
   */
  private void removeContextListenerAndCancelDeadlineFuture() {
    context.removeListener(cancellationListener);
    ScheduledFuture<?> f = deadlineCancellationSendToServerFuture;
    if (f != null) {
      f.cancel(false);
    }

    f = deadlineCancellationNotifyApplicationFuture;
    if (f != null) {
      f.cancel(false);
    }
  }

  /**
   * 根据超时时间，提交任务
   *
   * @param deadline
   * @param observer
   * @return
   */
  private ScheduledFuture<?> startDeadlineNotifyApplicationTimer(Deadline deadline,
                                                                 final Listener<RespT> observer) {
    // 计算剩余的时间
    final long remainingNanos = deadline.timeRemaining(TimeUnit.NANOSECONDS);

    // 超时则取消流
    class DeadlineExceededNotifyApplicationTimer implements Runnable {
      @Override
      public void run() {
        Status status = buildDeadlineExceededStatusWithRemainingNanos(remainingNanos);
        delayedCancelOnDeadlineExceeded(status, observer);
      }
    }

    // 执行延时任务
    return deadlineCancellationExecutor.schedule(
            new LogExceptionRunnable(new DeadlineExceededNotifyApplicationTimer()),
            remainingNanos,
            TimeUnit.NANOSECONDS);
  }

  /**
   * 根据剩余的时间构建超时状态信息
   *
   * @param remainingNanos
   * @return
   */
  private Status buildDeadlineExceededStatusWithRemainingNanos(long remainingNanos) {
    final InsightBuilder insight = new InsightBuilder();
    stream.appendTimeoutInsight(insight);

    long seconds = Math.abs(remainingNanos) / TimeUnit.SECONDS.toNanos(1);
    long nanos = Math.abs(remainingNanos) % TimeUnit.SECONDS.toNanos(1);

    StringBuilder buf = new StringBuilder();
    buf.append("deadline exceeded after ");
    if (remainingNanos < 0) {
      buf.append('-');
    }
    buf.append(seconds);
    buf.append(String.format(".%09d", nanos));
    buf.append("s. ");
    buf.append(insight);

    return DEADLINE_EXCEEDED.augmentDescription(buf.toString());
  }

  /**
   * 如果超时则取消流
   *
   * @param status
   * @param observer
   */
  private void delayedCancelOnDeadlineExceeded(final Status status, Listener<RespT> observer) {
    if (deadlineCancellationSendToServerFuture != null) {
      return;
    }

    class DeadlineExceededSendCancelToServerTimer implements Runnable {
      @Override
      public void run() {
        // DelayedStream.cancel() is safe to call from a thread that is different from where the
        // stream is created.
        stream.cancel(status);
      }
    }

    // This races with removeContextListenerAndCancelDeadlineFuture(). Since calling cancel() on a
    // stream multiple time is safe, the race here is fine.
    deadlineCancellationSendToServerFuture = deadlineCancellationExecutor.schedule(
            new LogExceptionRunnable(new DeadlineExceededSendCancelToServerTimer()),
            DEADLINE_EXPIRATION_CANCEL_DELAY_NANOS,
            TimeUnit.NANOSECONDS);
    executeCloseObserverInContext(observer, status);
  }

  /**
   * 关闭监听器
   *
   * @param observer
   * @param status
   */
  private void executeCloseObserverInContext(final Listener<RespT> observer, final Status status) {
    class CloseInContext extends ContextRunnable {
      CloseInContext() {
        super(context);
      }

      @Override
      public void runInContext() {
        closeObserver(observer, status, new Metadata());
      }
    }

    callExecutor.execute(new CloseInContext());
  }

  /**
   * 关闭监听器
   *
   * @param observer
   * @param status
   * @param trailers
   */
  private void closeObserver(Listener<RespT> observer, Status status, Metadata trailers) {
    if (!observerClosed) {
      observerClosed = true;
      observer.onClose(status, trailers);
    }
  }

  /**
   * 计算最后期限
   *
   * @return
   */
  @Nullable
  private Deadline effectiveDeadline() {
    // Call options and context are immutable, so we don't need to cache the deadline.
    return min(callOptions.getDeadline(), context.getDeadline());
  }

  @Nullable
  private static Deadline min(@Nullable Deadline deadline0, @Nullable Deadline deadline1) {
    if (deadline0 == null) {
      return deadline1;
    }
    if (deadline1 == null) {
      return deadline0;
    }
    return deadline0.minimum(deadline1);
  }

  /**
   * 将请求消息数量传递给监听器
   *
   * @param numMessages 要传递给 listener 的请求消息数量，不能为负数
   */
  @Override
  public void request(int numMessages) {
    PerfMark.startTask("ClientCall.request", tag);
    try {
      checkState(stream != null, "Not started");
      checkArgument(numMessages >= 0, "Number requested must be non-negative");
      stream.request(numMessages);
    } finally {
      PerfMark.stopTask("ClientCall.request", tag);
    }
  }

  @Override
  public void cancel(@Nullable String message, @Nullable Throwable cause) {
    PerfMark.startTask("ClientCall.cancel", tag);
    try {
      cancelInternal(message, cause);
    } finally {
      PerfMark.stopTask("ClientCall.cancel", tag);
    }
  }

  private void cancelInternal(@Nullable String message, @Nullable Throwable cause) {
    if (message == null && cause == null) {
      cause = new CancellationException("Cancelled without a message or cause");
      log.log(Level.WARNING, "Cancelling without a message or cause is suboptimal", cause);
    }
    if (cancelCalled) {
      return;
    }
    cancelCalled = true;
    try {
      // Cancel is called in exception handling cases, so it may be the case that the
      // stream was never successfully created or start has never been called.
      if (stream != null) {
        Status status = Status.CANCELLED;
        if (message != null) {
          status = status.withDescription(message);
        } else {
          status = status.withDescription("Call cancelled without message");
        }
        if (cause != null) {
          status = status.withCause(cause);
        }
        stream.cancel(status);
      }
    } finally {
      removeContextListenerAndCancelDeadlineFuture();
    }
  }

  /**
   * 关闭请求的消息发送调用，返回的响应不受影响，当客户端不会发送更多消息时调用
   */
  @Override
  public void halfClose() {
    PerfMark.startTask("ClientCall.halfClose", tag);
    try {
      halfCloseInternal();
    } finally {
      PerfMark.stopTask("ClientCall.halfClose", tag);
    }
  }

  private void halfCloseInternal() {
    checkState(stream != null, "Not started");
    checkState(!cancelCalled, "call was cancelled");
    checkState(!halfCloseCalled, "call already half-closed");
    halfCloseCalled = true;
    stream.halfClose();
  }

  /**
   * 执行发送消息
   *
   * @param message 发给服务端的消息
   */
  @Override
  public void sendMessage(ReqT message) {
    PerfMark.startTask("ClientCall.sendMessage", tag);
    try {
      sendMessageInternal(message);
    } finally {
      PerfMark.stopTask("ClientCall.sendMessage", tag);
    }
  }

  /**
   * 发送消息
   *
   * @param message
   */
  private void sendMessageInternal(ReqT message) {
    checkState(stream != null, "Not started");
    checkState(!cancelCalled, "call was cancelled");
    checkState(!halfCloseCalled, "call was half-closed");
    try {
      // 如果是重试流，则通过重试流的方法发送消息
      if (stream instanceof RetriableStream) {
        @SuppressWarnings("unchecked")
        RetriableStream<ReqT> retriableStream = (RetriableStream<ReqT>) stream;
        retriableStream.sendMessage(message);
      } else {
        // 不是重试流，将消息转为流，发送
        stream.writeMessage(method.streamRequest(message));
      }
    } catch (RuntimeException e) {
      // 如果出错则取消请求
      stream.cancel(Status.CANCELLED.withCause(e).withDescription("Failed to stream message"));
      return;
    } catch (Error e) {
      stream.cancel(Status.CANCELLED.withDescription("Client sendMessage() failed with Error"));
      throw e;
    }
    // For unary requests, we don't flush since we know that halfClose should be coming soon. This
    // allows us to piggy-back the END_STREAM=true on the last message frame without opening the
    // possibility of broken applications forgetting to call halfClose without noticing.
    // 对于 unary 请求，不用flush，因为接下来就是 halfClose, 这样就可以在消息最后搭载 END_STREAM=true，
    // 而无需打开损坏的流
    if (!unaryRequest) {
      stream.flush();
    }
  }

  @Override
  public void setMessageCompression(boolean enabled) {
    checkState(stream != null, "Not started");
    stream.setMessageCompression(enabled);
  }

  @Override
  public boolean isReady() {
    return stream.isReady();
  }

  @Override
  public Attributes getAttributes() {
    if (stream != null) {
      return stream.getAttributes();
    }
    return Attributes.EMPTY;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("method", method).toString();
  }

  /**
   * 客户端流事件监听器实现
   */
  private class ClientStreamListenerImpl implements ClientStreamListener {
    /**
     * 监听器
     */
    private final Listener<RespT> observer;
    /**
     * 是否关闭
     */
    private boolean closed;

    public ClientStreamListenerImpl(Listener<RespT> observer) {
      this.observer = checkNotNull(observer, "observer");
    }

    @Override
    public void headersRead(final Metadata headers) {
      PerfMark.startTask("ClientStreamListener.headersRead", tag);
      final Link link = PerfMark.linkOut();

      final class HeadersRead extends ContextRunnable {
        HeadersRead() {
          super(context);
        }

        @Override
        public void runInContext() {
          PerfMark.startTask("ClientCall$Listener.headersRead", tag);
          PerfMark.linkIn(link);
          try {
            runInternal();
          } finally {
            PerfMark.stopTask("ClientCall$Listener.headersRead", tag);
          }
        }

        private void runInternal() {
          if (closed) {
            return;
          }
          try {
            observer.onHeaders(headers);
          } catch (Throwable t) {
            Status status =
                Status.CANCELLED.withCause(t).withDescription("Failed to read headers");
            stream.cancel(status);
            close(status, new Metadata());
          }
        }
      }

      try {
        callExecutor.execute(new HeadersRead());
      } finally {
        PerfMark.stopTask("ClientStreamListener.headersRead", tag);
      }
    }

    @Override
    public void messagesAvailable(final MessageProducer producer) {
      PerfMark.startTask("ClientStreamListener.messagesAvailable", tag);
      final Link link = PerfMark.linkOut();

      final class MessagesAvailable extends ContextRunnable {
        MessagesAvailable() {
          super(context);
        }

        @Override
        public void runInContext() {
          PerfMark.startTask("ClientCall$Listener.messagesAvailable", tag);
          PerfMark.linkIn(link);
          try {
            runInternal();
          } finally {
            PerfMark.stopTask("ClientCall$Listener.messagesAvailable", tag);
          }
        }

        private void runInternal() {
          if (closed) {
            GrpcUtil.closeQuietly(producer);
            return;
          }
          try {
            InputStream message;
            while ((message = producer.next()) != null) {
              try {
                observer.onMessage(method.parseResponse(message));
              } catch (Throwable t) {
                GrpcUtil.closeQuietly(message);
                throw t;
              }
              message.close();
            }
          } catch (Throwable t) {
            GrpcUtil.closeQuietly(producer);
            Status status =
                Status.CANCELLED.withCause(t).withDescription("Failed to read message.");
            stream.cancel(status);
            close(status, new Metadata());
          }
        }
      }

      try {
        callExecutor.execute(new MessagesAvailable());
      } finally {
        PerfMark.stopTask("ClientStreamListener.messagesAvailable", tag);
      }
    }

    /**
     * 关闭流
     * Must be called from application thread.
     * 必须由应用线程调用
     */
    private void close(Status status, Metadata trailers) {
      closed = true;
      cancelListenersShouldBeRemoved = true;
      try {
        closeObserver(observer, status, trailers);
      } finally {
        removeContextListenerAndCancelDeadlineFuture();
        channelCallsTracer.reportCallEnded(status.isOk());
      }
    }

    @Override
    public void closed(Status status, Metadata trailers) {
      closed(status, RpcProgress.PROCESSED, trailers);
    }

    @Override
    public void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
      PerfMark.startTask("ClientStreamListener.closed", tag);
      try {
        // 关闭流
        closedInternal(status, rpcProgress, trailers);
      } finally {
        PerfMark.stopTask("ClientStreamListener.closed", tag);
      }
    }

    private void closedInternal(Status status, @SuppressWarnings("unused") RpcProgress rpcProgress, Metadata trailers) {
      // 获取最后调用时间
      Deadline deadline = effectiveDeadline();
      // 根据状态判断是否是取消，且有超时
      if (status.getCode() == Status.Code.CANCELLED && deadline != null) {
        // When the server's deadline expires, it can only reset the stream with CANCEL and no
        // description. Since our timer may be delayed in firing, we double-check the deadline and
        // turn the failure into the likely more helpful DEADLINE_EXCEEDED status.
        // 当达到最后的期限时，流只能被重置为 CANCEL 且没有描述，因为计时器可能会延时，所以做了双重检查，
        // 然后将状态变为更有用的 DEADLINE_EXCEEDED
        if (deadline.isExpired()) {
          // 追加错误信息
          InsightBuilder insight = new InsightBuilder();
          stream.appendTimeoutInsight(insight);
          status = DEADLINE_EXCEEDED.augmentDescription("ClientCall was cancelled at or after deadline. " + insight);
          // Replace trailers to prevent mixing sources of status and trailers.
          // 更换 trailers，以防止状态和 trailers 混淆
          trailers = new Metadata();
        }
      }
      final Status savedStatus = status;
      final Metadata savedTrailers = trailers;
      final Link link = PerfMark.linkOut();

      // 构建 runnable 任务
      final class StreamClosed extends ContextRunnable {
        StreamClosed() {
          super(context);
        }

        @Override
        public void runInContext() {
          PerfMark.startTask("ClientCall$Listener.onClose", tag);
          PerfMark.linkIn(link);
          try {
            runInternal();
          } finally {
            PerfMark.stopTask("ClientCall$Listener.onClose", tag);
          }
        }

        private void runInternal() {
          // 如果已经关闭了则直接返回
          if (closed) {
            // We intentionally don't keep the status or metadata from the server.
            return;
          }
          close(savedStatus, savedTrailers);
        }
      }

      // 执行关闭流的任务
      callExecutor.execute(new StreamClosed());
    }

    /**
     * 监听器 ready
     */
    @Override
    public void onReady() {
      if (method.getType().clientSendsOneMessage()) {
        return;
      }

      PerfMark.startTask("ClientStreamListener.onReady", tag);
      final Link link = PerfMark.linkOut();

      final class StreamOnReady extends ContextRunnable {
        StreamOnReady() {
          super(context);
        }

        @Override
        public void runInContext() {
          PerfMark.startTask("ClientCall$Listener.onReady", tag);
          PerfMark.linkIn(link);
          try {
            runInternal();
          } finally {
            PerfMark.stopTask("ClientCall$Listener.onReady", tag);
          }
        }

        private void runInternal() {
          try {
            observer.onReady();
          } catch (Throwable t) {
            Status status =
                Status.CANCELLED.withCause(t).withDescription("Failed to call onReady.");
            stream.cancel(status);
            close(status, new Metadata());
          }
        }
      }

      try {
        callExecutor.execute(new StreamOnReady());
      } finally {
        PerfMark.stopTask("ClientStreamListener.onReady", tag);
      }
    }
  }
}
