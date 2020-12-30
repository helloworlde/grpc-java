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

package io.grpc;

import com.google.common.base.Preconditions;

import java.util.concurrent.TimeoutException;

/**
 * Utility methods for working with {@link Context}s in GRPC.
 * 用于 Context 的工具类
 */
public final class Contexts {

    private Contexts() {
    }

    /**
     * Make the provided {@link Context} {@link Context#current()} for the creation of a listener
     * to a received call and for all events received by that listener.
     * 为当前的 Context 创建一个监听器，用于创建一个监听器，接收调用和所有的事件
     *
     * <p>This utility is expected to be used by {@link ServerInterceptor} implementations that need
     * to augment the {@link Context} in which the application does work when receiving events from
     * the client.
     * 这个工具期望用于需要增加 Context 的 ServerInterceptor 的实现，会在接收到客户端发送的事件时工作
     *
     * @param context to make {@link Context#current()}.
     *                当前上下文
     * @param call    used to send responses to client.
     *                将响应发送给客户端
     * @param headers received from client.
     *                客户端接收的 header
     * @param next    handler used to create the listener to be wrapped.
     *                用于创建要包装的监听器的处理器
     * @return listener that will receive events in the scope of the provided context.
     * 可以在提供的 Context 范围内监听事件的监听器
     */
    public static <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(Context context,
                                                                        ServerCall<ReqT, RespT> call,
                                                                        Metadata headers,
                                                                        ServerCallHandler<ReqT, RespT> next) {
        Context previous = context.attach();
        try {
            return new ContextualizedServerCallListener<>(next.startCall(call, headers), context);
        } finally {
            context.detach(previous);
        }
    }

    /**
     * Implementation of {@link io.grpc.ForwardingServerCallListener} that attaches a context before
     * dispatching calls to the delegate and detaches them after the call completes.
     * <p>
     * ForwardingServerCallListener 的实现，在将请求转发给代理，在调用完成后分离
     */
    private static class ContextualizedServerCallListener<ReqT> extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

        private final Context context;

        public ContextualizedServerCallListener(ServerCall.Listener<ReqT> delegate, Context context) {
            super(delegate);
            this.context = context;
        }

        @Override
        public void onMessage(ReqT message) {
            Context previous = context.attach();
            try {
                super.onMessage(message);
            } finally {
                context.detach(previous);
            }
        }

        @Override
        public void onHalfClose() {
            Context previous = context.attach();
            try {
                super.onHalfClose();
            } finally {
                context.detach(previous);
            }
        }

        @Override
        public void onCancel() {
            Context previous = context.attach();
            try {
                super.onCancel();
            } finally {
                context.detach(previous);
            }
        }

        @Override
        public void onComplete() {
            Context previous = context.attach();
            try {
                super.onComplete();
            } finally {
                context.detach(previous);
            }
        }

        @Override
        public void onReady() {
            Context previous = context.attach();
            try {
                super.onReady();
            } finally {
                context.detach(previous);
            }
        }
    }

    /**
     * Returns the {@link Status} of a cancelled context or {@code null} if the context
     * is not cancelled.
     * 根据已经取消的上下文返回状态，如果没有取消则返回 null
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1975")
    public static Status statusFromCancelled(Context context) {
        Preconditions.checkNotNull(context, "context must not be null");
        // 如果没有取消则返回 null
        if (!context.isCancelled()) {
            return null;
        }

        // 如果没有异常，则返回取消
        Throwable cancellationCause = context.cancellationCause();
        if (cancellationCause == null) {
            return Status.CANCELLED.withDescription("io.grpc.Context was cancelled without error");
        }
        // 如果是超时，则返回超时
        if (cancellationCause instanceof TimeoutException) {
            return Status.DEADLINE_EXCEEDED
                    .withDescription(cancellationCause.getMessage())
                    .withCause(cancellationCause);
        }

        // 如果有异常且状态是未知，返回取消
        Status status = Status.fromThrowable(cancellationCause);
        if (Status.Code.UNKNOWN.equals(status.getCode())
                && status.getCause() == cancellationCause) {
            // If fromThrowable could not determine a status, then
            // just return CANCELLED.
            return Status.CANCELLED.withDescription("Context cancelled").withCause(cancellationCause);
        }
        // 返回状态
        return status.withCause(cancellationCause);
    }
}
