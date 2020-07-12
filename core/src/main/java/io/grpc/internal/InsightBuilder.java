/*
 * Copyright 2019 The gRPC Authors
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

import javax.annotation.Nullable;
import java.util.ArrayList;

/**
 * Builds a concise and readable string that gives insight of the concerned part of the system.  The
 * resulted string is made up of a list of short strings, each of which gives out a piece of
 * information.
 * <p>
 * 构建简洁易读的字符串，用于洞悉系统状态，结果由多个短字符串组成，每段给出信息
 */
public final class InsightBuilder {
    private final ArrayList<String> buffer = new ArrayList<>();

    /**
     * Appends a piece of information which is a plain string.  The given object is immediately
     * converted to string and recorded.
     * 追加字符串信息，被立即转为 string 并记录
     */
    public InsightBuilder append(@Nullable Object insight) {
        buffer.add(String.valueOf(insight));
        return this;
    }

    /**
     * Appends a piece of information which is a key-value , which will be formatted into {@code
     * "key=value"}.  Value's {@code toString()} or {@code null} is immediately recorded.
     * 追加 key-value 信息，格式化为 key=value 格式，调用 value 的 toString() 或者 变为null，然后记录
     */
    public InsightBuilder appendKeyValue(String key, @Nullable Object value) {
        buffer.add(key + "=" + value);
        return this;
    }

    /**
     * Get the resulting string.
     */
    @Override
    public String toString() {
        return buffer.toString();
    }
}
