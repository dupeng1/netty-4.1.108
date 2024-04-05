/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.PlatformDependent;

/**
 * The {@link CompleteFuture} which is failed already.  It is
 * recommended to use {@link EventExecutor#newFailedFuture(Throwable)}
 * instead of calling the constructor of this future.
 */
// 完成失败结果类，继承与CompleteFuture，此类的所有方法都是符合完成标记的，
// 比如：isDone为true、isCancellable为false、isCancelled为false等父级的方法的固定返回值。
public final class FailedFuture<V> extends CompleteFuture<V> {
    // 失败了必然是有异常的，此类为定义的异常类
    private final Throwable cause;

    /**
     * Creates a new instance.
     *
     * @param executor the {@link EventExecutor} associated with this future
     * @param cause   the cause of failure
     */
    // 传入执行器与失败的异常，并且判断了如果异常时null则抛出NullPointer异常
    public FailedFuture(EventExecutor executor, Throwable cause) {
        super(executor);
        this.cause = ObjectUtil.checkNotNull(cause, "cause");
    }

    // 此方法在AbstractFuture实现get的时候就有此方法的调用
    @Override
    public Throwable cause() {
        return cause;
    }

    // 都是失败了自然是false
    @Override
    public boolean isSuccess() {
        return false;
    }

    // 美女不能吧从从从x'c's
    @Override
    public Future<V> sync() {
        PlatformDependent.throwException(cause);
        return this;
    }

    // 同步获取结果则抛出创建时传入的异常与上方方法相同，两个方法获取的差异看前方的定义。
    @Override
    public Future<V> syncUninterruptibly() {
        PlatformDependent.throwException(cause);
        return this;
    }

    // 失败自然没有结果则返回null
    @Override
    public V getNow() {
        return null;
    }
}
