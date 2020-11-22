/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.util;

import io.grpc.BindableService;
import io.grpc.ExperimentalApi;
import io.grpc.HandlerRegistry;
import io.grpc.MethodDescriptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default implementation of {@link MutableHandlerRegistry}.
 * 处理器注册器的实现
 *
 * <p>Uses {@link ConcurrentHashMap} to avoid service registration excessively
 * blocking method lookup.
 */
@ThreadSafe
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/933")
public final class MutableHandlerRegistry extends HandlerRegistry {

    private final ConcurrentMap<String, ServerServiceDefinition> services = new ConcurrentHashMap<>();

    /**
     * Registers a service.
     * 注册服务
     *
     * @return the previously registered service with the same service descriptor name if exists,
     * otherwise {@code null}.
     * 返回同一个服务定义之前注册的定义，如果没有则返回 null
     */
    @Nullable
    public ServerServiceDefinition addService(ServerServiceDefinition service) {
        return services.put(service.getServiceDescriptor().getName(), service);
    }

    /**
     * Registers a service.
     * 注册服务
     *
     * @return the previously registered service with the same service descriptor name if exists,
     * otherwise {@code null}.
     * 返回同一个服务定义之前注册的定义，如果没有则返回 null
     */
    @Nullable
    public ServerServiceDefinition addService(BindableService bindableService) {
        return addService(bindableService.bindService());
    }

    /**
     * Removes a registered service
     * 移除注册的服务
     *
     * @return true if the service was found to be removed.
     * 如果存在且被移除则返回 true
     */
    public boolean removeService(ServerServiceDefinition service) {
        return services.remove(service.getServiceDescriptor().getName(), service);
    }

    /**
     * Note: This does not necessarily return a consistent view of the map.
     * 返回所有注册的服务
     */
    @Override
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
    public List<ServerServiceDefinition> getServices() {
        return Collections.unmodifiableList(new ArrayList<>(services.values()));
    }

    /**
     * Note: This does not actually honor the authority provided.  It will, eventually in the future.
     * 根据方法限定名查找服务
     */
    @Override
    @Nullable
    public ServerMethodDefinition<?, ?> lookupMethod(String methodName, @Nullable String authority) {
        // 从方法名中获取服务名
        String serviceName = MethodDescriptor.extractFullServiceName(methodName);

        // 如果没有则返回 null
        if (serviceName == null) {
            return null;
        }

        // 根据服务名从注册器中获取服务
        ServerServiceDefinition service = services.get(serviceName);
        if (service == null) {
            return null;
        }
        // 根据方法名从服务中获取方法
        return service.getMethod(methodName);
    }
}
