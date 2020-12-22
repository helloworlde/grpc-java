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

package io.grpc.census;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.Context;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerStreamTracer;
import io.grpc.StreamTracer;
import io.opencensus.trace.BlankSpan;
import io.opencensus.trace.EndSpanOptions;
import io.opencensus.trace.MessageEvent;
import io.opencensus.trace.MessageEvent.Type;
import io.opencensus.trace.Span;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.Status;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.propagation.BinaryFormat;
import io.opencensus.trace.unsafe.ContextUtils;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Provides factories for {@link StreamTracer} that records traces to Census.
 * 为 StreamTracer 提供工厂用于记录 trace 信息到 Census
 *
 * <p>On the client-side, a factory is created for each call, because ClientCall starts earlier than
 * the ClientStream, and in some cases may even not create a ClientStream at all.  Therefore, it's
 * the factory that reports the summary to Census.
 * 在客户端，每个调用都会创建一个工厂，因为 ClientCall 早于 ClientStream 开始，有些情况下可能不创建 ClientStream,
 * 因此，由工厂上报统计信息给 Census
 *
 * <p>On the server-side, there is only one ServerStream per each ServerCall, and ServerStream
 * starts earlier than the ServerCall.  Therefore, only one tracer is created per stream/call and
 * it's the tracer that reports the summary to Census.
 * 在 Server 端，因为每个 ServerCall 只有一个 ServerStream，并且 ServerStream 早于 ServerCall 创建，
 * 因此，每个流仅有一个 tracer 创建，并且由 tracer 上报统计给 Census
 */
final class CensusTracingModule {
    private static final Logger logger = Logger.getLogger(CensusTracingModule.class.getName());

    @Nullable
    private static final AtomicIntegerFieldUpdater<ClientCallTracer> callEndedUpdater;

    @Nullable
    private static final AtomicIntegerFieldUpdater<ServerTracer> streamClosedUpdater;

    /**
     * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their JDK
     * reflection API that triggers a NoSuchFieldException. When this occurs, we fallback to
     * (potentially racy) direct updates of the volatile variables.
     */
    static {
        AtomicIntegerFieldUpdater<ClientCallTracer> tmpCallEndedUpdater;
        AtomicIntegerFieldUpdater<ServerTracer> tmpStreamClosedUpdater;
        try {
            tmpCallEndedUpdater = AtomicIntegerFieldUpdater.newUpdater(ClientCallTracer.class, "callEnded");
            tmpStreamClosedUpdater = AtomicIntegerFieldUpdater.newUpdater(ServerTracer.class, "streamClosed");
        } catch (Throwable t) {
            logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
            tmpCallEndedUpdater = null;
            tmpStreamClosedUpdater = null;
        }
        callEndedUpdater = tmpCallEndedUpdater;
        streamClosedUpdater = tmpStreamClosedUpdater;
    }

    private final Tracer censusTracer;

    @VisibleForTesting
    final Metadata.Key<SpanContext> tracingHeader;

    // 跟踪拦截器
    private final TracingClientInterceptor clientInterceptor = new TracingClientInterceptor();

    // Server Tracer 工厂
    private final ServerTracerFactory serverTracerFactory = new ServerTracerFactory();

    /**
     * Census
     */
    CensusTracingModule(Tracer censusTracer,
                        final BinaryFormat censusPropagationBinaryFormat) {
        this.censusTracer = checkNotNull(censusTracer, "censusTracer");
        checkNotNull(censusPropagationBinaryFormat, "censusPropagationBinaryFormat");

        // 创建 TracerHeader
        this.tracingHeader = Metadata.Key.of("grpc-trace-bin", new Metadata.BinaryMarshaller<SpanContext>() {
            @Override
            public byte[] toBytes(SpanContext context) {
                return censusPropagationBinaryFormat.toByteArray(context);
            }

            @Override
            public SpanContext parseBytes(byte[] serialized) {
                try {
                    return censusPropagationBinaryFormat.fromByteArray(serialized);
                } catch (Exception e) {
                    logger.log(Level.FINE, "Failed to parse tracing header", e);
                    return SpanContext.INVALID;
                }
            }
        });
    }

