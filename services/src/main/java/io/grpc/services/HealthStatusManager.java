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

package io.grpc.services;

import io.grpc.BindableService;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A {@code HealthStatusManager} object manages a health check service. A health check service is
 * created in the constructor of {@code HealthStatusManager}, and it can be retrieved by the
 * {@link #getHealthService()} method.
 * 管理健康检查服务的 HealthStatusManager 对象，健康检查服务在 HealthStatusManager 的构造器中创建，
 * 可以通过 getHealthService() 方法获取
 * <p>
 * The health status manager can update the health statuses of the server.
 * HealthStatusManager 可以更新 server 的健康检查状态
 *
 * <p>The default, empty-string, service name, {@link #SERVICE_NAME_ALL_SERVICES}, is initialized to
 * {@link ServingStatus#SERVING}.
 * SERVICE_NAME_ALL_SERVICES 用于默认的服务名，是个空字符串，初始状态为 ServingStatus#SERVING
 */
@io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/4696")
public final class HealthStatusManager {
    /**
     * The special "service name" that represent all services on a GRPC server.  It is an empty
     * string.
     * 代表所有 gRPC server 的服务名，是个空字符串
     */
    public static final String SERVICE_NAME_ALL_SERVICES = "";

    /**
     * 健康检查实现类
     */
    private final HealthServiceImpl healthService;

    /**
     * Creates a new health service instance.
     * 创建一个新的健康检查实例
     */
    public HealthStatusManager() {
        healthService = new HealthServiceImpl();
    }

    /**
     * Gets the health check service created in the constructor.
     * 获取构造器中初始化的健康检查实例
     */
    public BindableService getHealthService() {
        return healthService;
    }

    /**
     * Updates the status of the server.
     * 更新 server 的状态
     *
     * @param service the name of some aspect of the server that is associated with a health status.
     *                This name can have no relation with the gRPC services that the server is running with.
     *                It can also be an empty String {@code ""} per the gRPC specification.
     *                与健康状态相关联的服务器某些方面的名称，该名称与运行服务器的 gRPC 服务无关，也可以为空字符串
     * @param status  is one of the values {@link ServingStatus#SERVING},
     *                {@link ServingStatus#NOT_SERVING} and {@link ServingStatus#UNKNOWN}.
     *                状态是 ServingStatus#SERVING，ServingStatus#NOT_SERVING，ServingStatus#UNKNOWN 的其中一个值
     */
    public void setStatus(String service, ServingStatus status) {
        checkNotNull(status, "status");
        healthService.setStatus(service, status);
    }

    /**
     * Clears the health status record of a service. The health service will respond with NOT_FOUND
     * error on checking the status of a cleared service.
     * 清除服务的健康状态，被清除的服务的状态将会返回 NOT_FOUND
     *
     * @param service the name of some aspect of the server that is associated with a health status.
     *                This name can have no relation with the gRPC services that the server is running with.
     *                It can also be an empty String {@code ""} per the gRPC specification.
     *                服务的名称
     */
    public void clearStatus(String service) {
        healthService.clearStatus(service);
    }

    /**
     * enterTerminalState causes the health status manager to mark all services as not serving, and
     * prevents future updates to services.  This method is meant to be called prior to server
     * shutdown as a way to indicate that clients should redirect their traffic elsewhere.
     * <p>
     * 进入终止状态会导致所有的服务都是 NOT_SERVING，并且阻止更新，这个方法在 server 关闭前调用，提示
     * 客户端应当请求其他的服务
     */
    public void enterTerminalState() {
        healthService.enterTerminalState();
    }
}
