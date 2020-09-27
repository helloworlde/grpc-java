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

package io.grpc.internal;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.HashSet;

/**
 * Aggregates the in-use state of a set of objects.
 * in-use 状态的对象集合
 */
@NotThreadSafe
public abstract class InUseStateAggregator<T> {

    private final HashSet<T> inUseObjects = new HashSet<>();

    /**
     * Update the in-use state of an object. Initially no object is in use.
     * 更新对象的 in-use 状态，初始时没有对象是 in-use 状态
     *
     * <p>This may call into {@link #handleInUse} or {@link #handleNotInUse} when appropriate.
     * 会根据状态适时调用 handleInUse 和 handleNotInUse 方法
     */
    public final void updateObjectInUse(T object, boolean inUse) {
        int origSize = inUseObjects.size();
        // 如果是使用状态，则将其添加到集合中
        if (inUse) {
            inUseObjects.add(object);
            // 如果是第一个加入的，则调用 handleInUse
            if (origSize == 0) {
                handleInUse();
            }
        } else {
            // 如果不是使用状态，则从集合中移除
            boolean removed = inUseObjects.remove(object);
            // 如果是最后一个，则调用 handleNotInUse
            if (removed && origSize == 1) {
                handleNotInUse();
            }
        }
    }

    public final boolean isInUse() {
        return !inUseObjects.isEmpty();
    }

    /**
     * Called when the aggregated in-use state has changed to true, which means at least one object is
     * in use.
     * 当对象 in-use 状态变为 true 时调用，意味着至少有一个对象在使用中
     */
    protected abstract void handleInUse();

    /**
     * Called when the aggregated in-use state has changed to false, which means no object is in use.
     * 当对象的 in-use 状态变为 false 时调用，意味着没有对象在使用
     */
    protected abstract void handleNotInUse();
}
