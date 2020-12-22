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
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
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
import io.grpc.Status;
import io.grpc.StreamTracer;
import io.grpc.census.internal.DeprecatedCensusConstants;
import io.opencensus.contrib.grpc.metrics.RpcMeasureConstants;
import io.opencensus.stats.Measure.MeasureDouble;
import io.opencensus.stats.Measure.MeasureLong;
import io.opencensus.stats.MeasureMap;
import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagValue;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;
import io.opencensus.tags.propagation.TagContextBinarySerializer;
import io.opencensus.tags.propagation.TagContextSerializationException;
import io.opencensus.tags.unsafe.ContextUtils;

import javax.annotation.Nullable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Provides factories for {@link StreamTracer} that records stats to Census.
 * 提供用于 Census 统计的 StreamTracer 工厂
 *
 * <p>On the client-side, a factory is created for each call, because ClientCall starts earlier than
 * the ClientStream, and in some cases may even not create a ClientStream at all.  Therefore, it's
 * the factory that reports the summary to Census.
 * 在客户端，工厂在每个调用都会创建，因为 ClientCall 早于 ClientStream 启动，可能因为一些问题不会创建 ClientStream，
 * 因此，这是一个上报 Census 汇总统计的工厂
 *
 * <p>On the server-side, there is only one ServerStream per each ServerCall, and ServerStream
 * starts earlier than the ServerCall.  Therefore, only one tracer is created per stream/call and
 * it's the tracer that reports the summary to Census.
 * 在 Server 端，每个 ServerCall 只有一个 ServerStream，并且 ServerStream 早于 ServerCall 创建，因此，
 * 每个流只有一个 tracer，会上报汇总统计的工厂
 */
final class CensusStatsModule {
    private static final Logger logger = Logger.getLogger(CensusStatsModule.class.getName());
    private static final double NANOS_PER_MILLI = TimeUnit.MILLISECONDS.toNanos(1);

    private final Tagger tagger;
    private final StatsRecorder statsRecorder;
    private final Supplier<Stopwatch> stopwatchSupplier;

    @VisibleForTesting
    final Metadata.Key<TagContext> statsHeader;

    private final boolean propagateTags;
    private final boolean recordStartedRpcs;
    private final boolean recordFinishedRpcs;
    private final boolean recordRealTimeMetrics;

    /**
     * Creates a {@link CensusStatsModule} with the default OpenCensus implementation.
     * 创建包含 OpenCensus 实现的 CensusStatsModule
     */
    CensusStatsModule(Supplier<Stopwatch> stopwatchSupplier,
                      boolean propagateTags,
                      boolean recordStartedRpcs,
                      boolean recordFinishedRpcs,
                      boolean recordRealTimeMetrics) {
        this(Tags.getTagger(),
                Tags.getTagPropagationComponent().getBinarySerializer(),
                Stats.getStatsRecorder(),
                stopwatchSupplier,
                propagateTags,
                recordStartedRpcs,
                recordFinishedRpcs,
                recordRealTimeMetrics);
    }