    /**
     * Creates a {@link ClientCallTracer} for a new call.
     * 为新的调用创建 ClientCallTracer
     */
    @VisibleForTesting
    ClientCallTracer newClientCallTracer(@Nullable Span parentSpan, MethodDescriptor<?, ?> method) {
        return new ClientCallTracer(parentSpan, method);
    }

    /**
     * Returns the server tracer factory.
     */
    ServerStreamTracer.Factory getServerTracerFactory() {
        return serverTracerFactory;
    }

    /**
     * Returns the client interceptor that facilitates Census-based stats reporting.
     */
    ClientInterceptor getClientInterceptor() {
        return clientInterceptor;
    }

    /**
     * 将 gRPC 的状态转换为 opencensus 的状态
     */
    @VisibleForTesting
    static Status convertStatus(io.grpc.Status grpcStatus) {
        Status status;
        switch (grpcStatus.getCode()) {
            case OK:
                status = Status.OK;
                break;
            case CANCELLED:
                status = Status.CANCELLED;
                break;
            case UNKNOWN:
                status = Status.UNKNOWN;
                break;
            case INVALID_ARGUMENT:
                status = Status.INVALID_ARGUMENT;
                break;
            case DEADLINE_EXCEEDED:
                status = Status.DEADLINE_EXCEEDED;
                break;
            case NOT_FOUND:
                status = Status.NOT_FOUND;
                break;
            case ALREADY_EXISTS:
                status = Status.ALREADY_EXISTS;
                break;
            case PERMISSION_DENIED:
                status = Status.PERMISSION_DENIED;
                break;
            case RESOURCE_EXHAUSTED:
                status = Status.RESOURCE_EXHAUSTED;
                break;
            case FAILED_PRECONDITION:
                status = Status.FAILED_PRECONDITION;
                break;
            case ABORTED:
                status = Status.ABORTED;
                break;
            case OUT_OF_RANGE:
                status = Status.OUT_OF_RANGE;
                break;
            case UNIMPLEMENTED:
                status = Status.UNIMPLEMENTED;
                break;
            case INTERNAL:
                status = Status.INTERNAL;
                break;
            case UNAVAILABLE:
                status = Status.UNAVAILABLE;
                break;
            case DATA_LOSS:
                status = Status.DATA_LOSS;
                break;
            case UNAUTHENTICATED:
                status = Status.UNAUTHENTICATED;
                break;
            default:
                throw new AssertionError("Unhandled status code " + grpcStatus.getCode());
        }
        if (grpcStatus.getDescription() != null) {
            status = status.withDescription(grpcStatus.getDescription());
        }
        return status;
    }

    /**
     * 创建结束的 Span
     */
    private static EndSpanOptions createEndSpanOptions(io.grpc.Status status,
                                                       boolean sampledToLocalTracing) {
        return EndSpanOptions.builder()
                             .setStatus(convertStatus(status))
                             .setSampleToLocalSpanStore(sampledToLocalTracing)
                             .build();
    }

    /**
     * 上报消息事件
     *
     * @param span                     Tracer 的 Span
     * @param type                     消息类型
     * @param seqNo                    消息序号
     * @param optionalWireSize         消息的线号，如果未知则是 -1
     * @param optionalUncompressedSize 未压缩消息大小
     */
    private static void recordMessageEvent(Span span,
                                           MessageEvent.Type type,
                                           int seqNo,
                                           long optionalWireSize,
                                           long optionalUncompressedSize) {

        // 构建消息类型
        MessageEvent.Builder eventBuilder = MessageEvent.builder(type, seqNo);

        // 未压缩消息大小
        if (optionalUncompressedSize != -1) {
            eventBuilder.setUncompressedMessageSize(optionalUncompressedSize);
        }

        // 消息线号
        if (optionalWireSize != -1) {
            eventBuilder.setCompressedMessageSize(optionalWireSize);
        }
        // 向 Span 添加事件类型
        span.addMessageEvent(eventBuilder.build());
    }

