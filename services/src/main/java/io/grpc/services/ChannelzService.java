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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ExperimentalApi;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.ChannelStats;
import io.grpc.InternalChannelz.ServerList;
import io.grpc.InternalChannelz.ServerSocketsList;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.channelz.v1.ChannelzGrpc;
import io.grpc.channelz.v1.GetChannelRequest;
import io.grpc.channelz.v1.GetChannelResponse;
import io.grpc.channelz.v1.GetServerSocketsRequest;
import io.grpc.channelz.v1.GetServerSocketsResponse;
import io.grpc.channelz.v1.GetServersRequest;
import io.grpc.channelz.v1.GetServersResponse;
import io.grpc.channelz.v1.GetSocketRequest;
import io.grpc.channelz.v1.GetSocketResponse;
import io.grpc.channelz.v1.GetSubchannelRequest;
import io.grpc.channelz.v1.GetSubchannelResponse;
import io.grpc.channelz.v1.GetTopChannelsRequest;
import io.grpc.channelz.v1.GetTopChannelsResponse;
import io.grpc.stub.StreamObserver;

/**
 * The channelz service provides stats about a running gRPC process.
 * 用于提供运行汇中的 gRPC 进程统计的 Channelz
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4206")
public final class ChannelzService extends ChannelzGrpc.ChannelzImplBase {

    private final InternalChannelz channelz;

    private final int maxPageSize;

    /**
     * Creates an instance.
     * 创建实例
     */
    public static ChannelzService newInstance(int maxPageSize) {
        return new ChannelzService(InternalChannelz.instance(), maxPageSize);
    }

    @VisibleForTesting
    ChannelzService(InternalChannelz channelz, int maxPageSize) {
        this.channelz = channelz;
        this.maxPageSize = maxPageSize;
    }

    /**
     * Returns top level channel aka {@link io.grpc.ManagedChannel}.
     * 返回 ManagedChannel
     */
    @Override
    public void getTopChannels(GetTopChannelsRequest request,
                               StreamObserver<GetTopChannelsResponse> responseObserver) {

        // 获取根 Channel
        InternalChannelz.RootChannelList rootChannels = channelz.getRootChannels(request.getStartChannelId(), maxPageSize);

        GetTopChannelsResponse resp;
        try {
            // 将 Channel 转为响应结果
            resp = ChannelzProtoUtil.toGetTopChannelResponse(rootChannels);
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
            return;
        }

        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    /**
     * Returns a top level channel aka {@link io.grpc.ManagedChannel}.
     * 根据 ID 获取 Channel
     */
    @Override
    public void getChannel(GetChannelRequest request,
                           StreamObserver<GetChannelResponse> responseObserver) {
        InternalInstrumented<ChannelStats> s = channelz.getRootChannel(request.getChannelId());
        if (s == null) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("Can't find channel " + request.getChannelId()).asRuntimeException());
            return;
        }

        GetChannelResponse resp;
        try {
            resp = GetChannelResponse.newBuilder()
                                     .setChannel(ChannelzProtoUtil.toChannel(s))
                                     .build();
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
            return;
        }

        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    /**
     * Returns servers.
     * 返回 Server 集合
     */
    @Override
    public void getServers(GetServersRequest request,
                           StreamObserver<GetServersResponse> responseObserver) {
        ServerList servers = channelz.getServers(request.getStartServerId(), maxPageSize);

        GetServersResponse resp;
        try {
            resp = ChannelzProtoUtil.toGetServersResponse(servers);
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
            return;
        }

        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    /**
     * Returns a subchannel.
     * 根据 Subchannel ID 获取 Subchannel
     */
    @Override
    public void getSubchannel(GetSubchannelRequest request,
                              StreamObserver<GetSubchannelResponse> responseObserver) {

        InternalInstrumented<ChannelStats> s = channelz.getSubchannel(request.getSubchannelId());
        if (s == null) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("Can't find subchannel " + request.getSubchannelId())
                                                     .asRuntimeException());
            return;
        }

        GetSubchannelResponse resp;
        try {
            resp = GetSubchannelResponse.newBuilder()
                                        .setSubchannel(ChannelzProtoUtil.toSubchannel(s))
                                        .build();
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
            return;
        }

        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    /**
     * Returns a socket.
     * 根据 Socket ID 获取 Socket
     */
    @Override
    public void getSocket(GetSocketRequest request,
                          StreamObserver<GetSocketResponse> responseObserver) {

        InternalInstrumented<SocketStats> s = channelz.getSocket(request.getSocketId());
        if (s == null) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("Can't find socket " + request.getSocketId())
                                                     .asRuntimeException());
            return;
        }

        GetSocketResponse resp;
        try {
            resp = GetSocketResponse.newBuilder()
                                    .setSocket(ChannelzProtoUtil.toSocket(s))
                                    .build();
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
            return;
        }

        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    /**
     * 根据 Server ID 获取所有的 Socket
     */
    @Override
    public void getServerSockets(GetServerSocketsRequest request,
                                 StreamObserver<GetServerSocketsResponse> responseObserver) {

        ServerSocketsList serverSockets = channelz.getServerSockets(request.getServerId(), request.getStartSocketId(), maxPageSize);
        if (serverSockets == null) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("Can't find server " + request.getServerId())
                                                     .asRuntimeException());
            return;
        }

        GetServerSocketsResponse resp;
        try {
            resp = ChannelzProtoUtil.toGetServerSocketsResponse(serverSockets);
        } catch (StatusRuntimeException e) {
            responseObserver.onError(e);
            return;
        }

        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }
}
