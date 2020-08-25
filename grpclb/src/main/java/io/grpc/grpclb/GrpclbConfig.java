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

package io.grpc.grpclb;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.grpc.grpclb.GrpclbState.Mode;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * GRPCLB 负载均衡的配置
 */
final class GrpclbConfig {

    private final Mode mode;

    @Nullable
    private final String serviceName;

    /**
     * 创建配置
     *
     * @param mode        模式
     * @param serviceName 服务名称
     */
    private GrpclbConfig(Mode mode, @Nullable String serviceName) {
        this.mode = checkNotNull(mode, "mode");
        this.serviceName = serviceName;
    }

    /**
     * 根据模式创建配置
     *
     * @param mode 模式
     * @return 配置
     */
    static GrpclbConfig create(Mode mode) {
        return create(mode, null);
    }

    /**
     * 根据模式和服务名称创建配置
     *
     * @param mode        模式
     * @param serviceName 服务名称
     * @return 配置
     */
    static GrpclbConfig create(Mode mode, @Nullable String serviceName) {
        return new GrpclbConfig(mode, serviceName);
    }

    Mode getMode() {
        return mode;
    }

    /**
     * If specified, it overrides the name of the sevice name to be sent to the balancer. if not, the
     * target to be sent to the balancer will continue to be obtained from the target URI passed
     * to the gRPC client channel.
     * 如果指定了，将会覆盖发送给均衡器的服务名称，如果没有，将继续传递客户端 Channel 发送给均衡器的名称
     */
    @Nullable
    String getServiceName() {
        return serviceName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GrpclbConfig that = (GrpclbConfig) o;
        return mode == that.mode && Objects.equal(serviceName, that.serviceName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mode, serviceName);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("mode", mode)
                          .add("serviceName", serviceName)
                          .toString();
    }
}