    /**
     * 用于跟踪 Trace 的 StreamTracer.Factory
     */
    @VisibleForTesting
    final class ClientCallTracer extends ClientStreamTracer.Factory {
        volatile int callEnded;

        private final boolean isSampledToLocalTracing;
        private final Span span;

        ClientCallTracer(@Nullable Span parentSpan, MethodDescriptor<?, ?> method) {
            checkNotNull(method, "method");
            this.isSampledToLocalTracing = method.isSampledToLocalTracing();
            // 启动 Span
            this.span = censusTracer.spanBuilderWithExplicitParent(generateTraceSpanName(false, method.getFullMethodName()), parentSpan)
                                    .setRecordEvents(true)
                                    .startSpan();
        }

        /**
         * 创建新的 ClientStreamTracer
         */
        @Override
        public ClientStreamTracer newClientStreamTracer(ClientStreamTracer.StreamInfo info,
                                                        Metadata headers) {
            // 如果 span 不是空白的，则将上下文放入 header 中
            if (span != BlankSpan.INSTANCE) {
                headers.discardAll(tracingHeader);
                headers.put(tracingHeader, span.getContext());
            }
            return new ClientTracer(span);
        }

        /**
         * Record a finished call and mark the current time as the end time.
         * 记录调用完成，标记当前时间为结束时间
         *
         * <p>Can be called from any thread without synchronization.  Calling it the second time or more
         * is a no-op.
         * 可以被任意线程调用，不需要保持同步，第二次及之后的调用没有作用
         */
        void callEnded(io.grpc.Status status) {
            if (callEndedUpdater != null) {
                if (callEndedUpdater.getAndSet(this, 1) != 0) {
                    return;
                }
            } else {
                if (callEnded != 0) {
                    return;
                }
                callEnded = 1;
            }
            // 创建结束的 Span 并标记结束
            span.end(createEndSpanOptions(status, isSampledToLocalTracing));
        }
    }

    /**
     * ClientStreamTracer 的继承类，监听事件并上报
     */
    private static final class ClientTracer extends ClientStreamTracer {
        private final Span span;

        ClientTracer(Span span) {
            this.span = checkNotNull(span, "span");
        }

        /**
         * 发送出站消息
         */
        @Override
        public void outboundMessageSent(int seqNo,
                                        long optionalWireSize,
                                        long optionalUncompressedSize) {
            recordMessageEvent(span,
                    Type.SENT,
                    seqNo,
                    optionalWireSize,
                    optionalUncompressedSize);
        }

        /**
         * 读取入站消息
         */
        @Override
        public void inboundMessageRead(int seqNo,
                                       long optionalWireSize,
                                       long optionalUncompressedSize) {
            recordMessageEvent(span,
                    Type.RECEIVED,
                    seqNo,
                    optionalWireSize,
                    optionalUncompressedSize);
        }
    }


    /**
     * Server 端的 Tracer
     */
    private final class ServerTracer extends ServerStreamTracer {

        private final Span span;

        volatile boolean isSampledToLocalTracing;

        volatile int streamClosed;

        ServerTracer(String fullMethodName, @Nullable SpanContext remoteSpan) {
            checkNotNull(fullMethodName, "fullMethodName");
            // 创建 span
            this.span = censusTracer.spanBuilderWithRemoteParent(generateTraceSpanName(true, fullMethodName), remoteSpan)
                                    .setRecordEvents(true)
                                    .startSpan();
        }

        /**
         * 当 ServerCall 创建时调用
         */
        @Override
        public void serverCallStarted(ServerCallInfo<?, ?> callInfo) {
            isSampledToLocalTracing = callInfo.getMethodDescriptor().isSampledToLocalTracing();
        }

