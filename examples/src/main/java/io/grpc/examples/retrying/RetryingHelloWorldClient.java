/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.examples.retrying;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import io.grpc.InternalChannelz;
import io.grpc.InternalInstrumented;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A client that requests a greeting from the {@link RetryingHelloWorldServer} with a retrying policy.
 */
public class RetryingHelloWorldClient {
    static final String ENV_DISABLE_RETRYING = "DISABLE_RETRYING_IN_RETRYING_EXAMPLE";

    private static final Logger logger = Logger.getLogger(RetryingHelloWorldClient.class.getName());

    private final boolean enableRetries;
    private final ManagedChannel channel;
    private final GreeterGrpc.GreeterBlockingStub blockingStub;
    private final AtomicInteger totalRpcs = new AtomicInteger();
    private final AtomicInteger failedRpcs = new AtomicInteger();

    protected Map<String, ?> getRetryingServiceConfig() {
        return new Gson()
                .fromJson(
                        new JsonReader(
                                new InputStreamReader(
                                        RetryingHelloWorldClient.class.getResourceAsStream(
                                                "retrying_service_config.json"),
                                        UTF_8)),
                        Map.class);
    }

    /**
     * Construct client connecting to HelloWorld server at {@code host:port}.
     */
    public RetryingHelloWorldClient(String host, int port, boolean enableRetries) {

        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port)
                                                                       // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
                                                                       // needing certificates.
                                                                       .usePlaintext();
        if (enableRetries) {
            Map<String, ?> serviceConfig = getRetryingServiceConfig();
            System.out.println("客户端重试配置: " + serviceConfig.toString());
            channelBuilder.defaultServiceConfig(serviceConfig).enableRetry();
        }
        channel = channelBuilder.build();
        blockingStub = GreeterGrpc.newBlockingStub(channel);
        this.enableRetries = enableRetries;
    }

    public void shutdown() throws InterruptedException {
        channel.shutdown().awaitTermination(60, TimeUnit.SECONDS);
    }

    /**
     * Say hello to server in a blocking unary call.
     */
    public void greet(String name) {
        HelloRequest request = HelloRequest.newBuilder().setName(name).build();
        HelloReply response = null;
        StatusRuntimeException statusRuntimeException = null;
        try {
            response = blockingStub.sayHello(request);
        } catch (StatusRuntimeException e) {
            failedRpcs.incrementAndGet();
            statusRuntimeException = e;
        }

        totalRpcs.incrementAndGet();

        if (statusRuntimeException == null) {
            System.out.println("成功: " + Arrays.toString(new Object[]{response.getMessage()}));
        } else {
            System.out.println("请求失败:" + Arrays.toString(new Object[]{statusRuntimeException.getStatus()}));
        }
    }

    private void printSummary() {
        logger.log(
                Level.INFO,
                "\n\nTotal RPCs sent: {0}. Total RPCs failed: {1}\n",
                new Object[]{
                        totalRpcs.get(), failedRpcs.get()});

        if (enableRetries) {
            // logger.log(
            //         Level.INFO,
            //         "开启了重试嘤嘤嘤. To disable retries, run the client with environment variable {0}=true.",
            //         ENV_DISABLE_RETRYING);
            System.out.println("开启了重试嘤嘤嘤");
        } else {
            // logger.log(
            //         Level.INFO,
            //         "没开启重试. To enable retries, unset environment variable {0} and then run the client.",
            //         ENV_DISABLE_RETRYING);
            System.out.println("没开启重试");
        }
    }

    public static void main(String[] args) throws Exception {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        call();

                        Thread.sleep(10000);
                        System.out.println("sleep ");
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        thread.start();

    }

    private static void call() throws InterruptedException {
        boolean enableRetries = !Boolean.parseBoolean(System.getenv(ENV_DISABLE_RETRYING));
        final RetryingHelloWorldClient client = new RetryingHelloWorldClient("localhost", 50051, enableRetries);
        ForkJoinPool executor = new ForkJoinPool();

        for (int i = 0; i < 1; i++) {
            final String userId = "user" + i;
            // executor.execute(
            //         new Runnable() {
            //             @Override
            //             public void run() {
            client.greet(userId);
            //     }
            // });

            client.getStats();
        }
        // executor.awaitQuiescence(100, TimeUnit.SECONDS);
        executor.shutdown();
        client.printSummary();
        client.shutdown();
    }


    public void getStats() {
        try {
            Field field = channel.getClass().getSuperclass().getDeclaredField("delegate");
            field.setAccessible(true);

            Object channelResult = field.get(channel);
            InternalInstrumented<InternalChannelz.ChannelStats> instrumented =  (InternalInstrumented<InternalChannelz.ChannelStats>)  channelResult;
            ListenableFuture<InternalChannelz.ChannelStats> future = instrumented.getStats();
            InternalChannelz.ChannelStats stats = future.get();
            System.out.println(stats.callsFailed);
            System.out.println(stats.callsSucceeded);
            System.out.println(stats.callsStarted);
            System.out.println(stats.target);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
