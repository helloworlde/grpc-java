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

package io.grpc.stub;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Utility functions for processing different call idioms. We have one-to-one correspondence
 * between utilities in this class and the potential signatures in a generated stub class so
 * that the runtime can vary behavior without requiring regeneration of the stub.
 */
public final class ClientCalls {

  private static final Logger logger = Logger.getLogger(ClientCalls.class.getName());

  // Prevent instantiation
  private ClientCalls() {}

  /**
   * Executes a unary call with a response {@link StreamObserver}.  The {@code call} should not be
   * already started.  After calling this method, {@code call} should no longer be used.
   *
   * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
   * {@code beforeStart()} will be called.
   */
  public static <ReqT, RespT> void asyncUnaryCall(
      ClientCall<ReqT, RespT> call, ReqT req, StreamObserver<RespT> responseObserver) {
    asyncUnaryRequestCall(call, req, responseObserver, false);
  }

  /**
   * Executes a server-streaming call with a response {@link StreamObserver}.  The {@code call}
   * should not be already started.  After calling this method, {@code call} should no longer be
   * used.
   *
   * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
   * {@code beforeStart()} will be called.
   */
  public static <ReqT, RespT> void asyncServerStreamingCall(
      ClientCall<ReqT, RespT> call, ReqT req, StreamObserver<RespT> responseObserver) {
    asyncUnaryRequestCall(call, req, responseObserver, true);
  }

  /**
   * Executes a client-streaming call returning a {@link StreamObserver} for the request messages.
   * The {@code call} should not be already started.  After calling this method, {@code call}
   * should no longer be used.
   *
   * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
   * {@code beforeStart()} will be called.
   *
   * @return request stream observer. It will extend {@link ClientCallStreamObserver}
   */
  public static <ReqT, RespT> StreamObserver<ReqT> asyncClientStreamingCall(
      ClientCall<ReqT, RespT> call,
      StreamObserver<RespT> responseObserver) {
    return asyncStreamingRequestCall(call, responseObserver, false);
  }

  /**
   * Executes a bidirectional-streaming call.  The {@code call} should not be already started.
   * After calling this method, {@code call} should no longer be used.
   *
   * <p>If the provided {@code responseObserver} is an instance of {@link ClientResponseObserver},
   * {@code beforeStart()} will be called.
   *
   * @return request stream observer. It will extend {@link ClientCallStreamObserver}
   */
  public static <ReqT, RespT> StreamObserver<ReqT> asyncBidiStreamingCall(
      ClientCall<ReqT, RespT> call, StreamObserver<RespT> responseObserver) {
    return asyncStreamingRequestCall(call, responseObserver, true);
  }

  /**
   * Executes a unary call and blocks on the response.  The {@code call} should not be already
   * started.  After calling this method, {@code call} should no longer be used.
   * <p>
   * 执行 unary 调用并阻塞等待响应，call 在此之前不应当开始，调用这个方法之后，call 不应当再使用
   *
   * @return the single response message 单个的响应
   * @throws StatusRuntimeException on error
   */
  public static <ReqT, RespT> RespT blockingUnaryCall(ClientCall<ReqT, RespT> call, ReqT req) {
    try {
      return getUnchecked(futureUnaryCall(call, req));
    } catch (RuntimeException e) {
      throw cancelThrow(call, e);
    } catch (Error e) {
      throw cancelThrow(call, e);
    }
  }