        /**
         * Record a finished stream and mark the current time as the end time.
         * 记录一个流完成并标记当前时间为结束时间
         *
         * <p>Can be called from any thread without synchronization.  Calling it the second time or more
         * is a no-op.
         * 可以被任意线程访问而无需同步，第二次及之后的调用没有作用
         */
        @Override
        public void streamClosed(io.grpc.Status status) {
            if (streamClosedUpdater != null) {
                if (streamClosedUpdater.getAndSet(this, 1) != 0) {
                    return;
                }
            } else {
                if (streamClosed != 0) {
                    return;
                }
                streamClosed = 1;
            }
            // 标记结束
            span.end(createEndSpanOptions(status, isSampledToLocalTracing));
        }

        /**
         * 在拦截器和调用处理器之前调用，如果需要可以修改 Context 的对象
         */
        @Override
        public Context filterContext(Context context) {
            // Access directly the unsafe trace API to create the new Context. This is a safe usage
            // because gRPC always creates a new Context for each of the server calls and does not
            // inherit from the parent Context.
            return ContextUtils.withValue(context, span);
        }

        /**
         * 发送出站消息
         */
        @Override
        public void outboundMessageSent(int seqNo,
                                        long optionalWireSize,
                                        long optionalUncompressedSize) {
            recordMessageEvent(span,
                    Type.SENT,
                    seqNo,
                    optionalWireSize,
                    optionalUncompressedSize);
        }

        /**
         * 读取入站消息
         **/
        @Override
        public void inboundMessageRead(int seqNo,
                                       long optionalWireSize,
                                       long optionalUncompressedSize) {
            recordMessageEvent(span,
                    Type.RECEIVED,
                    seqNo,
                    optionalWireSize,
                    optionalUncompressedSize);
        }
    }

    /**
     * Server 端 StreamTracer.Factory
     */
    @VisibleForTesting
    final class ServerTracerFactory extends ServerStreamTracer.Factory {

        /**
         * 创建新的 ServerStreamTracer
         */
        @SuppressWarnings("ReferenceEquality")
        @Override
        public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
            SpanContext remoteSpan = headers.get(tracingHeader);
            if (remoteSpan == SpanContext.INVALID) {
                remoteSpan = null;
            }
            return new ServerTracer(fullMethodName, remoteSpan);
        }
    }

    /**
     * Trace 客户端拦截器
     */
    @VisibleForTesting
    final class TracingClientInterceptor implements ClientInterceptor {

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                   CallOptions callOptions,
                                                                   Channel next) {
            // New RPCs on client-side inherit the tracing context from the current Context.
            // Safe usage of the unsafe trace API because CONTEXT_SPAN_KEY.get() returns the same value
            // as Tracer.getCurrentSpan() except when no value available when the return value is null
            // for the direct access and BlankSpan when Tracer API is used.
            // 可以安全使用不安全的 tracing API，客户端新的 RPC tracing 上下文继承当前的上下文，因为 CONTEXT_SPAN_KEY.get()
            // 和 Tracer.getCurrentSpan() 返回相同的值，除非当直接访问的返回值为 null 时没有可用值
            // 而使用 Tracer API 时返回 BlankSpan 则没有可用值
            final ClientCallTracer tracerFactory = newClientCallTracer(ContextUtils.getValue(Context.current()), method);

            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions.withStreamTracerFactory(tracerFactory));

            return new SimpleForwardingClientCall<ReqT, RespT>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    delegate().start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
                                         @Override
                                         public void onClose(io.grpc.Status status, Metadata trailers) {
                                             tracerFactory.callEnded(status);
                                             super.onClose(status, trailers);
                                         }
                                     },
                            headers);
                }
            };
        }
    }

    /**
     * Convert a full method name to a tracing span name.
     * 将方法全名转换为 span 的名称
     *
     * @param isServer       {@code false} if the span is on the client-side, {@code true} if on the
     *                       server-side
     *                       如果是客户端则是 false，如果是服务端则是 true
     * @param fullMethodName the method name as returned by {@link MethodDescriptor#getFullMethodName}.
     *                       MethodDescriptor#getFullMethodName 返回的方法全名
     */
    @VisibleForTesting
    static String generateTraceSpanName(boolean isServer, String fullMethodName) {
        String prefix = isServer ? "Recv" : "Sent";
        return prefix + "." + fullMethodName.replace('/', '.');
    }

}
