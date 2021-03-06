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

import com.google.common.base.Preconditions;

import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

/**
 * A tuple of a {@link ConnectivityState} and its associated {@link Status}.
 * 关联了状态的 ConnectivityState 元组
 *
 * <p>If the state is {@code TRANSIENT_FAILURE}, the status is never {@code OK}.  For other states,
 * the status is always {@code OK}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1771")
public final class ConnectivityStateInfo {

    // 连接状态
    private final ConnectivityState state;

    // 请求状态
    private final Status status;

    /**
     * Returns an instance for a state that is not {@code TRANSIENT_FAILURE}.
     * 当连接状态不是 TRANSIENT_FAILURE 时返回实例
     *
     * @throws IllegalArgumentException if {@code state} is {@code TRANSIENT_FAILURE}.
     */
    public static ConnectivityStateInfo forNonError(ConnectivityState state) {
        Preconditions.checkArgument(state != TRANSIENT_FAILURE,
                "state is TRANSIENT_ERROR. Use forError() instead");
        return new ConnectivityStateInfo(state, Status.OK);
    }

    /**
     * Returns an instance for {@code TRANSIENT_FAILURE}, associated with an error status.
     * <p>
     * 返回 TRANSIENT_FAILURE 状态的实例，带有错误状态
     */
    public static ConnectivityStateInfo forTransientFailure(Status error) {
        Preconditions.checkArgument(!error.isOk(), "The error status must not be OK");
        return new ConnectivityStateInfo(TRANSIENT_FAILURE, error);
    }

    /**
     * Returns the state.
     * <p>
     * 返回连接状态
     */
    public ConnectivityState getState() {
        return state;
    }

    /**
     * Returns the status associated with the state.
     * 返回这个状态关联的请求状态
     *
     * <p>If the state is {@code TRANSIENT_FAILURE}, the status is never {@code OK}.  For other
     * states, the status is always {@code OK}.
     * 如果连接状态是 TRANSIENT_FAILURE，则请求状态不会是 OK，其他连接状态的请求状态都是 OK
     */
    public Status getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ConnectivityStateInfo)) {
            return false;
        }
        ConnectivityStateInfo o = (ConnectivityStateInfo) other;
        return state.equals(o.state) && status.equals(o.status);
    }

    @Override
    public int hashCode() {
        return state.hashCode() ^ status.hashCode();
    }

    @Override
    public String toString() {
        if (status.isOk()) {
            return state.toString();
        }
        return state + "(" + status + ")";
    }

    private ConnectivityStateInfo(ConnectivityState state, Status status) {
        this.state = Preconditions.checkNotNull(state, "state is null");
        this.status = Preconditions.checkNotNull(status, "status is null");
    }
}
