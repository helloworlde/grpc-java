/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.netty;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.netty.ListeningEncoder.Http2OutboundFrameListener;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;

import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static io.netty.handler.codec.http2.Http2CodecUtil.getEmbeddedHttp2Exception;

/**
 * Base class for all Netty gRPC handlers. This class standardizes exception handling (always
 * shutdown the connection) as well as sending the initial connection window at startup.
 * 基于 Netty 的 gRPC 处理器的基类，这个类用于标准化处理异常(总是关闭连接)以及在启动时发送初始连接窗口
 */
abstract class AbstractNettyHandler extends GrpcHttp2ConnectionHandler {

    private static final long GRACEFUL_SHUTDOWN_NO_TIMEOUT = -1;
    private static final int MAX_ALLOWED_PING = 2;

    private final int initialConnectionWindow;
    // ping 计数监听器
    private final PingCountingListener pingCountingListener = new PingCountingListener();
    // 流控控制器
    private final FlowControlPinger flowControlPing = new FlowControlPinger(MAX_ALLOWED_PING);
    // 流控自动打开
    private boolean autoTuneFlowControlOn;
    // Channel 处理器上下文
    private ChannelHandlerContext ctx;
    private boolean initialWindowSent = false;

    private static final long BDP_MEASUREMENT_PING = 1234;

    AbstractNettyHandler(ChannelPromise channelUnused,
                         Http2ConnectionDecoder decoder,
                         Http2ConnectionEncoder encoder,
                         Http2Settings initialSettings,
                         boolean autoFlowControl) {
        super(channelUnused, decoder, encoder, initialSettings);

        // During a graceful shutdown, wait until all streams are closed.
        // 设置等待优雅关闭的时间
        gracefulShutdownTimeoutMillis(GRACEFUL_SHUTDOWN_NO_TIMEOUT);

        // Extract the connection window from the settings if it was set.
        // 设置初始连接窗口大小
        this.initialConnectionWindow = initialSettings.initialWindowSize() == null ? -1 : initialSettings.initialWindowSize();
        this.autoTuneFlowControlOn = autoFlowControl;
        // 设置监听器
        if (encoder instanceof ListeningEncoder) {
            ((ListeningEncoder) encoder).setListener(pingCountingListener);
        }
    }

