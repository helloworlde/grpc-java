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

package io.grpc.stub;

import io.grpc.ExperimentalApi;

/**
 * Specialization of {@link StreamObserver} implemented by clients in order to interact with the
 * advanced features of a call such as flow-control.
 * StreamObserver 的客户端的特殊实现，用于流控等高级特性
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4693")
public interface ClientResponseObserver<ReqT, RespT> extends StreamObserver<RespT> {
    /**
     * Called by the runtime priot to the start of a call to provide a reference to the
     * {@link ClientCallStreamObserver} for the outbound stream. This can be used to listen to
     * onReady events, disable auto inbound flow and perform other advanced functions.
     * 运行时在调用开始之前进行调用，以提供对出站流的引用，可以用于监听 onReady 事件，禁用自动用入站流并
     * 实现其他高级功能
     *
     * <p>Only the methods {@link ClientCallStreamObserver#setOnReadyHandler(Runnable)} and
     * {@link ClientCallStreamObserver#disableAutoRequestWithInitial(int)} may be called within
     * this callback
     * 这个回调内只能调用  ClientCallStreamObserver#disableAutoRequestWithInitial(int) 或
     * ClientCallStreamObserver#setOnReadyHandler(Runnable) 方法
     *
     * <pre>
     *   // Copy an iterator to the request stream under flow-control
     *   someStub.fullDuplexCall(new ClientResponseObserver&lt;ReqT, RespT&gt;() {
     *     public void beforeStart(final ClientCallStreamObserver&lt;Req&gt; requestStream) {
     *       StreamObservers.copyWithFlowControl(someIterator, requestStream);
     *   });
     * </pre>
     */
    void beforeStart(final ClientCallStreamObserver<ReqT> requestStream);
}
