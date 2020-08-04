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

package io.grpc.services;

import com.google.common.base.Preconditions;
import io.grpc.CallOptions;
import io.grpc.ClientInterceptor;
import io.grpc.ServerInterceptor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The default implementation of a {@link BinaryLogProvider}.
 * BinaryLogProvider 的默认实现
 */
class BinaryLogProviderImpl extends BinaryLogProvider {
    // avoid using 0 because proto3 long fields default to 0 when unset
    private static final AtomicLong counter = new AtomicLong(1);

    private final BinlogHelper.Factory factory;
    private final BinaryLogSink sink;

    /**
     * 构造提供器实现
     *
     * @throws IOException
     */
    public BinaryLogProviderImpl() throws IOException {
        this(new TempFileSink(), System.getenv("GRPC_BINARY_LOG_CONFIG"));
    }

    /**
     * Deprecated and will be removed in a future version of gRPC.
     */
    @Deprecated
    public BinaryLogProviderImpl(BinaryLogSink sink) throws IOException {
        this(sink, System.getenv("GRPC_BINARY_LOG_CONFIG"));
    }

    /**
     * Creates an instance.
     * 构造实例
     *
     * @param sink      ownership is transferred to this class.
     *                  所有权被转移到这个类
     * @param configStr config string to parse to determine logged methods and msg size limits.
     *                  解析并决定记录日志的方法和日志大小限制的配置
     * @throws IOException if initialization failed. 初始化失败时抛出
     */
    public BinaryLogProviderImpl(BinaryLogSink sink, String configStr) throws IOException {
        this.sink = Preconditions.checkNotNull(sink);
        try {
            factory = new BinlogHelper.FactoryImpl(sink, configStr);
        } catch (RuntimeException e) {
            sink.close();
            // parsing the conf string may throw if it is blank or contains errors
            throw new IOException(
                    "Can not initialize. The env variable GRPC_BINARY_LOG_CONFIG must be valid.", e);
        }
    }

    @Nullable
    @Override
    public ServerInterceptor getServerInterceptor(String fullMethodName) {
        BinlogHelper helperForMethod = factory.getLog(fullMethodName);
        if (helperForMethod == null) {
            return null;
        }
        return helperForMethod.getServerInterceptor(counter.getAndIncrement());
    }

    /**
     * 获取二进制日志拦截器
     *
     * @param fullMethodName 方法名
     * @param callOptions    调用选项
     * @return 二进制日志拦截器
     */
    @Nullable
    @Override
    public ClientInterceptor getClientInterceptor(String fullMethodName, CallOptions callOptions) {
        // 根据方法名获取二进制日志工具
        BinlogHelper helperForMethod = factory.getLog(fullMethodName);
        if (helperForMethod == null) {
            return null;
        }
        // 获取拦截器
        return helperForMethod.getClientInterceptor(counter.getAndIncrement());
    }

    @Override
    public void close() throws IOException {
        sink.close();
    }
}