    /**
     * Channel 处理器添加回调
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        // Sends the connection preface if we haven't already.
        super.handlerAdded(ctx);
        // 发送初始连接窗口
        sendInitialConnectionWindow();
    }

    /**
     * Channel 活跃回调
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Sends connection preface if we haven't already.
        super.channelActive(ctx);
        // 发送初始连接窗口
        sendInitialConnectionWindow();
    }

    /**
     * 异常处理
     */
    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        Http2Exception embedded = getEmbeddedHttp2Exception(cause);
        if (embedded == null) {
            // There was no embedded Http2Exception, assume it's a connection error. Subclasses are
            // responsible for storing the appropriate status and shutting down the connection.
            onError(ctx, /* outbound= */ false, cause);
        } else {
            super.exceptionCaught(ctx, cause);
        }
    }

    /**
     * Channel 处理器上下文
     */
    protected final ChannelHandlerContext ctx() {
        return ctx;
    }

    /**
     * Sends initial connection window to the remote endpoint if necessary.
     * 必要时给远程端点发送初始连接窗口
     */
    private void sendInitialConnectionWindow() throws Http2Exception {
        // 如果没有发送初始连接窗口，且 Channel 活跃
        if (!initialWindowSent && ctx.channel().isActive()) {
            Http2Stream connectionStream = connection().connectionStream();
            // 计算窗口
            int currentSize = connection().local().flowController().windowSize(connectionStream);
            int delta = initialConnectionWindow - currentSize;
            // 增加窗口
            decoder().flowController().incrementWindowSize(connectionStream, delta);
            initialWindowSent = true;
            // 发送
            ctx.flush();
        }
    }

    @VisibleForTesting
    FlowControlPinger flowControlPing() {
        return flowControlPing;
    }

    @VisibleForTesting
    void setAutoTuneFlowControl(boolean isOn) {
        autoTuneFlowControlOn = isOn;
    }

    /**
     * Class for handling flow control pinging and flow control window updates as necessary.
     * 用于处理 ping 流控和必要时控制窗口更新的类
     */
    final class FlowControlPinger {

        // 窗口大小为 8M
        private static final int MAX_WINDOW_SIZE = 8 * 1024 * 1024;
        private final int maxAllowedPing;
        private int pingCount;
        private int pingReturn;
        private boolean pinging;
        private int dataSizeSincePing;
        private float lastBandwidth; // bytes per second
        private long lastPingTime;

        public FlowControlPinger(int maxAllowedPing) {
            checkArgument(maxAllowedPing > 0, "maxAllowedPing must be positive");
            this.maxAllowedPing = maxAllowedPing;
        }

        public long payload() {
            return BDP_MEASUREMENT_PING;
        }

        public int maxWindow() {
            return MAX_WINDOW_SIZE;
        }

        /**
         * 当数据读取时触发
         */
        public void onDataRead(int dataLength, int paddingLength) {
            // 如果没有打开自动流控，则返回
            if (!autoTuneFlowControlOn) {
                return;
            }
            // 如果不是正在 ping，且 ping 的次数小于最大次数
            if (!isPinging() && pingCountingListener.pingCount < maxAllowedPing) {
                // 修改 ping 状态
                setPinging(true);
                // 发送 ping
                sendPing(ctx());
            }
            // 增加 ping 之后的数据数量
            incrementDataSincePing(dataLength + paddingLength);
        }

        /**
         * 更新窗口
         */
        public void updateWindow() throws Http2Exception {
            // 如果没有打开自动流控，则返回
            if (!autoTuneFlowControlOn) {
                return;
            }
            pingReturn++;
            long elapsedTime = (System.nanoTime() - lastPingTime);
            if (elapsedTime == 0) {
                elapsedTime = 1;
            }
            // 窗口时间
            long bandwidth = (getDataSincePing() * TimeUnit.SECONDS.toNanos(1)) / elapsedTime;

            Http2LocalFlowController fc = decoder().flowController();

            // Calculate new window size by doubling the observed BDP, but cap at max window
            // 计算新的窗口大小
            int targetWindow = Math.min(getDataSincePing() * 2, MAX_WINDOW_SIZE);
            setPinging(false);
            int currentWindow = fc.initialWindowSize(connection().connectionStream());
            // 如果目标窗口超过当前窗口，则增加窗口大小
            if (targetWindow > currentWindow && bandwidth > lastBandwidth) {
                lastBandwidth = bandwidth;
                int increase = targetWindow - currentWindow;
                fc.incrementWindowSize(connection().connectionStream(), increase);
                fc.initialWindowSize(targetWindow);
                Http2Settings settings = new Http2Settings();
                settings.initialWindowSize(targetWindow);
                frameWriter().writeSettings(ctx(), settings, ctx().newPromise());
            }
        }

        private boolean isPinging() {
            return pinging;
        }

        private void setPinging(boolean pingOut) {
            pinging = pingOut;
        }

        /**
         * 发送 ping
         */
        private void sendPing(ChannelHandlerContext ctx) {
            // 修改属性，设置最新 ping 的时间
            setDataSizeSincePing(0);
            lastPingTime = System.nanoTime();

            encoder().writePing(ctx, false, BDP_MEASUREMENT_PING, ctx.newPromise());
            pingCount++;
        }

        private void incrementDataSincePing(int increase) {
            int currentSize = getDataSincePing();
            setDataSizeSincePing(currentSize + increase);
        }

        @VisibleForTesting
        int getPingCount() {
            return pingCount;
        }

        @VisibleForTesting
        int getPingReturn() {
            return pingReturn;
        }

        @VisibleForTesting
        int getDataSincePing() {
            return dataSizeSincePing;
        }

        private void setDataSizeSincePing(int dataSize) {
            dataSizeSincePing = dataSize;
        }

        @VisibleForTesting
        void setDataSizeAndSincePing(int dataSize) {
            setDataSizeSincePing(dataSize);
            lastPingTime = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);
        }
    }

    /**
     * ping 计数器监听器
     */
    private static class PingCountingListener extends Http2OutboundFrameListener {
        int pingCount = 0;

        @Override
        public void onWindowUpdate(int streamId, int windowSizeIncrement) {
            pingCount = 0;
            super.onWindowUpdate(streamId, windowSizeIncrement);
        }

        @Override
        public void onPing(boolean ack, long data) {
            if (!ack) {
                pingCount++;
            }
            super.onPing(ack, data);
        }

        @Override
        public void onData(int streamId, ByteBuf data, int padding, boolean endStream) {
            pingCount = 0;
            super.onData(streamId, data, padding, endStream);
        }
    }
}
