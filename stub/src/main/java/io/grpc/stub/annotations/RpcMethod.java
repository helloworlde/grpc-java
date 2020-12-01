/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.stub.annotations;

import io.grpc.MethodDescriptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link RpcMethod} contains a limited subset of information about the RPC to assist
 * <a href="https://docs.oracle.com/javase/6/docs/api/javax/annotation/processing/Processor.html">
 * Java Annotation Processors.</a>
 * RpcMethod 包含请求信息的有限子集
 *
 * <p>
 * This annotation is used by the gRPC stub compiler to annotate {@link MethodDescriptor}
 * getters.  Users should not annotate their own classes with this annotation.  Not all stubs may
 * have this annotation, so consumers should not assume that it is present.
 * 这个注解用于 gRPC Stub 来修改 MethodDescriptor 的 get 方法，用户不应当使用这个注解，不是所有的 Stub
 * 有这个注解，因此，消费者不应当认为有这个注解
 * </p>
 *
 * @since 1.14.0
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface RpcMethod {

    /**
     * The fully qualified method name.  This should match the name as returned by
     * {@link MethodDescriptor#generateFullMethodName(String, String)}.
     * 方法全名，应当和 MethodDescriptor#generateFullMethodName 返回的结果一致
     */
    String fullMethodName();

    /**
     * The request type of the method.  The request type class should be assignable from (i.e.
     * {@link Class#isAssignableFrom(Class)} the request type {@code ReqT} of the
     * {@link MethodDescriptor}.  Additionally, if the request {@code MethodDescriptor.Marshaller}
     * is a {@code MethodDescriptor.ReflectableMarshaller}, the request type should be assignable
     * from {@code MethodDescriptor.ReflectableMarshaller#getMessageClass()}.
     * <p>
     * 方法的请求类型，请求类型应当通过 MethodDescriptor 的 ReqT 分配，即 Class#isAssignableFrom(Class)，
     * 如果 MethodDescriptor.Marshaller 是 MethodDescriptor.ReflectableMarshaller，请求类型应当从
     * MethodDescriptor.ReflectableMarshaller#getMessageClass() 设置
     */
    Class<?> requestType();

    /**
     * The response type of the method.  The response type class should be assignable from (i.e.
     * {@link Class#isAssignableFrom(Class)} the response type {@code RespT} of the
     * {@link MethodDescriptor}.  Additionally, if the response {@code MethodDescriptor.Marshaller}
     * is a {@code MethodDescriptor.ReflectableMarshaller}, the response type should be assignable
     * from {@code MethodDescriptor.ReflectableMarshaller#getMessageClass()}.
     * <p>
     * 响应类型，响应类型应当通过 MethodDescriptor 的 RespT 分配，即 Class#isAssignableFrom(Class)，
     * 如果 MethodDescriptor.Marshaller 是 MethodDescriptor.ReflectableMarshaller，响应类型应当从
     * MethodDescriptor.ReflectableMarshaller#getMessageClass() 设置
     */
    Class<?> responseType();

    /**
     * The call type of the method.
     * 方法的调用类型
     */
    MethodDescriptor.MethodType methodType();
}
