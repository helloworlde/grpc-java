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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 健康检查实现
 */
final class HealthServiceImpl extends HealthGrpc.HealthImplBase {

    private static final Logger logger = Logger.getLogger(HealthServiceImpl.class.getName());

    // Due to the latency of rpc calls, synchronization of the map does not help with consistency.
    // However, need use ConcurrentHashMap to allow concurrent reading by check().
    // 因为 rpc 调用延迟，同步的 map 不利于一致性；但是需要 ConcurrentHashMap 用于保证 check 并发读取
    private final Map<String, ServingStatus> statusMap = new ConcurrentHashMap<>();

    private final Object watchLock = new Object();

    // Indicates if future status changes should be ignored.
    // 表示是否 future 的状态变化应该被忽略
    @GuardedBy("watchLock")
    private boolean terminal;

    // Technically a Multimap<String, StreamObserver<HealthCheckResponse>>.  The Boolean value is not
    // used.  The StreamObservers need to be kept in a identity-equality set, to make sure
    // user-defined equals() doesn't confuse our book-keeping of the StreamObservers.  Constructing
    // such Multimap would require extra lines and the end result is not significantly simpler, thus I
    // would rather not have the Guava collections dependency.
    // 一个 Multimap，Boolean 值没有使用，需要将 StreamObservers 放在一个身份平等集中，确保用户自定义的 equals
    // 不会混淆 StreamObservers，构造这样的 Multimap 会需要额外的行，并且最终结果并不是很简单，因为不使用 Guava
    @GuardedBy("watchLock")
    private final HashMap<String, IdentityHashMap<StreamObserver<HealthCheckResponse>, Boolean>> watchers = new HashMap<>();

    HealthServiceImpl() {
        // Copy of what Go and C++ do.
        // 如果没有传方法，则默认的状态是 SERVING
        statusMap.put(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.SERVING);
    }

    @Override
    public void check(HealthCheckRequest request,
                      StreamObserver<HealthCheckResponse> responseObserver) {
        // 根据请求中的服务名获取状态
        ServingStatus status = statusMap.get(request.getService());
        // 如果状态是 null，则返回 NOT_FOUND 错误
        if (status == null) {
            responseObserver.onError(new StatusException(Status.NOT_FOUND.withDescription("unknown service " + request.getService())));
        } else {
            // 根据状态构造响应
            HealthCheckResponse response = HealthCheckResponse.newBuilder().setStatus(status).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void watch(HealthCheckRequest request,
                      final StreamObserver<HealthCheckResponse> responseObserver) {
        final String service = request.getService();

        // 加锁
        synchronized (watchLock) {
            // 根据服务获取状态，构建结果，并发送出去
            ServingStatus status = statusMap.get(service);
            responseObserver.onNext(getResponseForWatch(status));

            // 从 watcher 中获取服务名的 map，如果不存在，则创建一个
            IdentityHashMap<StreamObserver<HealthCheckResponse>, Boolean> serviceWatchers = watchers.get(service);

            if (serviceWatchers == null) {
                serviceWatchers = new IdentityHashMap<>();
                watchers.put(service, serviceWatchers);
            }

            // 如果存在，则将 responseObserver 添加到 map 中
            serviceWatchers.put(responseObserver, Boolean.TRUE);
        }

        Context.current().addListener(
                new CancellationListener() {
                    @Override
                    // Called when the client has closed the stream
                    public void cancelled(Context context) {
                        synchronized (watchLock) {
                            // 当客户端关闭时，从 map 中移除方法对应的数据
                            IdentityHashMap<StreamObserver<HealthCheckResponse>, Boolean> serviceWatchers = watchers.get(service);
                            if (serviceWatchers != null) {
                                serviceWatchers.remove(responseObserver);
                                if (serviceWatchers.isEmpty()) {
                                    watchers.remove(service);
                                }
                            }
                        }
                    }
                },
                MoreExecutors.directExecutor());
    }

    /**
     * 设置服务的状态
     *
     * @param service 服务名称
     * @param status  状态
     */
    void setStatus(String service, ServingStatus status) {
        synchronized (watchLock) {
            if (terminal) {
                logger.log(Level.FINE, "Ignoring status {} for {}", new Object[]{status, service});
                return;
            }
            setStatusInternal(service, status);
        }
    }

    @GuardedBy("watchLock")
    private void setStatusInternal(String service, ServingStatus status) {
        // 设置新的状态
        ServingStatus prevStatus = statusMap.put(service, status);
        // 如果状态不一样，则通知状态变化
        if (prevStatus != status) {
            notifyWatchers(service, status);
        }
    }

    /**
     * 清除服务状态
     *
     * @param service 服务名
     */
    void clearStatus(String service) {
        synchronized (watchLock) {
            if (terminal) {
                logger.log(Level.FINE, "Ignoring status clearing for {}", new Object[]{service});
                return;
            }
            // 移除状态
            ServingStatus prevStatus = statusMap.remove(service);
            if (prevStatus != null) {
                // 更新状态
                notifyWatchers(service, null);
            }
        }
    }

    /**
     * 通知进入终止状态
     */
    void enterTerminalState() {
        synchronized (watchLock) {
            if (terminal) {
                logger.log(Level.WARNING, "Already terminating", new RuntimeException());
                return;
            }
            terminal = true;
            // 遍历所有的监听器，更新状态为 NOT_SERVING
            for (String service : statusMap.keySet()) {
                setStatusInternal(service, ServingStatus.NOT_SERVING);
            }
        }
    }

    /**
     * 返回监听的数量
     *
     * @param service 服务名
     * @return 数量
     */
    @VisibleForTesting
    int numWatchersForTest(String service) {
        synchronized (watchLock) {
            IdentityHashMap<StreamObserver<HealthCheckResponse>, Boolean> serviceWatchers = watchers.get(service);
            if (serviceWatchers == null) {
                return 0;
            }
            return serviceWatchers.size();
        }
    }

    /**
     * 通知状态变化
     *
     * @param service 服务名
     * @param status  新的状态
     */
    @GuardedBy("watchLock")
    private void notifyWatchers(String service, @Nullable ServingStatus status) {
        // 构建结果
        HealthCheckResponse response = getResponseForWatch(status);

        IdentityHashMap<StreamObserver<HealthCheckResponse>, Boolean> serviceWatchers = watchers.get(service);

        // 如果有监听，则遍历所有的监听，发送结果
        if (serviceWatchers != null) {
            for (StreamObserver<HealthCheckResponse> responseObserver : serviceWatchers.keySet()) {
                responseObserver.onNext(response);
            }
        }
    }

    /**
     * 根据服务状态获取响应结果
     *
     * @param recordedStatus 服务状态
     * @return 响应结果
     */
    private static HealthCheckResponse getResponseForWatch(@Nullable ServingStatus recordedStatus) {
        return HealthCheckResponse.newBuilder()
                                  .setStatus(recordedStatus == null ? ServingStatus.SERVICE_UNKNOWN : recordedStatus)
                                  .build();
    }
}
