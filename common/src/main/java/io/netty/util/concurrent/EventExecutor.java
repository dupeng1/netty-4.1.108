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

/**
 * The {@link EventExecutor} is a special {@link EventExecutorGroup} which comes
 * with some handy methods to see if a {@link Thread} is executed in a event loop.
 * Besides this, it also extends the {@link EventExecutorGroup} to allow for a generic
 * way to access methods.
 *
 */
// 此【事件执行器】继承与EventExecutorGroup【事件执行器组】。
// 在EventExecutorGroup接口中可以看到有对EventExecutor的依赖，EventExecutorGroup的迭代就是EventExecutor。
public interface EventExecutor extends EventExecutorGroup {

    /**
     * Returns a reference to itself.
     */
    // 在group中我们讲解了next它采用了executor的选择器来返回next，
    // 而此处的next是对group的重写代表的意义则是返回本身executor也就是this。
    @Override
    EventExecutor next();

    /**
     * Return the {@link EventExecutorGroup} which is the parent of this {@link EventExecutor},
     */
    // 在创建executor的时候会将group传入到executor中而此方法就是为了方便获取当前group所提供的
    // 返回EventExecutor的管理者
    EventExecutorGroup parent();

    /**
     * Calls {@link #inEventLoop(Thread)} with {@link Thread#currentThread()} as argument
     */
    // 判断【当前线程】是否是【执行器】的【执行线程】，一般是调用下面的方法传入当前的线程
    boolean inEventLoop();

    /**
     * Return {@code true} if the given {@link Thread} is executed in the event loop,
     * {@code false} otherwise.
     */
    // 判断【传入一个线程】是否是【执行器】的【执行线程】
    boolean inEventLoop(Thread thread);

    /**
     * Return a new {@link Promise}.
     */
    // 返回一个【新的应答】，这里的Promise是Future的实现
    <V> Promise<V> newPromise();

    /**
     * Create a new {@link ProgressivePromise}.
     */
    // 返回一个【新的应答】
    <V> ProgressivePromise<V> newProgressivePromise();

    /**
     * Create a new {@link Future} which is marked as succeeded already. So {@link Future#isSuccess()}
     * will return {@code true}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    // 返回一个SucceededFuture对象，并且他的isSuccess方法为true，使用get获取结果是不会阻塞会返回传入的result。
    <V> Future<V> newSucceededFuture(V result);

    /**
     * Create a new {@link Future} which is marked as failed already. So {@link Future#isSuccess()}
     * will return {@code false}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    // 传入一个抛出的异常描述，返回一个FailedFuture对象，与上方恰好相反，isSuccess为false，并且get的时候抛出异常。
    <V> Future<V> newFailedFuture(Throwable cause);
}
