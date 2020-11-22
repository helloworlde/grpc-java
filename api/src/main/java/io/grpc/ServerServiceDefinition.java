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

package io.grpc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Definition of a service to be exposed via a Server.
 * 定义 Server 需要暴露的服务定义
 */
public final class ServerServiceDefinition {

    /**
     * Convenience that constructs a {@link ServiceDescriptor} simultaneously.
     * 根据服务名构造 Builder
     */
    public static Builder builder(String serviceName) {
        return new Builder(serviceName);
    }

    /**
     * 根据 ServiceDescriptor 构造 Builder
     */
    public static Builder builder(ServiceDescriptor serviceDescriptor) {
        return new Builder(serviceDescriptor);
    }

    /**
     * 服务描述
     */
    private final ServiceDescriptor serviceDescriptor;

    /**
     * 方法定义集合
     */
    private final Map<String, ServerMethodDefinition<?, ?>> methods;

    private ServerServiceDefinition(ServiceDescriptor serviceDescriptor,
                                    Map<String, ServerMethodDefinition<?, ?>> methods) {
        this.serviceDescriptor = checkNotNull(serviceDescriptor, "serviceDescriptor");
        this.methods = Collections.unmodifiableMap(new HashMap<>(methods));
    }

    /**
     * The descriptor for the service.
     * 返回服务的描述
     */
    public ServiceDescriptor getServiceDescriptor() {
        return serviceDescriptor;
    }

    /**
     * Gets all the methods of service.
     * 获取服务所有的方法
     */
    public Collection<ServerMethodDefinition<?, ?>> getMethods() {
        return methods.values();
    }

    /**
     * Look up a method by its fully qualified name.
     * 通过方法的限定名查找方法
     *
     * @param methodName the fully qualified name without leading slash. E.g., "com.foo.Foo/Bar"
     *                   没有前斜杠的方法的限定名
     */
    @Internal
    public ServerMethodDefinition<?, ?> getMethod(String methodName) {
        return methods.get(methodName);
    }

    /**
     * Builder for constructing Service instances.
     * 用于构造方法实例的构造器
     */
    public static final class Builder {

        /**
         * 服务名称
         */
        private final String serviceName;
        /**
         * 服务定义
         */
        private final ServiceDescriptor serviceDescriptor;
        /**
         * 服务定义集合
         */
        private final Map<String, ServerMethodDefinition<?, ?>> methods = new HashMap<>();

        private Builder(String serviceName) {
            this.serviceName = checkNotNull(serviceName, "serviceName");
            this.serviceDescriptor = null;
        }

        private Builder(ServiceDescriptor serviceDescriptor) {
            this.serviceDescriptor = checkNotNull(serviceDescriptor, "serviceDescriptor");
            this.serviceName = serviceDescriptor.getName();
        }

        /**
         * Add a method to be supported by the service.
         * 添加服务支持的方法
         *
         * @param method  the {@link MethodDescriptor} of this method.
         *                这个方法的描述
         * @param handler handler for incoming calls
         *                方法的处理器
         */
        public <ReqT, RespT> Builder addMethod(MethodDescriptor<ReqT, RespT> method,
                                               ServerCallHandler<ReqT, RespT> handler) {
            return addMethod(ServerMethodDefinition.create(
                    checkNotNull(method, "method must not be null"),
                    checkNotNull(handler, "handler must not be null")));
        }

        /**
         * Add a method to be supported by the service.
         * 添加服务支持的方法
         */
        public <ReqT, RespT> Builder addMethod(ServerMethodDefinition<ReqT, RespT> def) {
            MethodDescriptor<ReqT, RespT> method = def.getMethodDescriptor();
            checkArgument(serviceName.equals(method.getServiceName()),
                    "Method name should be prefixed with service name and separated with '/'. "
                            + "Expected service name: '%s'. Actual fully qualifed method name: '%s'.",
                    serviceName, method.getFullMethodName());
            String name = method.getFullMethodName();
            checkState(!methods.containsKey(name), "Method by same name already registered: %s", name);
            methods.put(name, def);
            return this;
        }

        /**
         * Construct new ServerServiceDefinition.
         * 构建新的 ServerServiceDefinition
         */
        public ServerServiceDefinition build() {
            ServiceDescriptor serviceDescriptor = this.serviceDescriptor;
            // 如果服务定义为 null，则遍历方法，用服务名和方法集合构建新的服务定义
            if (serviceDescriptor == null) {
                List<MethodDescriptor<?, ?>> methodDescriptors = new ArrayList<>(methods.size());
                for (ServerMethodDefinition<?, ?> serverMethod : methods.values()) {
                    methodDescriptors.add(serverMethod.getMethodDescriptor());
                }
                serviceDescriptor = new ServiceDescriptor(serviceName, methodDescriptors);
            }

            Map<String, ServerMethodDefinition<?, ?>> tmpMethods = new HashMap<>(methods);
            // 遍历方法定义，校验是否所有的方法都在方法定义中
            for (MethodDescriptor<?, ?> descriptorMethod : serviceDescriptor.getMethods()) {
                ServerMethodDefinition<?, ?> removed = tmpMethods.remove(descriptorMethod.getFullMethodName());

                if (removed == null) {
                    throw new IllegalStateException("No method bound for descriptor entry " + descriptorMethod.getFullMethodName());
                }
                if (removed.getMethodDescriptor() != descriptorMethod) {
                    throw new IllegalStateException("Bound method for " + descriptorMethod.getFullMethodName()
                            + " not same instance as method in service descriptor");
                }
            }

            if (tmpMethods.size() > 0) {
                throw new IllegalStateException("No entry in descriptor matching bound method "
                        + tmpMethods.values().iterator().next().getMethodDescriptor().getFullMethodName());
            }
            // 根据服务定义和方法定义集合构建服务定义
            return new ServerServiceDefinition(serviceDescriptor, methods);
        }
    }
}
