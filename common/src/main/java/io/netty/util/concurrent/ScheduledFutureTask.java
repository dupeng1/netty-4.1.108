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

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.PriorityQueueNode;

import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

// 延迟任务管理，不仅支持延迟执行也可以根据周期一直运行。
@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {
    // set once when added to priority queue
    // 下一次任务的执行id，
    private long id;

    private long deadlineNanos;
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    private final long periodNanos;

    private int queueIndex = INDEX_NOT_IN_QUEUE;

    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Runnable runnable, long nanoTime) {

        super(executor, runnable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Runnable runnable, long nanoTime, long period) {

        super(executor, runnable);
        deadlineNanos = nanoTime;
        periodNanos = validatePeriod(period);
    }

    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = validatePeriod(period);
    }

    ScheduledFutureTask(AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    private static long validatePeriod(long period) {
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        return period;
    }

    ScheduledFutureTask<V> setId(long id) {
        if (this.id == 0L) {
            this.id = id;
        }
        return this;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }
    // 获取最后一次的执行时间，此方法一般用于循环任务
    public long deadlineNanos() {
        return deadlineNanos;
    }

    void setConsumed() {
        // Optimization to avoid checking system clock again
        // after deadline has passed and task has been dequeued
        if (periodNanos == 0) {
            assert scheduledExecutor().getCurrentTimeNanos() >= deadlineNanos;
            deadlineNanos = 0L;
        }
    }

    public long delayNanos() {
        return delayNanos(scheduledExecutor().getCurrentTimeNanos());
    }

    static long deadlineToDelayNanos(long currentTimeNanos, long deadlineNanos) {
        return deadlineNanos == 0L ? 0L : Math.max(0L, deadlineNanos - currentTimeNanos);
    }

    public long delayNanos(long currentTimeNanos) {
        return deadlineToDelayNanos(currentTimeNanos, deadlineNanos);
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    // 之前在说Delayed接口的时候，此结构继承过Comparable接口，所以整个方法实现的Comparable接口的方法
    // 此方法是比较两个ScheduledFutureTask的周期任务是下次执行的时长，
    // 因为既然是在队列中那么每次弹出的任务都会是头部的，所以是为了将先执行的任务排到队列头使用。
    // 此函数具体的返回值需要根据使用出做出判定此处不做解释
    @Override
    public int compareTo(Delayed o) {
        //如果两个对象比较相等则返回0
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        // 当前的执行时间减去传入的执行时间，获取的就是他们的差数
        long d = deadlineNanos() - that.deadlineNanos();
        // 如果小于0 则代表当前的时间执行早于传入的时间则返回-1
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            // 如果大于0则代表当前任务晚于传入的时间则返回1
            return 1;
        } else if (id < that.id) {
            // 如果他俩下一个周期时间相等则代表d是0，则判断他当前的id是否小于传入的id，如果小则代表当前任务优先于传入的任务则返回-1
            return -1;
        } else {
            // 如果两个id相等则抛出异常
            assert id != that.id;
            // 否则传入的任务优先于当前的任务，此处结论是根据调用出总结。
            return 1;
        }
    }

    // 最终的运行run方法
    @Override
    public void run() {
        // 如果当前线程不是传入的执行器线程则会抛出断言异常当然如果运行时没有开启断言关键字那么次代码无效
        assert executor().inEventLoop();
        try {
            if (delayNanos() > 0L) {
                // Not yet expired, need to add or remove from queue
                if (isCancelled()) {
                    scheduledExecutor().scheduledTaskQueue().removeTyped(this);
                } else {
                    scheduledExecutor().scheduleFromEventLoop(this);
                }
                return;
            }
            // 检查是否周期为0之前说过如果是0则不进行循环
            if (periodNanos == 0) {
                // 与父级的使用相同设置为状态为正在运运行
                if (setUncancellableInternal()) {
                    // 执行任务
                    V result = runTask();
                    // 设置为成功
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                // 检查当前的任务是否被取消了
                if (!isCancelled()) {
                    // 如果没有则调用call，因为能进入这里都是循环执行的任务所以没有返回值
                    runTask();
                    // 并且判断当前的执行器是否已经关闭
                    if (!executor().isShutdown()) {
                        // 如果当前周期大于0则代表当前时间添加周期时间
                        // 这里需要注意当前时间包括了不包括执行时间
                        // 这样说可能有点绕，这样理解这里的p是本次执行是在开始的准时间，什么是准时间？就是无视任务的执行时间以周期时间和执行开始时间计算。
                        // scheduleAtFixedRate方法的算法，通过下面的deadlineNanos+=p也是可以看出的。
                        if (periodNanos > 0) {
                            deadlineNanos += periodNanos;
                        } else {
                            // 此处小于0 则就需要将当前程序的运行时间也要算进去所以使用了当前时间加周期，p因为小于0所以负负得正了
                            deadlineNanos = scheduledExecutor().getCurrentTimeNanos() - periodNanos;
                        }
                        // 如果还没有取消当前任务
                        if (!isCancelled()) {
                            // 获取任务队列并且将当前的任务在丢进去，因为已经计算完下一次执行的时间了所以当前任务已经是一个新的任务，最起码执行时间改变了
                            scheduledExecutor().scheduledTaskQueue().add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            // 如果出现异常则设置为失败
            setFailureInternal(cause);
        }
    }

    private AbstractScheduledEventExecutor scheduledExecutor() {
        return (AbstractScheduledEventExecutor) executor();
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    // 取消当前任务所以需要从任务队列中移除当前任务
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean canceled = super.cancel(mayInterruptIfRunning);
        if (canceled) {
            scheduledExecutor().removeScheduled(this);
        }
        return canceled;
    }

    // 取消不删除则直接调用父级方法不做任务的删除，
    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }

    @Override
    public int priorityQueueIndex(DefaultPriorityQueue<?> queue) {
        return queueIndex;
    }

    @Override
    public void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i) {
        queueIndex = i;
    }
}