    /**
     * Creates a {@link CensusStatsModule} with the given OpenCensus implementation.
     * 创建包含 OpenCensus 实现的 CensusStatsModule
     */
    CensusStatsModule(final Tagger tagger,
                      final TagContextBinarySerializer tagCtxSerializer,
                      StatsRecorder statsRecorder,
                      Supplier<Stopwatch> stopwatchSupplier,
                      boolean propagateTags,
                      boolean recordStartedRpcs,
                      boolean recordFinishedRpcs,
                      boolean recordRealTimeMetrics) {

        this.tagger = checkNotNull(tagger, "tagger");
        this.statsRecorder = checkNotNull(statsRecorder, "statsRecorder");
        checkNotNull(tagCtxSerializer, "tagCtxSerializer");
        this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
        this.propagateTags = propagateTags;
        this.recordStartedRpcs = recordStartedRpcs;
        this.recordFinishedRpcs = recordFinishedRpcs;
        this.recordRealTimeMetrics = recordRealTimeMetrics;

        this.statsHeader = Metadata.Key.of("grpc-tags-bin", new Metadata.BinaryMarshaller<TagContext>() {
            @Override
            public byte[] toBytes(TagContext context) {
                // TODO(carl-mastrangelo): currently we only make sure the correctness. We may need to
                // optimize out the allocation and copy in the future.
                try {
                    return tagCtxSerializer.toByteArray(context);
                } catch (TagContextSerializationException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public TagContext parseBytes(byte[] serialized) {
                try {
                    return tagCtxSerializer.fromByteArray(serialized);
                } catch (Exception e) {
                    logger.log(Level.FINE, "Failed to parse stats header", e);
                    return tagger.empty();
                }
            }
        });
    }

    /**
     * Creates a {@link ClientCallTracer} for a new call.
     * 为新的调用创建 ClientCallTracer
     */
    @VisibleForTesting
    ClientCallTracer newClientCallTracer(TagContext parentCtx,
                                         String fullMethodName) {
        return new ClientCallTracer(this, parentCtx, fullMethodName);
    }

    /**
     * Returns the server tracer factory.
     * 返回 Server 端 StreamTracer.Factory1
     */
    ServerStreamTracer.Factory getServerTracerFactory() {
        return new ServerTracerFactory();
    }

    /**
     * Returns the client interceptor that facilitates Census-based stats reporting.
     * 返回用于 Census 统计的客户端拦截器
     */
    ClientInterceptor getClientInterceptor() {
        return new StatsClientInterceptor();
    }

    /**
     * 记录时间数据
     */
    private void recordRealTimeMetric(TagContext ctx, MeasureDouble measure, double value) {
        if (recordRealTimeMetrics) {
            MeasureMap measureMap = statsRecorder.newMeasureMap().put(measure, value);
            measureMap.record(ctx);
        }
    }

    /**
     * 记录时间数据
     */
    private void recordRealTimeMetric(TagContext ctx, MeasureLong measure, long value) {
        if (recordRealTimeMetrics) {
            MeasureMap measureMap = statsRecorder.newMeasureMap().put(measure, value);
            measureMap.record(ctx);
        }
    }

    /**
     * 客户端 Tracer
     */
    private static final class ClientTracer extends ClientStreamTracer {

        @Nullable
        private static final AtomicLongFieldUpdater<ClientTracer> outboundMessageCountUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ClientTracer> inboundMessageCountUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ClientTracer> outboundWireSizeUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ClientTracer> inboundWireSizeUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ClientTracer> outboundUncompressedSizeUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ClientTracer> inboundUncompressedSizeUpdater;

        /**
         * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their
         * JDK reflection API that triggers a NoSuchFieldException. When this occurs, we fallback to
         * (potentially racy) direct updates of the volatile variables.
         */
        static {
            AtomicLongFieldUpdater<ClientTracer> tmpOutboundMessageCountUpdater;
            AtomicLongFieldUpdater<ClientTracer> tmpInboundMessageCountUpdater;
            AtomicLongFieldUpdater<ClientTracer> tmpOutboundWireSizeUpdater;
            AtomicLongFieldUpdater<ClientTracer> tmpInboundWireSizeUpdater;
            AtomicLongFieldUpdater<ClientTracer> tmpOutboundUncompressedSizeUpdater;
            AtomicLongFieldUpdater<ClientTracer> tmpInboundUncompressedSizeUpdater;
            try {
                tmpOutboundMessageCountUpdater = AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundMessageCount");
                tmpInboundMessageCountUpdater = AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundMessageCount");
                tmpOutboundWireSizeUpdater = AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundWireSize");
                tmpInboundWireSizeUpdater = AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundWireSize");
                tmpOutboundUncompressedSizeUpdater = AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundUncompressedSize");
                tmpInboundUncompressedSizeUpdater = AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundUncompressedSize");
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
                tmpOutboundMessageCountUpdater = null;
                tmpInboundMessageCountUpdater = null;
                tmpOutboundWireSizeUpdater = null;
                tmpInboundWireSizeUpdater = null;
                tmpOutboundUncompressedSizeUpdater = null;
                tmpInboundUncompressedSizeUpdater = null;
            }
            outboundMessageCountUpdater = tmpOutboundMessageCountUpdater;
            inboundMessageCountUpdater = tmpInboundMessageCountUpdater;
            outboundWireSizeUpdater = tmpOutboundWireSizeUpdater;
            inboundWireSizeUpdater = tmpInboundWireSizeUpdater;
            outboundUncompressedSizeUpdater = tmpOutboundUncompressedSizeUpdater;
            inboundUncompressedSizeUpdater = tmpInboundUncompressedSizeUpdater;
        }

        private final CensusStatsModule module;
        private final TagContext startCtx;

        volatile long outboundMessageCount;
        volatile long inboundMessageCount;
        volatile long outboundWireSize;
        volatile long inboundWireSize;
        volatile long outboundUncompressedSize;
        volatile long inboundUncompressedSize;

        ClientTracer(CensusStatsModule module, TagContext startCtx) {
            this.module = checkNotNull(module, "module");
            this.startCtx = checkNotNull(startCtx, "startCtx");
        }

        /**
         * 统计出站线径大小
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void outboundWireSize(long bytes) {
            if (outboundWireSizeUpdater != null) {
                outboundWireSizeUpdater.getAndAdd(this, bytes);
            } else {
                outboundWireSize += bytes;
            }
            module.recordRealTimeMetric(startCtx, RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_METHOD, bytes);
        }

        /**
         * 统计入站线径大小
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void inboundWireSize(long bytes) {
            if (inboundWireSizeUpdater != null) {
                inboundWireSizeUpdater.getAndAdd(this, bytes);
            } else {
                inboundWireSize += bytes;
            }
            module.recordRealTimeMetric(startCtx, RpcMeasureConstants.GRPC_CLIENT_RECEIVED_BYTES_PER_METHOD, bytes);
        }

        /**
         * 统计未压缩的出站大小
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void outboundUncompressedSize(long bytes) {
            if (outboundUncompressedSizeUpdater != null) {
                outboundUncompressedSizeUpdater.getAndAdd(this, bytes);
            } else {
                outboundUncompressedSize += bytes;
            }
        }

        /**
         * 统计未压缩的入站大小
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void inboundUncompressedSize(long bytes) {
            if (inboundUncompressedSizeUpdater != null) {
                inboundUncompressedSizeUpdater.getAndAdd(this, bytes);
            } else {
                inboundUncompressedSize += bytes;
            }
        }

        /**
         * 消息入站
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void inboundMessage(int seqNo) {
            if (inboundMessageCountUpdater != null) {
                inboundMessageCountUpdater.getAndIncrement(this);
            } else {
                inboundMessageCount++;
            }
            module.recordRealTimeMetric(startCtx, RpcMeasureConstants.GRPC_CLIENT_RECEIVED_MESSAGES_PER_METHOD, 1);
        }

        /**
         * 消息出站
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void outboundMessage(int seqNo) {
            if (outboundMessageCountUpdater != null) {
                outboundMessageCountUpdater.getAndIncrement(this);
            } else {
                outboundMessageCount++;
            }
            module.recordRealTimeMetric(startCtx, RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD, 1);
        }
    }

    @VisibleForTesting
    static final class ClientCallTracer extends ClientStreamTracer.Factory {

        @Nullable
        private static final AtomicReferenceFieldUpdater<ClientCallTracer, ClientTracer> streamTracerUpdater;

        @Nullable
        private static final AtomicIntegerFieldUpdater<ClientCallTracer> callEndedUpdater;

        /**
         * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their
         * JDK reflection API that triggers a NoSuchFieldException. When this occurs, we fallback to
         * (potentially racy) direct updates of the volatile variables.
         */
        static {
            AtomicReferenceFieldUpdater<ClientCallTracer, ClientTracer> tmpStreamTracerUpdater;
            AtomicIntegerFieldUpdater<ClientCallTracer> tmpCallEndedUpdater;
            try {
                tmpStreamTracerUpdater = AtomicReferenceFieldUpdater.newUpdater(ClientCallTracer.class, ClientTracer.class, "streamTracer");
                tmpCallEndedUpdater = AtomicIntegerFieldUpdater.newUpdater(ClientCallTracer.class, "callEnded");
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
                tmpStreamTracerUpdater = null;
                tmpCallEndedUpdater = null;
            }
            streamTracerUpdater = tmpStreamTracerUpdater;
            callEndedUpdater = tmpCallEndedUpdater;
        }

        private final CensusStatsModule module;
        private final Stopwatch stopwatch;
        private volatile ClientTracer streamTracer;
        private volatile int callEnded;
        private final TagContext parentCtx;
        private final TagContext startCtx;

        /**
         * 创建新的 ClientCallTracer
         */
        ClientCallTracer(CensusStatsModule module, TagContext parentCtx, String fullMethodName) {
            this.module = checkNotNull(module);
            this.parentCtx = checkNotNull(parentCtx);
            // 方法 tag
            TagValue methodTag = TagValue.create(fullMethodName);

            this.startCtx = module.tagger.toBuilder(parentCtx)
                                         .putLocal(RpcMeasureConstants.GRPC_CLIENT_METHOD, methodTag)
                                         .build();
            // 启动 StopWatch
            this.stopwatch = module.stopwatchSupplier.get().start();

            // 开始记录
            if (module.recordStartedRpcs) {
                module.statsRecorder.newMeasureMap()
                                    .put(DeprecatedCensusConstants.RPC_CLIENT_STARTED_COUNT, 1)
                                    .record(startCtx);
            }
        }

        /**
         * 创建 ClientStreamTracer
         */
        @Override
        public ClientStreamTracer newClientStreamTracer(ClientStreamTracer.StreamInfo info,
                                                        Metadata headers) {

            ClientTracer tracer = new ClientTracer(module, startCtx);

            // TODO(zhangkun83): Once retry or hedging is implemented, a ClientCall may start more than
            // one streams.  We will need to update this file to support them.
            if (streamTracerUpdater != null) {
                checkState(streamTracerUpdater.compareAndSet(this, null, tracer),
                        "Are you creating multiple streams per call? This class doesn't yet support this case");
            } else {
                checkState(streamTracer == null,
                        "Are you creating multiple streams per call? This class doesn't yet support this case");
                streamTracer = tracer;
            }
            if (module.propagateTags) {
                headers.discardAll(module.statsHeader);
                if (!module.tagger.empty().equals(parentCtx)) {
                    headers.put(module.statsHeader, parentCtx);
                }
            }
            return tracer;
        }

        /**
         * Record a finished call and mark the current time as the end time.
         * 记录调用完成，将当前时间标记为结束时间
         *
         * <p>Can be called from any thread without synchronization.  Calling it the second time or more
         * is a no-op.
         */
        void callEnded(Status status) {
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

            if (!module.recordFinishedRpcs) {
                return;
            }
            stopwatch.stop();
            long roundtripNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
            ClientTracer tracer = streamTracer;
            // 如果没有则创建一个
            if (tracer == null) {
                tracer = new ClientTracer(module, startCtx);
            }

            // 记录统计数据
            MeasureMap measureMap = module.statsRecorder.newMeasureMap()
                                                        // TODO(songya): remove the deprecated measure constants once they are completed removed.
                                                        .put(DeprecatedCensusConstants.RPC_CLIENT_FINISHED_COUNT, 1)
                                                        // The latency is double value
                                                        .put(DeprecatedCensusConstants.RPC_CLIENT_ROUNDTRIP_LATENCY, roundtripNanos / NANOS_PER_MILLI)
                                                        .put(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_COUNT, tracer.outboundMessageCount)
                                                        .put(DeprecatedCensusConstants.RPC_CLIENT_RESPONSE_COUNT, tracer.inboundMessageCount)
                                                        .put(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_BYTES, tracer.outboundWireSize)
                                                        .put(DeprecatedCensusConstants.RPC_CLIENT_RESPONSE_BYTES, tracer.inboundWireSize)
                                                        .put(DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES, tracer.outboundUncompressedSize)
                                                        .put(DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES, tracer.inboundUncompressedSize);

            if (!status.isOk()) {
                measureMap.put(DeprecatedCensusConstants.RPC_CLIENT_ERROR_COUNT, 1);
            }

            TagValue statusTag = TagValue.create(status.getCode().toString());
            measureMap.record(module.tagger
                    .toBuilder(startCtx)
                    .putLocal(RpcMeasureConstants.GRPC_CLIENT_STATUS, statusTag)
                    .build());
        }
    }

    /**
     * Server 端统计
     */
    private static final class ServerTracer extends ServerStreamTracer {

        @Nullable
        private static final AtomicIntegerFieldUpdater<ServerTracer> streamClosedUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ServerTracer> outboundMessageCountUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ServerTracer> inboundMessageCountUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ServerTracer> outboundWireSizeUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ServerTracer> inboundWireSizeUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ServerTracer> outboundUncompressedSizeUpdater;

        @Nullable
        private static final AtomicLongFieldUpdater<ServerTracer> inboundUncompressedSizeUpdater;

        /**
         * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their
         * JDK reflection API that triggers a NoSuchFieldException. When this occurs, we fallback to
         * (potentially racy) direct updates of the volatile variables.
         */
        static {
            AtomicIntegerFieldUpdater<ServerTracer> tmpStreamClosedUpdater;
            AtomicLongFieldUpdater<ServerTracer> tmpOutboundMessageCountUpdater;
            AtomicLongFieldUpdater<ServerTracer> tmpInboundMessageCountUpdater;
            AtomicLongFieldUpdater<ServerTracer> tmpOutboundWireSizeUpdater;
            AtomicLongFieldUpdater<ServerTracer> tmpInboundWireSizeUpdater;
            AtomicLongFieldUpdater<ServerTracer> tmpOutboundUncompressedSizeUpdater;
            AtomicLongFieldUpdater<ServerTracer> tmpInboundUncompressedSizeUpdater;
            try {
                tmpStreamClosedUpdater = AtomicIntegerFieldUpdater.newUpdater(ServerTracer.class, "streamClosed");
                tmpOutboundMessageCountUpdater = AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundMessageCount");
                tmpInboundMessageCountUpdater = AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundMessageCount");
                tmpOutboundWireSizeUpdater = AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundWireSize");
                tmpInboundWireSizeUpdater = AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundWireSize");
                tmpOutboundUncompressedSizeUpdater = AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundUncompressedSize");
                tmpInboundUncompressedSizeUpdater = AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundUncompressedSize");
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
                tmpStreamClosedUpdater = null;
                tmpOutboundMessageCountUpdater = null;
                tmpInboundMessageCountUpdater = null;
                tmpOutboundWireSizeUpdater = null;
                tmpInboundWireSizeUpdater = null;
                tmpOutboundUncompressedSizeUpdater = null;
                tmpInboundUncompressedSizeUpdater = null;
            }
            streamClosedUpdater = tmpStreamClosedUpdater;
            outboundMessageCountUpdater = tmpOutboundMessageCountUpdater;
            inboundMessageCountUpdater = tmpInboundMessageCountUpdater;
            outboundWireSizeUpdater = tmpOutboundWireSizeUpdater;
            inboundWireSizeUpdater = tmpInboundWireSizeUpdater;
            outboundUncompressedSizeUpdater = tmpOutboundUncompressedSizeUpdater;
            inboundUncompressedSizeUpdater = tmpInboundUncompressedSizeUpdater;
        }

        private final CensusStatsModule module;
        private final TagContext parentCtx;
        private volatile int streamClosed;
        private final Stopwatch stopwatch;
        private volatile long outboundMessageCount;
        private volatile long inboundMessageCount;
        private volatile long outboundWireSize;
        private volatile long inboundWireSize;
        private volatile long outboundUncompressedSize;
        private volatile long inboundUncompressedSize;

        /**
         * 创建 ServerTracer
         */
        ServerTracer(CensusStatsModule module,
                     TagContext parentCtx) {
            this.module = checkNotNull(module, "module");
            this.parentCtx = checkNotNull(parentCtx, "parentCtx");
            this.stopwatch = module.stopwatchSupplier.get().start();
            if (module.recordStartedRpcs) {
                module.statsRecorder.newMeasureMap()
                                    .put(DeprecatedCensusConstants.RPC_SERVER_STARTED_COUNT, 1)
                                    .record(parentCtx);
            }
        }

        /**
         * 统计出站大小
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void outboundWireSize(long bytes) {
            if (outboundWireSizeUpdater != null) {
                outboundWireSizeUpdater.getAndAdd(this, bytes);
            } else {
                outboundWireSize += bytes;
            }
            module.recordRealTimeMetric(parentCtx, RpcMeasureConstants.GRPC_SERVER_SENT_BYTES_PER_METHOD, bytes);
        }

        /**
         * 统计入站大小
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void inboundWireSize(long bytes) {
            if (inboundWireSizeUpdater != null) {
                inboundWireSizeUpdater.getAndAdd(this, bytes);
            } else {
                inboundWireSize += bytes;
            }
            module.recordRealTimeMetric(parentCtx, RpcMeasureConstants.GRPC_SERVER_RECEIVED_BYTES_PER_METHOD, bytes);
        }

        /**
         * 统计未压缩出站大小
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void outboundUncompressedSize(long bytes) {
            if (outboundUncompressedSizeUpdater != null) {
                outboundUncompressedSizeUpdater.getAndAdd(this, bytes);
            } else {
                outboundUncompressedSize += bytes;
            }
        }

        /**
         * 统计未压缩入站消息
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void inboundUncompressedSize(long bytes) {
            if (inboundUncompressedSizeUpdater != null) {
                inboundUncompressedSizeUpdater.getAndAdd(this, bytes);
            } else {
                inboundUncompressedSize += bytes;
            }
        }

        /**
         * 入站消息
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void inboundMessage(int seqNo) {
            if (inboundMessageCountUpdater != null) {
                inboundMessageCountUpdater.getAndIncrement(this);
            } else {
                inboundMessageCount++;
            }
            module.recordRealTimeMetric(parentCtx, RpcMeasureConstants.GRPC_SERVER_RECEIVED_MESSAGES_PER_METHOD, 1);
        }

        /**
         * 统计出站消息
         */
        @Override
        @SuppressWarnings("NonAtomicVolatileUpdate")
        public void outboundMessage(int seqNo) {
            if (outboundMessageCountUpdater != null) {
                outboundMessageCountUpdater.getAndIncrement(this);
            } else {
                outboundMessageCount++;
            }
            module.recordRealTimeMetric(parentCtx, RpcMeasureConstants.GRPC_SERVER_SENT_MESSAGES_PER_METHOD, 1);
        }

        /**
         * Record a finished stream and mark the current time as the end time.
         * 记录流完成，标记当前时间为结束时间
         *
         * <p>Can be called from any thread without synchronization.  Calling it the second time or more
         * is a no-op.
         * 可以被任意线程调用，不需要保证同步，第二次或之后的调用不会有效果
         */
        @Override
        public void streamClosed(Status status) {
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
            if (!module.recordFinishedRpcs) {
                return;
            }
            stopwatch.stop();
            long elapsedTimeNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
            MeasureMap measureMap = module.statsRecorder.newMeasureMap()
                                                        // TODO(songya): remove the deprecated measure constants once they are completed removed.
                                                        .put(DeprecatedCensusConstants.RPC_SERVER_FINISHED_COUNT, 1)
                                                        // The latency is double value
                                                        .put(DeprecatedCensusConstants.RPC_SERVER_SERVER_LATENCY, elapsedTimeNanos / NANOS_PER_MILLI)
                                                        .put(DeprecatedCensusConstants.RPC_SERVER_RESPONSE_COUNT, outboundMessageCount)
                                                        .put(DeprecatedCensusConstants.RPC_SERVER_REQUEST_COUNT, inboundMessageCount)
                                                        .put(DeprecatedCensusConstants.RPC_SERVER_RESPONSE_BYTES, outboundWireSize)
                                                        .put(DeprecatedCensusConstants.RPC_SERVER_REQUEST_BYTES, inboundWireSize)
                                                        .put(DeprecatedCensusConstants.RPC_SERVER_UNCOMPRESSED_RESPONSE_BYTES, outboundUncompressedSize)
                                                        .put(DeprecatedCensusConstants.RPC_SERVER_UNCOMPRESSED_REQUEST_BYTES, inboundUncompressedSize);
            if (!status.isOk()) {
                measureMap.put(DeprecatedCensusConstants.RPC_SERVER_ERROR_COUNT, 1);
            }

            TagValue statusTag = TagValue.create(status.getCode().toString());
            measureMap.record(module.tagger
                    .toBuilder(parentCtx)
                    .putLocal(RpcMeasureConstants.GRPC_SERVER_STATUS, statusTag)
                    .build());
        }

        /**
         * 向 Context 添加值
         */
        @Override
        public Context filterContext(Context context) {
            if (!module.tagger.empty().equals(parentCtx)) {
                return ContextUtils.withValue(context, parentCtx);
            }
            return context;
        }
    }

    /**
     * 创建 ServerStreamTracer
     */
    @VisibleForTesting
    final class ServerTracerFactory extends ServerStreamTracer.Factory {
        @Override
        public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
            TagContext parentCtx = headers.get(statsHeader);
            if (parentCtx == null) {
                parentCtx = tagger.empty();
            }

            TagValue methodTag = TagValue.create(fullMethodName);
            parentCtx = tagger.toBuilder(parentCtx)
                              .putLocal(RpcMeasureConstants.GRPC_SERVER_METHOD, methodTag)
                              .build();
            return new ServerTracer(CensusStatsModule.this, parentCtx);
        }
    }

    /**
     * 用于 Census 统计的客户端拦截器
     */
    @VisibleForTesting
    final class StatsClientInterceptor implements ClientInterceptor {

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                   CallOptions callOptions,
                                                                   Channel next) {

            // New RPCs on client-side inherit the tag context from the current Context.
            // 客户端的新的 RPC 继承当前上下文中的 tag
            TagContext parentCtx = tagger.getCurrentTagContext();
            // 创建调用的 Tracer
            final ClientCallTracer tracerFactory = newClientCallTracer(parentCtx, method.getFullMethodName());

            // 调用
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions.withStreamTracerFactory(tracerFactory));

            return new SimpleForwardingClientCall<ReqT, RespT>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    delegate().start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
                                         @Override
                                         public void onClose(Status status, Metadata trailers) {
                                             // 调用关闭时记录结束
                                             tracerFactory.callEnded(status);
                                             super.onClose(status, trailers);
                                         }
                                     },
                            headers);
                }
            };
        }
    }
}
