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

package io.grpc.internal;

import io.grpc.ChannelLogger;
import io.grpc.InternalChannelz.ChannelTrace.Event;
import io.grpc.InternalChannelz.ChannelTrace.Event.Severity;
import io.grpc.InternalLogId;

import java.text.MessageFormat;
import java.util.logging.Level;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * 日志记录实现
 */
final class ChannelLoggerImpl extends ChannelLogger {
    private final ChannelTracer tracer;
    private final TimeProvider time;

    ChannelLoggerImpl(ChannelTracer tracer, TimeProvider time) {
        this.tracer = checkNotNull(tracer, "tracer");
        this.time = checkNotNull(time, "time");
    }

    @Override
    public void log(ChannelLogLevel level, String msg) {
        logOnly(tracer.getLogId(), level, msg);
        if (isTraceable(level)) {
            trace(level, msg);
        }
    }

    @Override
    public void log(ChannelLogLevel level, String messageFormat, Object... args) {
        String msg = null;
        Level javaLogLevel = toJavaLogLevel(level);
        if (isTraceable(level) || ChannelTracer.logger.isLoggable(javaLogLevel)) {
            msg = MessageFormat.format(messageFormat, args);
        }
        log(level, msg);
    }

    /**
     * 仅记录日志
     *
     * @param logId 日志 ID
     * @param level 日志级别
     * @param msg   日志
     */
    static void logOnly(InternalLogId logId, ChannelLogLevel level, String msg) {
        Level javaLogLevel = toJavaLogLevel(level);
        if (ChannelTracer.logger.isLoggable(javaLogLevel)) {
            ChannelTracer.logOnly(logId, javaLogLevel, msg);
        }
    }

    /**
     * 仅记录日志
     *
     * @param logId         日志 ID
     * @param level         日志级别
     * @param messageFormat 日志格式
     * @param args          参数
     */
    static void logOnly(InternalLogId logId,
                        ChannelLogLevel level,
                        String messageFormat,
                        Object... args) {

        Level javaLogLevel = toJavaLogLevel(level);

        if (ChannelTracer.logger.isLoggable(javaLogLevel)) {
            String msg = MessageFormat.format(messageFormat, args);
            ChannelTracer.logOnly(logId, javaLogLevel, msg);
        }
    }

    /**
     * 是否记录 trace
     * 最终由日志级别和 ChannelBuilder 的 maxTraceEvents 决定
     *
     * @param level 级别
     * @return 是否记录 trace
     */
    private boolean isTraceable(ChannelLogLevel level) {
        return level != ChannelLogLevel.DEBUG && tracer.isTraceEnabled();
    }

    /**
     * 记录 trace
     *
     * @param level 级别
     * @param msg   日志
     */
    private void trace(ChannelLogLevel level, String msg) {
        if (level == ChannelLogLevel.DEBUG) {
            return;
        }
        tracer.traceOnly(new Event.Builder()
                .setDescription(msg)
                .setSeverity(toTracerSeverity(level))
                .setTimestampNanos(time.currentTimeNanos())
                .build());
    }

    /**
     * 转为 Channelz 的日志级别
     *
     * @param level ChannelLog 级别
     * @return Channelz 日志级别
     */
    private static Severity toTracerSeverity(ChannelLogLevel level) {
        switch (level) {
            case ERROR:
                return Severity.CT_ERROR;
            case WARNING:
                return Severity.CT_WARNING;
            default:
                return Severity.CT_INFO;
        }
    }

    /**
     * 转为 Java 日志级别
     *
     * @param level ChannelLog 日志级别
     * @return Java 日志级别
     */
    private static Level toJavaLogLevel(ChannelLogLevel level) {
        switch (level) {
            case ERROR:
                return Level.FINE;
            case WARNING:
                return Level.FINER;
            default:
                return Level.FINEST;
        }
    }
}
