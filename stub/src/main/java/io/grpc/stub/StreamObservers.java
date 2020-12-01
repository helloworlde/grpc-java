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

package io.grpc.stub;

import com.google.common.base.Preconditions;
import io.grpc.ExperimentalApi;

import java.util.Iterator;

/**
 * Utility functions for working with {@link StreamObserver} and it's common subclasses like
 * {@link CallStreamObserver}.
 * 用于 StreamObserver 和其子类的工具类
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4694")
public final class StreamObservers {
    /**
     * Copy the values of an {@link Iterator} to the target {@link CallStreamObserver} while properly
     * accounting for outbound flow-control.  After calling this method, {@code target} should no
     * longer be used.
     * 将 Iterator 的值复制到目标 CallStreamObserver，同时适当地考虑出站流控制，调用这个方法之后，target 不应当
     * 再被使用
     *
     * <p>For clients this method is safe to call inside {@link ClientResponseObserver#beforeStart},
     * on servers it is safe to call inside the service method implementation.
     * </p>
     * 对于客户端，这个方法在 ClientResponseObserver#beforeStart 中调用是安全的；对于服务端，在服务方法实现中是安全的
     *
     * @param source of values expressed as an {@link Iterator}.
     *               迭代器的值
     * @param target {@link CallStreamObserver} which accepts values from the source.
     *               接收值的流观察器
     */
    public static <V> void copyWithFlowControl(final Iterator<V> source,
                                               final CallStreamObserver<V> target) {
        Preconditions.checkNotNull(source, "source");
        Preconditions.checkNotNull(target, "target");

        final class FlowControllingOnReadyHandler implements Runnable {
            private boolean completed;

            @Override
            public void run() {
                // 如果已经完成，则返回
                if (completed) {
                    return;
                }

                // 如果 ready，且有新的值，则遍历处理
                while (target.isReady() && source.hasNext()) {
                    target.onNext(source.next());
                }

                // 如果没有新的值，则完成
                if (!source.hasNext()) {
                    completed = true;
                    target.onCompleted();
                }
            }
        }

        // 设置 ready 执行回调
        target.setOnReadyHandler(new FlowControllingOnReadyHandler());
    }

    /**
     * Copy the values of an {@link Iterable} to the target {@link CallStreamObserver} while properly
     * accounting for outbound flow-control.  After calling this method, {@code target} should no
     * longer be used.
     *
     * <p>For clients this method is safe to call inside {@link ClientResponseObserver#beforeStart},
     * on servers it is safe to call inside the service method implementation.
     * </p>
     *
     * @param source of values expressed as an {@link Iterable}.
     * @param target {@link CallStreamObserver} which accepts values from the source.
     */
    public static <V> void copyWithFlowControl(final Iterable<V> source,
                                               CallStreamObserver<V> target) {
        Preconditions.checkNotNull(source, "source");
        copyWithFlowControl(source.iterator(), target);
    }
}
