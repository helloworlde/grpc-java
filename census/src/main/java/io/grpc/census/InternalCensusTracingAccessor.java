/*
 * Copyright 2019 The gRPC Authors
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

import io.grpc.ClientInterceptor;
import io.grpc.Internal;
import io.grpc.ServerStreamTracer;
import io.opencensus.trace.Tracing;

/**
 * Accessor for getting {@link ClientInterceptor} or {@link ServerStreamTracer.Factory} with
 * default Census tracing implementation.
 * <p>
 * 用于访问 Census tracing 实现的 ClientInterceptor 和 ServerStreamTracer.Factory
 */
@Internal
public final class InternalCensusTracingAccessor {

    // Prevent instantiation.
    private InternalCensusTracingAccessor() {
    }

    /**
     * Returns a {@link ClientInterceptor} with default tracing implementation.
     * 返回包含 tracing 默认实现的 ClientInterceptor
     */
    public static ClientInterceptor getClientInterceptor() {
        CensusTracingModule censusTracing = new CensusTracingModule(Tracing.getTracer(), Tracing.getPropagationComponent().getBinaryFormat());
        return censusTracing.getClientInterceptor();
    }

    /**
     * Returns a {@link ServerStreamTracer.Factory} with default stats implementation.
     * 返回包含 tracing 默认实现的  ServerStreamTracer.Factory
     */
    public static ServerStreamTracer.Factory getServerStreamTracerFactory() {
        CensusTracingModule censusTracing = new CensusTracingModule(Tracing.getTracer(), Tracing.getPropagationComponent().getBinaryFormat());
        return censusTracing.getServerTracerFactory();
    }
}