  /**
   * TODO 执行调用入口
   * Executes a unary call and blocks on the response.  The {@code call} should not be already
   * started.  After calling this method, {@code call} should no longer be used.
   * <p>
   * 执行 UNARY 调用，等待返回，当调用完后，call不应该再调用
   *
   * @return the single response message.
   * @throws StatusRuntimeException on error
   */
  public static <ReqT, RespT> RespT blockingUnaryCall(Channel channel,
                                                      MethodDescriptor<ReqT, RespT> method,
                                                      CallOptions callOptions,
                                                      ReqT req) {
    // 构建任务队列和单线程的线程池
    ThreadlessExecutor executor = new ThreadlessExecutor();
    boolean interrupt = false;
    // 创建新的调用的 ClientCall，指定了调用类型和执行器
    ClientCall<ReqT, RespT> call = channel.newCall(method, callOptions.withOption(ClientCalls.STUB_TYPE_OPTION, StubType.BLOCKING)
                                                                      .withExecutor(executor));
    try {
      // 执行调用，发出请求
      ListenableFuture<RespT> responseFuture = futureUnaryCall(call, req);
      while (!responseFuture.isDone()) {
        try {
          executor.waitAndDrain();
        } catch (InterruptedException e) {
          interrupt = true;
          call.cancel("Thread interrupted", e);
          // Now wait for onClose() to be called, so interceptors can clean up
        }
      }
      return getUnchecked(responseFuture);
    } catch (RuntimeException e) {
      // Something very bad happened. All bets are off; it may be dangerous to wait for onClose().
      throw cancelThrow(call, e);
    } catch (Error e) {
      // Something very bad happened. All bets are off; it may be dangerous to wait for onClose().
      throw cancelThrow(call, e);
    } finally {
      if (interrupt) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Executes a server-streaming call returning a blocking {@link Iterator} over the
   * response stream.  The {@code call} should not be already started.  After calling this method,
   * {@code call} should no longer be used.
   *
   * <p>The returned iterator may throw {@link StatusRuntimeException} on error.
   *
   * @return an iterator over the response stream.
   */
  // TODO(louiscryan): Not clear if we want to use this idiom for 'simple' stubs.
  public static <ReqT, RespT> Iterator<RespT> blockingServerStreamingCall(
      ClientCall<ReqT, RespT> call, ReqT req) {
    BlockingResponseStream<RespT> result = new BlockingResponseStream<>(call);
    asyncUnaryRequestCall(call, req, result.listener());
    return result;
  }

  /**
   * Executes a server-streaming call returning a blocking {@link Iterator} over the
   * response stream.  The {@code call} should not be already started.  After calling this method,
   * {@code call} should no longer be used.
   *
   * <p>The returned iterator may throw {@link StatusRuntimeException} on error.
   *
   * @return an iterator over the response stream.
   */
  // TODO(louiscryan): Not clear if we want to use this idiom for 'simple' stubs.
  public static <ReqT, RespT> Iterator<RespT> blockingServerStreamingCall(
      Channel channel, MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, ReqT req) {
    ThreadlessExecutor executor = new ThreadlessExecutor();
    ClientCall<ReqT, RespT> call = channel.newCall(method,
        callOptions.withOption(ClientCalls.STUB_TYPE_OPTION, StubType.BLOCKING)
            .withExecutor(executor));
    BlockingResponseStream<RespT> result = new BlockingResponseStream<>(call, executor);
    asyncUnaryRequestCall(call, req, result.listener());
    return result;
  }

  /**
   * Executes a unary call and returns a {@link ListenableFuture} to the response.  The
   * {@code call} should not be already started.  After calling this method, {@code call} should no
   * longer be used.
   * 执行 unary 调用，并返回 ListenableFuture，在调用之前不应该开始，调用后不应该再被使用
   *
   * @return a future for the single response message 返回用于单个消息响应的 Future
   */
  public static <ReqT, RespT> ListenableFuture<RespT> futureUnaryCall(ClientCall<ReqT, RespT> call, ReqT req) {
    // 初始化 GrpcFuture
    GrpcFuture<RespT> responseFuture = new GrpcFuture<>(call);
    // 将 GrpcFuture 包装为继承了 Listener 的 UnaryStreamToFuture，提交任务
    asyncUnaryRequestCall(call, req, new UnaryStreamToFuture<>(responseFuture));
    return responseFuture;
  }

  /**
   * Returns the result of calling {@link Future#get()} interruptibly on a task known not to throw a
   * checked exception.
   *
   * <p>If interrupted, the interrupt is restored before throwing an exception..
   *
   * @throws java.util.concurrent.CancellationException
   *     if {@code get} throws a {@code CancellationException}.
   * @throws io.grpc.StatusRuntimeException if {@code get} throws an {@link ExecutionException}
   *     or an {@link InterruptedException}.
   */
  private static <V> V getUnchecked(Future<V> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Status.CANCELLED
          .withDescription("Thread interrupted")
          .withCause(e)
          .asRuntimeException();
    } catch (ExecutionException e) {
      throw toStatusRuntimeException(e.getCause());
    }
  }

  /**
   * Wraps the given {@link Throwable} in a {@link StatusRuntimeException}. If it contains an
   * embedded {@link StatusException} or {@link StatusRuntimeException}, the returned exception will
   * contain the embedded trailers and status, with the given exception as the cause. Otherwise, an
   * exception will be generated from an {@link Status#UNKNOWN} status.
   */
  private static StatusRuntimeException toStatusRuntimeException(Throwable t) {
    Throwable cause = checkNotNull(t, "t");
    while (cause != null) {
      // If we have an embedded status, use it and replace the cause
      if (cause instanceof StatusException) {
        StatusException se = (StatusException) cause;
        return new StatusRuntimeException(se.getStatus(), se.getTrailers());
      } else if (cause instanceof StatusRuntimeException) {
        StatusRuntimeException se = (StatusRuntimeException) cause;
        return new StatusRuntimeException(se.getStatus(), se.getTrailers());
      }
      cause = cause.getCause();
    }
    return Status.UNKNOWN.withDescription("unexpected exception").withCause(t)
        .asRuntimeException();
  }

  /**
   * Cancels a call, and throws the exception.
   *
   * @param t must be a RuntimeException or Error
   */
  private static RuntimeException cancelThrow(ClientCall<?, ?> call, Throwable t) {
    try {
      call.cancel(null, t);
    } catch (Throwable e) {
      assert e instanceof RuntimeException || e instanceof Error;
      logger.log(Level.SEVERE, "RuntimeException encountered while closing call", e);
    }
    if (t instanceof RuntimeException) {
      throw (RuntimeException) t;
    } else if (t instanceof Error) {
      throw (Error) t;
    }
    // should be impossible
    throw new AssertionError(t);
  }

  private static <ReqT, RespT> void asyncUnaryRequestCall(
      ClientCall<ReqT, RespT> call, ReqT req, StreamObserver<RespT> responseObserver,
      boolean streamingResponse) {
    asyncUnaryRequestCall(
        call,
        req,
        new StreamObserverToCallListenerAdapter<>(
            responseObserver,
            new CallToStreamObserverAdapter<>(call, streamingResponse)));
  }

  /**
   * 开始异步调用
   *
   * @param call             ClientCallImpl 实例
   * @param req              请求，proto 生成的入参
   * @param responseListener 继承了 Listener，封装 GrpcFuture 的 UnaryStreamToFuture
   */
  private static <ReqT, RespT> void asyncUnaryRequestCall(ClientCall<ReqT, RespT> call,
                                                          ReqT req,
                                                          StartableListener<RespT> responseListener) {
    // 开始调用
    startCall(call, responseListener);
    try {
      // 发送消息，提交 BufferEntry 任务
      call.sendMessage(req);
      // 从客户端关闭流
      call.halfClose();
    } catch (RuntimeException e) {
      throw cancelThrow(call, e);
    } catch (Error e) {
      throw cancelThrow(call, e);
    }
  }

  private static <ReqT, RespT> StreamObserver<ReqT> asyncStreamingRequestCall(
      ClientCall<ReqT, RespT> call,
      StreamObserver<RespT> responseObserver,
      boolean streamingResponse) {
    CallToStreamObserverAdapter<ReqT> adapter = new CallToStreamObserverAdapter<>(
        call, streamingResponse);
    startCall(
        call,
        new StreamObserverToCallListenerAdapter<>(responseObserver, adapter));
    return adapter;
  }

  /**
   * 发起调用
   * @param call ClientCallImpl 调用实现类
   * @param responseListener 响应监听器
   * @param <ReqT> 请求
   * @param <RespT> 响应
   */
  private static <ReqT, RespT> void startCall(ClientCall<ReqT, RespT> call,
                                              StartableListener<RespT> responseListener) {
    // 通过 ClientCallImpl 调用 start
    call.start(responseListener, new Metadata());
    // 启动监听器，延迟执行 Stream 的 request 方法
    responseListener.onStart();
  }

  private abstract static class StartableListener<T> extends ClientCall.Listener<T> {
    abstract void onStart();
  }

  private static final class CallToStreamObserverAdapter<T> extends ClientCallStreamObserver<T> {
    private boolean frozen;
    private final ClientCall<T, ?> call;
    private final boolean streamingResponse;
    private Runnable onReadyHandler;
    private int initialRequest = 1;
    private boolean autoRequestEnabled = true;
    private boolean aborted = false;
    private boolean completed = false;

    // Non private to avoid synthetic class
    CallToStreamObserverAdapter(ClientCall<T, ?> call, boolean streamingResponse) {
      this.call = call;
      this.streamingResponse = streamingResponse;
    }

    private void freeze() {
      this.frozen = true;
    }

    @Override
    public void onNext(T value) {
      checkState(!aborted, "Stream was terminated by error, no further calls are allowed");
      checkState(!completed, "Stream is already completed, no further calls are allowed");
      call.sendMessage(value);
    }

    @Override
    public void onError(Throwable t) {
      call.cancel("Cancelled by client with StreamObserver.onError()", t);
      aborted = true;
    }

    @Override
    public void onCompleted() {
      call.halfClose();
      completed = true;
    }

    @Override
    public boolean isReady() {
      return call.isReady();
    }

    @Override
    public void setOnReadyHandler(Runnable onReadyHandler) {
      if (frozen) {
        throw new IllegalStateException(
            "Cannot alter onReadyHandler after call started. Use ClientResponseObserver");
      }
      this.onReadyHandler = onReadyHandler;
    }

    @Deprecated
    @Override
    public void disableAutoInboundFlowControl() {
      disableAutoRequestWithInitial(1);
    }

    @Override
    public void disableAutoRequestWithInitial(int request) {
      if (frozen) {
        throw new IllegalStateException(
            "Cannot disable auto flow control after call started. Use ClientResponseObserver");
      }
      Preconditions.checkArgument(request >= 0, "Initial requests must be non-negative");
      initialRequest = request;
      autoRequestEnabled = false;
    }

    @Override
    public void request(int count) {
      if (!streamingResponse && count == 1) {
        // Initially ask for two responses from flow-control so that if a misbehaving server
        // sends more than one responses, we can catch it and fail it in the listener.
        call.request(2);
      } else {
        call.request(count);
      }
    }

    @Override
    public void setMessageCompression(boolean enable) {
      call.setMessageCompression(enable);
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
      call.cancel(message, cause);
    }
  }

  private static final class StreamObserverToCallListenerAdapter<ReqT, RespT>
      extends StartableListener<RespT> {
    private final StreamObserver<RespT> observer;
    private final CallToStreamObserverAdapter<ReqT> adapter;
    private boolean firstResponseReceived;

    // Non private to avoid synthetic class
    StreamObserverToCallListenerAdapter(
        StreamObserver<RespT> observer,
        CallToStreamObserverAdapter<ReqT> adapter) {
      this.observer = observer;
      this.adapter = adapter;
      if (observer instanceof ClientResponseObserver) {
        @SuppressWarnings("unchecked")
        ClientResponseObserver<ReqT, RespT> clientResponseObserver =
            (ClientResponseObserver<ReqT, RespT>) observer;
        clientResponseObserver.beforeStart(adapter);
      }
      adapter.freeze();
    }

    @Override
    public void onHeaders(Metadata headers) {
    }

    @Override
    public void onMessage(RespT message) {
      if (firstResponseReceived && !adapter.streamingResponse) {
        throw Status.INTERNAL
            .withDescription("More than one responses received for unary or client-streaming call")
            .asRuntimeException();
      }
      firstResponseReceived = true;
      observer.onNext(message);

      if (adapter.streamingResponse && adapter.autoRequestEnabled) {
        // Request delivery of the next inbound message.
        adapter.request(1);
      }
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      if (status.isOk()) {
        observer.onCompleted();
      } else {
        observer.onError(status.asRuntimeException(trailers));
      }
    }

    @Override
    public void onReady() {
      if (adapter.onReadyHandler != null) {
        adapter.onReadyHandler.run();
      }
    }

    @Override
    void onStart() {
      if (adapter.initialRequest > 0) {
        adapter.request(adapter.initialRequest);
      }
    }
  }

  /**
   * Completes a {@link GrpcFuture} using {@link StreamObserver} events.
   * 使用 StreamObserver 事件完成 GrpcFuture，继承了 io.grpc.ClientCall.Listener
   */
  private static final class UnaryStreamToFuture<RespT> extends StartableListener<RespT> {
    private final GrpcFuture<RespT> responseFuture;
    private RespT value;

    // Non private to avoid synthetic class

    /**
     * 使用 GrpcFuture 初始化 UnaryStreamToFuture
     *
     * @param responseFuture
     */
    UnaryStreamToFuture(GrpcFuture<RespT> responseFuture) {
      this.responseFuture = responseFuture;
    }

    @Override
    public void onHeaders(Metadata headers) {
    }

    @Override
    public void onMessage(RespT value) {
      if (this.value != null) {
        throw Status.INTERNAL.withDescription("More than one value received for unary call")
            .asRuntimeException();
      }
      this.value = value;
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      if (status.isOk()) {
        if (value == null) {
          // No value received so mark the future as an error
          responseFuture.setException(
              Status.INTERNAL.withDescription("No value received for unary call")
                  .asRuntimeException(trailers));
        }
        responseFuture.set(value);
      } else {
        responseFuture.setException(status.asRuntimeException(trailers));
      }
    }

    /**
     * 启动监听器
     */
    @Override
    void onStart() {
      responseFuture.call.request(2);
    }
  }

  private static final class GrpcFuture<RespT> extends AbstractFuture<RespT> {
    private final ClientCall<?, RespT> call;

    // Non private to avoid synthetic class

    /**
     * 初始化 GrpcFuture，用于获取响应
     *
     * @param call
     */
    GrpcFuture(ClientCall<?, RespT> call) {
      this.call = call;
    }

    @Override
    protected void interruptTask() {
      call.cancel("GrpcFuture was cancelled", null);
    }

    @Override
    protected boolean set(@Nullable RespT resp) {
      return super.set(resp);
    }

    @Override
    protected boolean setException(Throwable throwable) {
      return super.setException(throwable);
    }

    @SuppressWarnings("MissingOverride") // Add @Override once Java 6 support is dropped
    protected String pendingToString() {
      return MoreObjects.toStringHelper(this).add("clientCall", call).toString();
    }
  }

  /**
   * Convert events on a {@link io.grpc.ClientCall.Listener} into a blocking {@link Iterator}.
   *
   * <p>The class is not thread-safe, but it does permit {@link ClientCall.Listener} calls in a
   * separate thread from {@link Iterator} calls.
   */
  // TODO(ejona86): determine how to allow ClientCall.cancel() in case of application error.
  private static final class BlockingResponseStream<T> implements Iterator<T> {
    // Due to flow control, only needs to hold up to 3 items: 2 for value, 1 for close.
    // (2 for value, not 1, because of early request() in next())
    private final BlockingQueue<Object> buffer = new ArrayBlockingQueue<>(3);
    private final StartableListener<T> listener = new QueuingListener();
    private final ClientCall<?, T> call;
    /** May be null. */
    private final ThreadlessExecutor threadless;
    // Only accessed when iterating.
    private Object last;

    // Non private to avoid synthetic class
    BlockingResponseStream(ClientCall<?, T> call) {
      this(call, null);
    }

    // Non private to avoid synthetic class
    BlockingResponseStream(ClientCall<?, T> call, ThreadlessExecutor threadless) {
      this.call = call;
      this.threadless = threadless;
    }

    StartableListener<T> listener() {
      return listener;
    }

    private Object waitForNext() {
      boolean interrupt = false;
      try {
        if (threadless == null) {
          while (true) {
            try {
              return buffer.take();
            } catch (InterruptedException ie) {
              interrupt = true;
              call.cancel("Thread interrupted", ie);
              // Now wait for onClose() to be called, to guarantee BlockingQueue doesn't fill
            }
          }
        } else {
          Object next;
          while ((next = buffer.poll()) == null) {
            try {
              threadless.waitAndDrain();
            } catch (InterruptedException ie) {
              interrupt = true;
              call.cancel("Thread interrupted", ie);
              // Now wait for onClose() to be called, so interceptors can clean up
            }
          }
          return next;
        }
      } finally {
        if (interrupt) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public boolean hasNext() {
      while (last == null) {
        // Will block here indefinitely waiting for content. RPC timeouts defend against permanent
        // hangs here as the call will become closed.
        last = waitForNext();
      }
      if (last instanceof StatusRuntimeException) {
        // Rethrow the exception with a new stacktrace.
        StatusRuntimeException e = (StatusRuntimeException) last;
        throw e.getStatus().asRuntimeException(e.getTrailers());
      }
      return last != this;
    }

    @Override
    public T next() {
      // Eagerly call request(1) so it can be processing the next message while we wait for the
      // current one, which reduces latency for the next message. With MigratingThreadDeframer and
      // if the data has already been recieved, every other message can be delivered instantly. This
      // can be run after hasNext(), but just would be slower.
      if (!(last instanceof StatusRuntimeException) && last != this) {
        call.request(1);
      }
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      @SuppressWarnings("unchecked")
      T tmp = (T) last;
      last = null;
      return tmp;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

    private final class QueuingListener extends StartableListener<T> {
      // Non private to avoid synthetic class
      QueuingListener() {}

      private boolean done = false;

      @Override
      public void onHeaders(Metadata headers) {
      }

      @Override
      public void onMessage(T value) {
        Preconditions.checkState(!done, "ClientCall already closed");
        buffer.add(value);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        Preconditions.checkState(!done, "ClientCall already closed");
        if (status.isOk()) {
          buffer.add(BlockingResponseStream.this);
        } else {
          buffer.add(status.asRuntimeException(trailers));
        }
        done = true;
      }

      @Override
      void onStart() {
        call.request(1);
      }
    }
  }

  /**
   * 用于从队列中拉取任务并执行，只有一个线程
   */
  @SuppressWarnings("serial")
  private static final class ThreadlessExecutor extends ConcurrentLinkedQueue<Runnable> implements Executor {

    private static final Logger log = Logger.getLogger(ThreadlessExecutor.class.getName());

    private volatile Thread waiter;

    // Non private to avoid synthetic class
    ThreadlessExecutor() {
    }

    /**
     * Waits until there is a Runnable, then executes it and all queued Runnables after it.
     * Must only be called by one thread at a time.
     * <p>
     * 等待执行队列中的 Runnable，一次只能有一个线程调用
     */
    public void waitAndDrain() throws InterruptedException {
      // 如果线程已经被打断了，则抛出异常
      throwIfInterrupted();
      // 从队列中获取任务
      Runnable runnable = poll();
      // 如果没有拉取到任务，那么让当前线程等待
      if (runnable == null) {
        waiter = Thread.currentThread();
        try {
          // 遍历拉取
          while ((runnable = poll()) == null) {
            LockSupport.park(this);
            throwIfInterrupted();
          }
        } finally {
          waiter = null;
        }
      }
      // 当有任务时不断拉取执行
      do {
        try {
          runnable.run();
        } catch (Throwable t) {
          log.log(Level.WARNING, "Runnable threw exception", t);
        }
      } while ((runnable = poll()) != null);
    }

    /**
     * 当线程被打断时抛出异常
     *
     * @throws InterruptedException
     */
    private static void throwIfInterrupted() throws InterruptedException {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
    }

    /**
     * 执行任务，将任务添加到队列中
     *
     * @param runnable
     */
    @Override
    public void execute(Runnable runnable) {
      add(runnable);
      LockSupport.unpark(waiter); // no-op if null
    }
  }

  enum StubType {
    BLOCKING, FUTURE, ASYNC
  }

  /**
   * Internal {@link CallOptions.Key} to indicate stub types.
   */
  static final CallOptions.Key<StubType> STUB_TYPE_OPTION =
      CallOptions.Key.create("internal-stub-type");
}
