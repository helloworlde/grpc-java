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

import com.google.common.base.MoreObjects;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Descriptor for a service.
 * 服务描述
 *
 * @since 1.0.0
 */
public final class ServiceDescriptor {

    /**
     * 服务名称
     */
    private final String name;
    /**
     * 服务方法
     */
    private final Collection<MethodDescriptor<?, ?>> methods;
    /**
     * 协议描述
     */
    private final Object schemaDescriptor;

    /**
     * Constructs a new Service Descriptor.  Users are encouraged to use {@link #newBuilder}
     * instead.
     * 构建新的服务描述，建议用户使用 newBuilder 代替
     *
     * @param name    The name of the service
     *                服务名称
     * @param methods The methods that are part of the service
     *                服务的方法
     * @since 1.0.0
     */
    public ServiceDescriptor(String name, MethodDescriptor<?, ?>... methods) {
        this(name, Arrays.asList(methods));
    }

    /**
     * Constructs a new Service Descriptor.  Users are encouraged to use {@link #newBuilder}
     * instead.
     * 构建新的服务描述，建议用户使用 newBuilder 代替
     *
     * @param name    The name of the service
     *                服务名称
     * @param methods The methods that are part of the service
     *                服务的方法集合
     * @since 1.0.0
     */
    public ServiceDescriptor(String name, Collection<MethodDescriptor<?, ?>> methods) {
        this(newBuilder(name).addAllMethods(checkNotNull(methods, "methods")));
    }

    /**
     * 使用 Builder 构建
     */
    private ServiceDescriptor(Builder b) {
        this.name = b.name;
        validateMethodNames(name, b.methods);
        this.methods = Collections.unmodifiableList(new ArrayList<>(b.methods));
        this.schemaDescriptor = b.schemaDescriptor;
    }

    /**
     * Simple name of the service. It is not an absolute path.
     * 获取服务的简称，不是绝对的路径
     *
     * @since 1.0.0
     */
    public String getName() {
        return name;
    }

    /**
     * A collection of {@link MethodDescriptor} instances describing the methods exposed by the
     * service.
     * 服务暴露的方法描述的实例集合
     *
     * @since 1.0.0
     */
    public Collection<MethodDescriptor<?, ?>> getMethods() {
        return methods;
    }

    /**
     * Returns the schema descriptor for this service.  A schema descriptor is an object that is not
     * used by gRPC core but includes information related to the service.  The type of the object
     * is specific to the consumer, so both the code setting the schema descriptor and the code
     * calling {@link #getSchemaDescriptor()} must coordinate.  For example, protobuf generated code
     * sets this value, in order to be consumed by the server reflection service.  See also:
     * {@code io.grpc.protobuf.ProtoFileDescriptorSupplier}.
     * 返回服务的协议描述，协议描述是不被 gRPC 核心使用但是包含服务相关信息的对象，对象的类型由消费者特定，所以
     * 设置协议和获取协议的方法必须相匹配，如，protobuf 生成代码设置这个值，为了被服务端反射服务消费
     *
     * @since 1.1.0
     */
    @Nullable
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
    public Object getSchemaDescriptor() {
        return schemaDescriptor;
    }

    /**
     * 校验服务方法
     *
     * @param serviceName 服务名称
     * @param methods     方法集合
     */
    static void validateMethodNames(String serviceName, Collection<MethodDescriptor<?, ?>> methods) {
        Set<String> allNames = new HashSet<>(methods.size());
        for (MethodDescriptor<?, ?> method : methods) {
            checkNotNull(method, "method");
            String methodServiceName = method.getServiceName();
            checkArgument(serviceName.equals(methodServiceName), "service names %s != %s", methodServiceName, serviceName);
            checkArgument(allNames.add(method.getFullMethodName()), "duplicate name %s", method.getFullMethodName());
        }
    }

    /**
     * Creates a new builder for a {@link ServiceDescriptor}.
     * 创建 ServiceDescriptor 的信息 builder
     *
     * @since 1.1.0
     */
    public static Builder newBuilder(String name) {
        return new Builder(name);
    }

    /**
     * A builder for a {@link ServiceDescriptor}.
     * ServiceDescriptor 的构建器
     *
     * @since 1.1.0
     */
    public static final class Builder {
        private Builder(String name) {
            setName(name);
        }

        /**
         * 服务名称
         */
        private String name;
        /**
         * 方法集合
         */
        private List<MethodDescriptor<?, ?>> methods = new ArrayList<>();
        /**
         * 协议
         */
        private Object schemaDescriptor;

        /**
         * Sets the name.  This should be non-{@code null}.
         * 设置服务名称
         *
         * @param name The name of the service.
         * @return this builder.
         * @since 1.1.0
         */
        @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2666")
        public Builder setName(String name) {
            this.name = checkNotNull(name, "name");
            return this;
        }

        /**
         * Adds a method to this service.  This should be non-{@code null}.
         * 添加方法
         *
         * @param method the method to add to the descriptor.
         * @return this builder.
         * @since 1.1.0
         */
        public Builder addMethod(MethodDescriptor<?, ?> method) {
            methods.add(checkNotNull(method, "method"));
            return this;
        }

        /**
         * Currently not exposed.  Bulk adds methods to this builder.
         * 添加所有的方法
         *
         * @param methods the methods to add.
         * @return this builder.
         */
        private Builder addAllMethods(Collection<MethodDescriptor<?, ?>> methods) {
            this.methods.addAll(methods);
            return this;
        }

        /**
         * Sets the schema descriptor for this builder.  A schema descriptor is an object that is not
         * used by gRPC core but includes information related to the service.  The type of the object
         * is specific to the consumer, so both the code calling this and the code calling
         * {@link ServiceDescriptor#getSchemaDescriptor()} must coordinate.  For example, protobuf
         * generated code sets this value, in order to be consumed by the server reflection service.
         * <p>
         * 设置协议
         *
         * @param schemaDescriptor an object that describes the service structure.  Should be immutable.
         * @return this builder.
         * @since 1.1.0
         */
        public Builder setSchemaDescriptor(@Nullable Object schemaDescriptor) {
            this.schemaDescriptor = schemaDescriptor;
            return this;
        }

        /**
         * Constructs a new {@link ServiceDescriptor}.  {@link #setName} should have been called with a
         * non-{@code null} value before calling this.
         * 构建新的 ServiceDescriptor
         *
         * @return a new ServiceDescriptor
         * @since 1.1.0
         */
        public ServiceDescriptor build() {
            return new ServiceDescriptor(this);
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("schemaDescriptor", schemaDescriptor)
                          .add("methods", methods)
                          .omitNullValues()
                          .toString();
    }
}