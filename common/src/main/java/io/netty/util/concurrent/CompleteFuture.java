/*
 * Copyright 2013 The Netty Project
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

import java.util.concurrent.TimeUnit;

/**
 * A skeletal {@link Future} implementation which represents a {@link Future} which has been completed already.
 */
// 一个完成的Future，这个CompleteFuture是一个结果父级，此类大多方法都是拥有默认值的，具体的差异在子类重写。
public abstract class CompleteFuture<V> extends AbstractFuture<V> {

    // 定义一个事件的执行器，可能会有人疑惑为什么这里要执行器，上面讲述过Future是一个异步任务，
    // 既然是异步自然就是多线程，而Future并没有线程的管理都是对任务的管理，所以需要单独的一个执行器来进行执行这个任务从而产生关联
    private final EventExecutor executor;

    /**
     * Creates a new instance.
     *
     * @param executor the {@link EventExecutor} associated with this future
     */
    // 初始化事件执行器
    protected CompleteFuture(EventExecutor executor) {
        this.executor = executor;
    }

    /**
     * Return the {@link EventExecutor} which is used by this {@link CompleteFuture}.
     */
    // 获取事件执行器
    protected EventExecutor executor() {
        return executor;
    }

    // 添加监听器
    @Override
    public Future<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        // 判断传入的监听器是否为空如果是则抛出异常
        DefaultPromise.notifyListener(executor(), this, ObjectUtil.checkNotNull(listener, "listener"));
        return this;
    }

    // 批量添加监听器
    @Override
    public Future<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        for (GenericFutureListener<? extends Future<? super V>> l:
                ObjectUtil.checkNotNull(listeners, "listeners")) {

            if (l == null) {
                break;
            }
            DefaultPromise.notifyListener(executor(), this, l);
        }
        return this;
    }

    // 这里并没有实现删除监听器，或者说这个抽象类不允许删除监听器
    @Override
    public Future<V> removeListener(GenericFutureListener<? extends Future<? super V>> listener) {
        // NOOP
        return this;
    }

    @Override
    public Future<V> removeListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        // NOOP
        return this;
    }

    @Override
    public Future<V> await() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return this;
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return true;
    }

    @Override
    public Future<V> sync() throws InterruptedException {
        return this;
    }

    @Override
    public Future<V> syncUninterruptibly() {
        return this;
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        return true;
    }

    @Override
    public Future<V> awaitUninterruptibly() {
        return this;
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        return true;
    }

    @Override
    public boolean isDone() {
        return true;
    }

    @Override
    public boolean isCancellable() {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }
}
