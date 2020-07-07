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

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * A HelloWorld server that responds to requests with UNAVAILABLE with a given percentage.
 */
public class RetryingHelloWorldServer {
    private static final Logger logger = Logger.getLogger(RetryingHelloWorldServer.class.getName());
    private static final float unavailablePercentage = 1f;
    private static Random random = new Random();

    private Server server;

    private void start() throws IOException {
        /* The port on which the server should run */
        int port = 50051;
        server = ServerBuilder.forPort(port)
                              .addService(new GreeterImpl())
                              .build()
                              .start();
        System.out.println("Server started, listening on " + port);

        DecimalFormat df = new DecimalFormat("#%");
        System.out.println("返回 UNAVAILABLE to " + df.format(unavailablePercentage) + " 请求");
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                try {
                    RetryingHelloWorldServer.this.stop();
                } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                }
                System.err.println("*** server shut down");
            }
        });
    }

    private void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    private void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    /**
     * Main launches the server from the command line.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        final RetryingHelloWorldServer server = new RetryingHelloWorldServer();
        server.start();
        server.blockUntilShutdown();
    }

    static class GreeterImpl extends GreeterGrpc.GreeterImplBase {
        AtomicInteger retryCounter = new AtomicInteger(0);

        @Override
        public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            int count = retryCounter.incrementAndGet();
            if (random.nextFloat() < unavailablePercentage) {
                System.out.println("返回 stubbed UNAVAILABLE 错误. count: " + count + " Name:" + request.getName());
                responseObserver.onError(Status.UNAVAILABLE
                        .withDescription("重试啊啊啊啊啊啊").asRuntimeException());
            } else {
                System.out.println("不重试，count: " + count);
                HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + request.getName()).build();
                responseObserver.onNext(reply);
                responseObserver.onCompleted();
            }
        }
    }
}
