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

package io.grpc;

/**
 * The connectivity states.
 * 连接状态
 *
 * @see <a href="https://github.com/grpc/grpc/blob/master/doc/connectivity-semantics-and-api.md">
 * more information</a>
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4359")
public enum ConnectivityState {
    /**
     * The channel is trying to establish a connection and is waiting to make progress on one of the
     * steps involved in name resolution, TCP connection establishment or TLS handshake. This may be
     * used as the initial state for channels upon creation.
     * Channel 尝试建立连接，等待涉及名称解析、TPC 建立连接或者 TLS 握手的步骤之一，可能用于 Channel 创建的初始
     * 状态
     */
    CONNECTING,

    /**
     * The channel has successfully established a connection all the way through TLS handshake (or
     * equivalent) and all subsequent attempt to communicate have succeeded (or are pending without
     * any known failure ).
     * Channel 已通过 TLS 握手(或等效操作)成功建立连接，所有的后续尝试交流都成功(或者等待但是没有出现失败)
     */
    READY,

    /**
     * There has been some transient failure (such as a TCP 3-way handshake timing out or a socket
     * error). Channels in this state will eventually switch to the CONNECTING state and try to
     * establish a connection again. Since retries are done with exponential backoff, channels that
     * fail to connect will start out spending very little time in this state but as the attempts
     * fail repeatedly, the channel will spend increasingly large amounts of time in this state. For
     * many non-fatal failures (e.g., TCP connection attempts timing out because the server is not
     * yet available), the channel may spend increasingly large amounts of time in this state.
     * <p>
     * 已经有一些短暂的失败，如 TCP 三次握手超时或者 Socket 错误，这个状态 Channel 最终会切换至 CONNECTING 状态，
     * 尝试再次建立连接，由于重试是通过指数补偿的，连接失败的 Channel 会在这个状态停留很短的时间，但是由于反复尝试
     * 失败，停留在这个状态的时间会大量增长，对于很多失败的场景（如 TCP 连接失败，因为 server 还未就绪）
     */
    TRANSIENT_FAILURE,

    /**
     * This is the state where the channel is not even trying to create a connection because of a
     * lack of new or pending RPCs. New RPCs MAY be created in this state. Any attempt to start an
     * RPC on the channel will push the channel out of this state to connecting. When there has been
     * no RPC activity on a channel for a configurable IDLE_TIMEOUT, i.e., no new or pending (active)
     * RPCs for this period, channels that are READY or CONNECTING switch to IDLE. Additionaly,
     * channels that receive a GOAWAY when there are no active or pending RPCs should also switch to
     * IDLE to avoid connection overload at servers that are attempting to shed connections.
     * <p>
     * 因为没有请求或者等待请求，Channel 没有尝试建立连接的状态，新的请求可能会创建这个状态，这个 Channel 上任何
     * 尝试的请求会将 Channel 从这个状态推出至连接，当没有针对可配置 IDLE_TIMEOUT 的 RPC 活动，如这个时间内没有
     * 新的或者等待的请求， READY 和 CONNECTING 状态会切换到 IDLE；另外，接收 GOAWAY 的 Channel 当没有活跃
     * 或者等待的请求时应当也切换至 IDLE 状态，避免试图断开连接的服务器上的连接过载
     */
    IDLE,

    /**
     * This channel has started shutting down. Any new RPCs should fail immediately. Pending RPCs
     * may continue running till the application cancels them. Channels may enter this state either
     * because the application explicitly requested a shutdown or if a non-recoverable error has
     * happened during attempts to connect communicate . (As of 6/12/2015, there are no known errors
     * (while connecting or communicating) that are classified as non-recoverable) Channels that
     * enter this state never leave this state.
     * <p>
     * 这个 Channel 开始关闭，新的请求会立即失败，已经等待的请求会继续执行至应用取消，Channel 进入这个状态是因为
     * 应用明确要求关闭，或者在尝试连接期间出现不可恢复的错误，进入这个状态的 Channel 会一直停留在这个状态
     */
    SHUTDOWN
}
