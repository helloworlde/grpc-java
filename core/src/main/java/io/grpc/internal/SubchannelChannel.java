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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ClientCallImpl.ClientTransportProvider;
import io.grpc.internal.ClientStreamListener.RpcProgress;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * 将 Subchannel 转为 Channel
 */
final class SubchannelChannel extends Channel {
    @VisibleForTesting
    static final Status NOT_READY_ERROR = Status.UNAVAILABLE.withDescription("Subchannel is NOT READY");

    @VisibleForTesting
    static final Status WAIT_FOR_READY_ERROR = Status.UNAVAILABLE.withDescription("wait-for-ready RPC is not supported on Subchannel.asChannel()");

    private static final FailingClientTransport notReadyTransport = new FailingClientTransport(NOT_READY_ERROR, RpcProgress.REFUSED);

    private final InternalSubchannel subchannel;
    private final Executor executor;
    private final ScheduledExecutorService deadlineCancellationExecutor;
    private final CallTracer callsTracer;

    private final ClientTransportProvider transportProvider = new ClientTransportProvider() {
        @Override
        public ClientTransport get(PickSubchannelArgs args) {
            // 如果 Subchannel 没有 Transport，则使用 notReadyTransport，否则使用 Subchannel 的 Transport
            ClientTransport transport = subchannel.getTransport();
            if (transport == null) {
                return notReadyTransport;
            } else {
                return transport;
            }
        }

        /**
         * 创建新的流，这里不支持
         */
        @Override
        public <ReqT> ClientStream newRetriableStream(MethodDescriptor<ReqT, ?> method,
                                                      CallOptions callOptions, Metadata headers, Context context) {
            throw new UnsupportedOperationException("OobChannel should not create retriable streams");
        }
    };

    SubchannelChannel(InternalSubchannel subchannel,
                      Executor executor,
                      ScheduledExecutorService deadlineCancellationExecutor,
                      CallTracer callsTracer) {
        this.subchannel = checkNotNull(subchannel, "subchannel");
        this.executor = checkNotNull(executor, "executor");
        this.deadlineCancellationExecutor = checkNotNull(deadlineCancellationExecutor, "deadlineCancellationExecutor");
        this.callsTracer = checkNotNull(callsTracer, "callsTracer");
    }

    /**
     * 执行新的调用
     *
     * @param methodDescriptor describes the name and parameter types of the operation to call.
     *                         描述要调用的方法名称和参数
     * @param callOptions      runtime options to be applied to this call.
     *                         这次调用的参数
     */
    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(MethodDescriptor<RequestT, ResponseT> methodDescriptor,
                                                                         CallOptions callOptions) {
        final Executor effectiveExecutor = callOptions.getExecutor() == null ? executor : callOptions.getExecutor();
        // 如果是支持等待 READY，则返回一个空的调用
        if (callOptions.isWaitForReady()) {
            return new ClientCall<RequestT, ResponseT>() {
                @Override
                public void start(final ClientCall.Listener<ResponseT> listener, Metadata headers) {
                    effectiveExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            listener.onClose(WAIT_FOR_READY_ERROR, new Metadata());
                        }
                    });
                }

                @Override
                public void request(int numMessages) {
                }

                @Override
                public void cancel(String message, Throwable cause) {
                }

                @Override
                public void halfClose() {
                }

                @Override
                public void sendMessage(RequestT message) {
                }
            };
        }
        // 如果是不支持等待 READY，则真正执行调用
        return new ClientCallImpl<>(methodDescriptor,
                effectiveExecutor,
                callOptions.withOption(GrpcUtil.CALL_OPTIONS_RPC_OWNED_BY_BALANCER, Boolean.TRUE),
                transportProvider,
                deadlineCancellationExecutor,
                callsTracer,
                false /* retryEnabled */);
    }

    @Override
    public String authority() {
        return subchannel.getAuthority();
    }
}
