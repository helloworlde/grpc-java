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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reschedules a runnable lazily.
 * 重新调度任务并延迟执行
 */
final class Rescheduler {

    // deps
    private final ScheduledExecutorService scheduler;
    private final Executor serializingExecutor;
    private final Runnable runnable;

    // state
    private final Stopwatch stopwatch;
    private long runAtNanos;
    private boolean enabled;
    private ScheduledFuture<?> wakeUp;

    Rescheduler(Runnable r,
                Executor serializingExecutor,
                ScheduledExecutorService scheduler,
                Stopwatch stopwatch) {
        this.runnable = r;
        this.serializingExecutor = serializingExecutor;
        this.scheduler = scheduler;
        this.stopwatch = stopwatch;
        stopwatch.start();
    }

    /**
     * 重新调度任务，并延迟执行
     *
     * @param delay    延迟时间
     * @param timeUnit 延迟时间单位
     */
    /* must be called from the {@link #serializingExecutor} originally passed in. */
    void reschedule(long delay, TimeUnit timeUnit) {
        long delayNanos = timeUnit.toNanos(delay);
        long newRunAtNanos = nanoTime() + delayNanos;
        enabled = true;
        // 如果还没有到执行时间或者在执行中
        if (newRunAtNanos - runAtNanos < 0 || wakeUp == null) {
            // 如果在执行中，则取消
            if (wakeUp != null) {
                wakeUp.cancel(false);
            }
            // 重新提交任务
            wakeUp = scheduler.schedule(new FutureRunnable(), delayNanos, TimeUnit.NANOSECONDS);
        }
        // 更新执行时间
        runAtNanos = newRunAtNanos;
    }

    /**
     * 取消执行任务
     *
     * @param permanent
     */
    // must be called from channel executor
    void cancel(boolean permanent) {
        enabled = false;
        if (permanent && wakeUp != null) {
            wakeUp.cancel(false);
            wakeUp = null;
        }
    }

    private final class FutureRunnable implements Runnable {
        @Override
        public void run() {
            Rescheduler.this.serializingExecutor.execute(new ChannelFutureRunnable());
        }

        private boolean isEnabled() {
            return Rescheduler.this.enabled;
        }
    }

    private final class ChannelFutureRunnable implements Runnable {

        @Override
        public void run() {
            // 如果没有重新调度，则不执行
            if (!enabled) {
                wakeUp = null;
                return;
            }
            // 如果还没有到执行时间，则重新调度执行时间
            long now = nanoTime();
            if (runAtNanos - now > 0) {
                wakeUp = scheduler.schedule(
                        new FutureRunnable(), runAtNanos - now, TimeUnit.NANOSECONDS);
            } else {
                // 如果到了执行时间则开始执行
                enabled = false;
                wakeUp = null;
                runnable.run();
            }
        }
    }

    @VisibleForTesting
    static boolean isEnabled(Runnable r) {
        return ((FutureRunnable) r).isEnabled();
    }

    private long nanoTime() {
        return stopwatch.elapsed(TimeUnit.NANOSECONDS);
    }
}
