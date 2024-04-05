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

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
// 默认的Promise实现，并没有好解释的这个名字，只不过从名字可以看出他会有实现或者说别的实现
public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
    // netty内部自己对日志打印的实现，当然这也算是一个模块了
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromise.class);
    // 打印异常的日志对象
    private static final InternalLogger rejectedExecutionLogger =
            InternalLoggerFactory.getInstance(DefaultPromise.class.getName() + ".rejectedExecution");
    // 获取最大的栈的深度，暂时知道有这么个东西即可
    // SystemPropertyUtil是netty的自带的配置类，可以在启动的时候进行配置，他最终使用的是System.getProperty方法。
    private static final int MAX_LISTENER_STACK_DEPTH = Math.min(8,
            SystemPropertyUtil.getInt("io.netty.defaultPromise.maxListenerStackDepth", 8));
    // 原子更新操作，这里可以理解为在多线程下操作是线程安全的
    // AtomicReferenceFieldUpdater.newUpdater使用了这个方法传入了三个参数
    // 1、DefaultPromise.class需要原子操作的类型
    // 2、Object.class需要原子操作类中的字段类型
    // 3、result 需要原子操作字段的字段名
    // 两个泛型则是两个对于的类型T、V。具体感兴趣的读者可以去看看他的源码
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result");
    // 操作成功，用于result的设置
    private static final Object SUCCESS = new Object();
    // 不可取消，用于result的设置具体看接下来的使用
    private static final Object UNCANCELLABLE = new Object();
    // 存储取消的原因，用于result的设置
    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(
            StacklessCancellationException.newInstance(DefaultPromise.class, "cancel(...)"));
    private static final StackTraceElement[] CANCELLATION_STACK = CANCELLATION_CAUSE_HOLDER.cause.getStackTrace();
    // 任务的执行结果
    private volatile Object result;
    //前面说了future是异步使用的来操作任务的所以需要执行器，因为执行器是多线程的。
    private final EventExecutor executor;
    /**
     * One or more listeners. Can be a {@link GenericFutureListener} or a {@link DefaultFutureListeners}.
     * If {@code null}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     *
     * Threading - synchronized(this). We must support adding listeners when there is no EventExecutor.
     */
    private GenericFutureListener<? extends Future<?>> listener;
    // 需要通知的监听器，如果为null则会有两种情况1、没有监听器2、监听器已经通知完毕
    private DefaultFutureListeners listeners;
    /**
     * Threading - synchronized(this). We are required to hold the monitor to use Java's underlying wait()/notifyAll().
     */
    // 计数，在当前类中有地方使用了Object的wait和notifyAll用来计数wait的次数
    private short waiters;

    /**
     * Threading - synchronized(this). We must prevent concurrent notification and FIFO listener notification if the
     * executor changes.
     */
    // 避免出现并发通知，true已经有线程进行通知了，false没有线程发送通知
    private boolean notifyingListeners;

    /**
     * Creates a new instance.
     *
     * It is preferable to use {@link EventExecutor#newPromise()} to create a new promise
     *
     * @param executor
     *        the {@link EventExecutor} which is used to notify the promise once it is complete.
     *        It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     *        The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     *        depth exceeds a threshold.
     *
     */
    // 构造器，传入执行器，并进行了校验如果执行器是null则会抛出nullpoint异常
    public DefaultPromise(EventExecutor executor) {
        this.executor = checkNotNull(executor, "executor");
    }

    /**
     * See {@link #executor()} for expectations of the executor.
     */
    // 无参构造，如果子类的实现没有使用到执行器那么可以调用无参构造，因为executor是final的所以必须初始化这里默认给了null
    protected DefaultPromise() {
        // only for subclasses
        executor = null;
    }

    // 前面说过Primise是个特殊的Future，可以进行手动设置执行成功
    @Override
    public Promise<V> setSuccess(V result) {
        // 设置结果，如果返回true代表设置成功则调用通知，否则代表已经完成了并且抛出异常
        if (setSuccess0(result)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    // 和上方方法并没有不同，仅仅是如果设置成功失败则返回false，而上方设置失败则抛出异常
    @Override
    public boolean trySuccess(V result) {
        return setSuccess0(result);
    }

    // 设置当前的任务为失败并且传入一个异常信息，返回true则通知监听器，否则抛出异常
    @Override
    public Promise<V> setFailure(Throwable cause) {
        if (setFailure0(cause)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this, cause);
    }

    // 尝试设置当前任务为失败并且传入一个异常信息，返回true则尝试成功并且通知监听器，否则返回false
    @Override
    public boolean tryFailure(Throwable cause) {
        return setFailure0(cause);
    }

    // 设置当前任务为不可取消
    @Override
    public boolean setUncancellable() {
        // 在上方说原子操作的时候RESULT_UPDATER字段是用来设置结果的。
        // 这里便使用它来操作设置当前的result为UNCANCELLABLE对象，
        // 第一参数传入需要操作的对象，第二参数传入预计当前的值，第三个参数传入需要设置的对象。
        if (RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            // 设置成功则返回true说明当前的任务状态已经是不可取消状态了
            return true;
        }
        Object result = this.result;
        // 否则获取当前的结果并且判断是成功了还是被取消了，两者一者满足即可。
        // 1、要么成功2、要么被取消
        return !isDone0(result) || !isCancelled0(result);
    }

    // 当前的任务是否执行完成
    @Override
    public boolean isSuccess() {
        Object result = this.result;
        // result不等于null是必须的，因为初始值就是null，说明并没有进行任何状态的设置
        // result不等于UNCANCELLABLE，代表是不可取消状态，但是他是未完成的，因为最终的result并不会是他，从而代表正在运行并且在运行途中还设置了不可取消状态
        //result不是CauseHolder类型，之前在定义失败异常的时候就是使用这个类的对象创建的标记，从而代表结束运行但是是被取消的所以不能算是完成
        return result != null && result != UNCANCELLABLE && !(result instanceof CauseHolder);
    }

    // 是否取消
    @Override
    public boolean isCancellable() {
        return result == null;
    }

    private static final class LeanCancellationException extends CancellationException {
        private static final long serialVersionUID = 2794674970981187807L;

        // Suppress a warning since the method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {
            setStackTrace(CANCELLATION_STACK);
            return this;
        }

        @Override
        public String toString() {
            return CancellationException.class.getName();
        }
    }

    // 获取执行异常
    @Override
    public Throwable cause() {
        return cause0(result);
    }

    private Throwable cause0(Object result) {
        if (!(result instanceof CauseHolder)) {
            return null;
        }
        if (result == CANCELLATION_CAUSE_HOLDER) {
            CancellationException ce = new LeanCancellationException();
            if (RESULT_UPDATER.compareAndSet(this, CANCELLATION_CAUSE_HOLDER, new CauseHolder(ce))) {
                return ce;
            }
            result = this.result;
        }
        // 如果当前result是CauseHolder，则代表存在异常，则将result转为CauseHolder并且调用cause属性返回否则返回null
        return ((CauseHolder) result).cause;
    }

    // 添加监听器
    @Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");
        // 锁住当前对象
        synchronized (this) {
            // 添加监听器
            addListener0(listener);
        }
        // 是否完成了当前的任务，如果完成则进行通知
        if (isDone()) {
            notifyListeners();
        }
        // 最后返回当前对象
        return this;
    }

    // 添加多个监听器
    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");
        // 锁住当前对象
        synchronized (this) {
            // 遍历当前传入的监听器如果是null则跳出循环。
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                addListener0(listener);
            }
        }
        // 如果任务执行成功则直接进行通知
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }
    // 删除监听器
    @Override
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");
        // 锁住当前对象
        synchronized (this) {
            // 进行监听器的删除
            removeListener0(listener);
        }

        return this;
    }

    // 同上，只不过监听器是多个并且进行的监听器的遍历去删除
    @Override
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                removeListener0(listener);
            }
        }

        return this;
    }

    @Override
    public Promise<V> await() throws InterruptedException {
        // 如果当前的任务已经执行完则返回this
        if (isDone()) {
            return this;
        }
        // 定义的时候说过await如果发生了中断则会抛出异常，这里判断当前线程是否中断，如果中断则抛出异常
        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }
        // 检查是否死锁
        checkDeadLock();
        //当 前线程锁住当前的代码块，其他线程不可访问
        synchronized (this) {
            // 是否成功，如果并没有成功则进入该while，如果成功则返回this
            while (!isDone()) {
                // 之前说过waiters字段用来记录等待的线程，此处是对waiters字段进行+1操作
                incWaiters();
                try {
                    // 当前对象进行等待
                    wait();
                } finally {
                    // 等待结束或者被唤醒则进行-1操作
                    decWaiters();
                }
            }
        }
        return this;
    }

    // 与上方方法解释相同只不过如果被中断了不会抛出异常，而是尝试中断当前的线程。
    @Override
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        checkDeadLock();

        boolean interrupted = false;
        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return this;
    }

    // await加强版，支持设置等到时长，这里讲传入的时长转换为了纳秒
    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return await0(unit.toNanos(timeout), true);
    }

    // 传入的毫秒转为纳秒
    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(MILLISECONDS.toNanos(timeoutMillis), true);
    }

    // 与上方方法相同只不过将抛出的中断异常转为了内部错误，在定义的时候就有说过此方法不会抛出中断异常
    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    // 与上方方法相同
    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    // 获取当前结果非阻塞，如果当前值是异常或者是SUCCESS或者UNCANCELLABLE则返回null，否则返回当前值
    @SuppressWarnings("unchecked")
    @Override
    public V getNow() {
        Object result = this.result;
        if (result instanceof CauseHolder || result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        return (V) result;
    }

    @SuppressWarnings("unchecked")
    @Override
    public V get() throws InterruptedException, ExecutionException {
        Object result = this.result;
        if (!isDone0(result)) {
            await();
            result = this.result;
        }
        if (result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        Throwable cause = cause0(result);
        if (cause == null) {
            return (V) result;
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    @SuppressWarnings("unchecked")
    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Object result = this.result;
        if (!isDone0(result)) {
            if (!await(timeout, unit)) {
                throw new TimeoutException();
            }
            result = this.result;
        }
        if (result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        Throwable cause = cause0(result);
        if (cause == null) {
            return (V) result;
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    // 取消当前任务执行，并且尝试中断，但是当前方法并没有尝试中断所以传参则无用。
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        // 设置当前result的值为CANCELLATION_CAUSE_HOLDER，取消异常
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
            // 设置成功则检查并唤醒之前wait中等待的线程
            if (checkNotifyWaiters()) {
                // 通知所有的监听器
                notifyListeners();
            }
            return true;
        }
        // 取消失败则返回false说明当前result已经被设置成其他的结果
        return false;
    }

    // 是否取消
    @Override
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    // 是否成功
    @Override
    public boolean isDone() {
        return isDone0(result);
    }

    // 同步等待调用了之前wait方法。如果失败则尝试抛出异常
    @Override
    public Promise<V> sync() throws InterruptedException {
        await();
        rethrowIfFailed();
        return this;
    }

    // 与上方方法一样只不过这里不会抛出中断异常
    @Override
    public Promise<V> syncUninterruptibly() {
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    // 打印当前任务的状态
    @Override
    public String toString() {
        return toStringBuilder().toString();
    }

    // 封装当前任务的状态
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = new StringBuilder(64)
                .append(StringUtil.simpleClassName(this))
                .append('@')
                .append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            buf.append("(success)");
        } else if (result == UNCANCELLABLE) {
            buf.append("(uncancellable)");
        } else if (result instanceof CauseHolder) {
            buf.append("(failure: ")
                    .append(((CauseHolder) result).cause)
                    .append(')');
        } else if (result != null) {
            buf.append("(success: ")
                    .append(result)
                    .append(')');
        } else {
            buf.append("(incomplete)");
        }

        return buf;
    }

    /**
     * Get the executor used to notify listeners when this promise is complete.
     * <p>
     * It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     * The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     * depth exceeds a threshold.
     * @return The executor used to notify listeners when this promise is complete.
     */
    // 获取传入的执行器
    protected EventExecutor executor() {
        return executor;
    }

    // 之前用到的检查死锁方法，就是检查当前调用方法的线程是不是执行器的线程，如果是则说明发生了死锁，需要抛出异常，停止死锁操作
    // 获取执行器，如果执行器为null则不会发生死锁，
    // 如果不是null则判断当前线程是否是执行器线程，inEventLoop此方法的定义在之前有讲解过
    protected void checkDeadLock() {
        EventExecutor e = executor();
        if (e != null && e.inEventLoop()) {
            throw new BlockingOperationException(toString());
        }
    }

    /**
     * Notify a listener that a future has completed.
     * <p>
     * This method has a fixed depth of {@link #MAX_LISTENER_STACK_DEPTH} that will limit recursion to prevent
     * {@link StackOverflowError} and will stop notifying listeners added after this threshold is exceeded.
     * @param eventExecutor the executor to use to notify the listener {@code listener}.
     * @param future the future that is complete.
     * @param listener the listener to notify.
     */
    // 通知所有的监听器
    // eventExecutor 通知监听器的执行器
    // future 需要通知的任务
    // listener 需要通知的监听器
    protected static void notifyListener(
            EventExecutor eventExecutor, final Future<?> future, final GenericFutureListener<?> listener) {
        // 下面三个方法的调用用于判断传入的三个参数是否为null，如果是则抛出nullpoint异常
        notifyListenerWithStackOverFlowProtection(
                checkNotNull(eventExecutor, "eventExecutor"),
                checkNotNull(future, "future"),
                checkNotNull(listener, "listener"));
    }

    // 通知监听器
    private void notifyListeners() {
        // 获取当前任务的的执行器
        EventExecutor executor = executor();
        // 如果调用这个方法的线程就是执行器的线程则进入该if
        if (executor.inEventLoop()) {
            // 获取当前线程的InternalThreadLocalMap对象
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            // 通过线程的数据对象或去到当前的任务监听器通知的层次，如果是第一次通知则为0
            final int stackDepth = threadLocals.futureListenerStackDepth();
            // 当前的线程数据中的层次与我们设置的最大层次相比，如果当前层次小于设置的最大层则进入if
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                // 进入后再层次中+1
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    // 立即通知
                    notifyListenersNow();
                } finally {
                    // 如果通知完成则还原深度，可以理解为又进行了减一
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }
        // 如果当前线程不是执行器或者当前的线程深度已经大于了设置的最大深度，则使用当前的执行器进行通知
        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListenersNow();
            }
        });
    }

    /**
     * The logic in this method should be identical to {@link #notifyListeners()} but
     * cannot share code because the listener(s) cannot be cached for an instance of {@link DefaultPromise} since the
     * listener(s) may be changed and is protected by a synchronized operation.
     */
    // 此方法和上方的方法相同，但是上方的通知是使用当前任务的监听器而此处使用的是传入的监听器，
    // 可能监听器会发生改变所以没有使用当前任务的字段做缓存，因为做了缓存上方代码是可以复用的。
    private static void notifyListenerWithStackOverFlowProtection(final EventExecutor executor,
                                                                  final Future<?> future,
                                                                  final GenericFutureListener<?> listener) {
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    // 此处与上方有差异，上方调用notifyListenersNow
                    notifyListener0(future, listener);
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListener0(future, listener);
            }
        });
    }

    private void notifyListenersNow() {
        GenericFutureListener listener;
        // 创建了方法内部的局部变量
        DefaultFutureListeners listeners;
        // 使用this作为线程锁，并且上锁
        synchronized (this) {
            listener = this.listener;
            listeners = this.listeners;
            // Only proceed if there are listeners to notify and we are not already notifying listeners.
            // 如果当前任务并没有通知并且是有监听器的则进行接下来的逻辑，否则return。
            if (notifyingListeners || (listener == null && listeners == null)) {
                return;
            }
            // 通知只能通知一次，既然当前线程已经到这里了那么接下来的线程就在上一个if停止就是了(当然代表当前线程已经释放了这个this锁)，
            // 因为这里设置了通知状态为true，代表正在通知
            notifyingListeners = true;
            if (listener != null) {
                this.listener = null;
            } else {
                this.listeners = null;
            }
        }
        // 循环调用进行通知
        for (;;) {
            // 这里对监听器做了两个处理
            // 第一个是当前监听器是一个列表代表多个监听器
            // 第二个则代表当前监听器是一个监听器，
            // 不一样的数据结构对应不一样的处理。
            if (listener != null) {
                notifyListener0(this, listener);
            } else {
                notifyListeners0(listeners);
            }
            // 通知完成后继续上锁
            synchronized (this) {
                // 如果当前的监听器已经重置为null则设置正在通知的状态结束，否则设置当前的局部变量为当前的监听器然后设置当前监听器为null
                if (this.listener == null && this.listeners == null) {
                    // Nothing can throw from within this method, so setting notifyingListeners back to false does not
                    // need to be in a finally block.
                    notifyingListeners = false;
                    return;
                }
                listener = this.listener;
                listeners = this.listeners;
                if (listener != null) {
                    this.listener = null;
                } else {
                    this.listeners = null;
                }
            }
        }
        //这里对此方法进行一个小结: 这里使用了两个地方用锁而且他们的锁是一样的所以会出现竞争问题，
        // 如果第一个线程进来并且设置为正在发送通知那么剩下的线程都不会再继续执行并且当前的监听器是null的
        // 如果通过别的途径再次添加了监听器并且当前的通知还是正在通知的状态那么其他的线程还是进不来，
        // 但是当前的线程执行完通知会发现当前的监听器又发生了变化，那么这个for的死循环再次执行，
        // 因为发现又有新的通知所以当前还是正在发送通知状态，所以其他线程还是进不来，最终还是由当前线程进行执行。
        // 而在讲述notifyListenerWithStackOverFlowProtection的时候说过监听器发生改变所以不能复用的问题，
        // 而这里就处理如果当前的监听器发送改变的处理。
    }

    // 这里进行通知数组类型的监听器
    private void notifyListeners0(DefaultFutureListeners listeners) {
        // 首先获取到传入监听器内部包含的数组
        GenericFutureListener<?>[] a = listeners.listeners();
        // 然后进行遍历通知遍历中的监听器
        // 而且要注意此方法是私有的那么就代表除了使用它可以进行遍历以外其他的继承只能一个一个发同通知，具体的看实现逻辑
        int size = listeners.size();
        for (int i = 0; i < size; i ++) {
            notifyListener0(this, a[i]);
        }
    }

    // 上方遍历调用的也是他，而传入多个参数的也是他，最终发送消息的也是他，
    // 此方法比较强，但是非常简单就是调用了监听器的操作完成方法并且传入对于的任务数据。
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyListener0(Future future, GenericFutureListener l) {
        try {
            l.operationComplete(future);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
            }
        }
    }

    // 此处添加监听器
    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        // 如果是null则说明这是第一个监听器那么直接将其赋值给当前的全局变量
        if (this.listener == null) {
            if (listeners == null) {
                this.listener = listener;
            } else {
                // 否则说明不是第一个监听器那么就判断是不是数组类型的监听器如果是则add加进去就行了
                listeners.add(listener);
            }
        } else {
            assert listeners == null;
            listeners = new DefaultFutureListeners(this.listener, listener);
            this.listener = null;
        }
    }

    // 删除监听器，非常简单如果是数组类型那么直接从数组中移除如果不是数组类型那么就置为null
    private void removeListener0(GenericFutureListener<? extends Future<? super V>> toRemove) {
        if (listener == toRemove) {
            listener = null;
        } else if (listeners != null) {
            listeners.remove(toRemove);
            // Removal is rare, no need for compaction
            if (listeners.size() == 0) {
                listeners = null;
            }
        }
    }

    // 设置当前任务的结果为成功，如果传入的结果是是null则设置为SUCCESS，否则设置为传入的result
    private boolean setSuccess0(V result) {
        return setValue0(result == null ? SUCCESS : result);
    }
    // 设置当前任务结果为失败，传入一个异常信息
    private boolean setFailure0(Throwable cause) {
        return setValue0(new CauseHolder(checkNotNull(cause, "cause")));
    }
    //设置值的方法
    private boolean setValue0(Object objResult) {
        // 如果当前结果是null则修改为传入的结果
        // 如果当前结果是UNCANCELLABLE不可取消状态则设置传入的结果
        // 两者有一个成功则进行通知检查，这里的通知不是监听器的通知，而是对前面wait等待线程的通知并且返回true
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
            if (checkNotifyWaiters()) {
                notifyListeners();
            }
            return true;
        }
        return false;
    }

    /**
     * Check if there are any waiters and if so notify these.
     * @return {@code true} if there are any listeners attached to the promise, {@code false} otherwise.
     */
    // 检查并且通知唤醒，如果等待的线程大于0则进行全部唤醒
    private synchronized boolean checkNotifyWaiters() {
        if (waiters > 0) {
            notifyAll();
        }
        return listener != null || listeners != null;
    }

    // 当有线程等待时进行加一
    private void incWaiters() {
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        ++waiters;
    }

    // 线程被唤醒的时候则减一
    private void decWaiters() {
        --waiters;
    }

    // 抛出失败异常，之前在同步等待结果的时候使用过，当得到结果后调用此方法判断是否有异常，如果有则抛出否则什么都不做。
    private void rethrowIfFailed() {
        Throwable cause = cause();
        if (cause == null) {
            return;
        }

        PlatformDependent.throwException(cause);
    }

    // 之前调用过的等待方法，传入两个参数第一个等待的纳秒时间，第二个是否中断抛出异常
    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        // 如果执行成功那么直接返回等待结果为true
        if (isDone()) {
            return true;
        }
        // 否则判断当前传入的时间是否小于等于0 如果是则返回当前执行结果是否为成功
        if (timeoutNanos <= 0) {
            return isDone();
        }
        // 判断是否允许抛出中断异常，并且判断当前线程是否被中断如果两者都成立则抛出中断异常
        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }
        // 检查是否为死锁，内部实现请查看上方的方法解释
        checkDeadLock();

        // Start counting time from here instead of the first line of this method,
        // to avoid/postpone performance cost of System.nanoTime().
        // 获取当前的纳秒时间
        final long startTime = System.nanoTime();
        synchronized (this) {
            // 是否中断
            boolean interrupted = false;
            try {
                // 用户设置的等待时间
                long waitTime = timeoutNanos;
                while (!isDone() && waitTime > 0) {
                    // 等待线程数+1
                    incWaiters();
                    try {
                        // 使用wait进行等待，因为wait传入参数是毫秒而这里是纳秒所以这里做了处理
                        // 1、获取纳秒数中的毫秒传入第一个参数
                        // 2、获取剩余的那纳秒数作为第二个参数
                        // wait 第一个参数是毫秒数 第二个参数是纳秒数，
                        // 看起来比较精准其实jdk只是发现有纳秒数后对毫秒数进行了+1 具体读者可以去看wait源码
                        wait(waitTime / 1000000, (int) (waitTime % 1000000));
                    } catch (InterruptedException e) {
                        // 如果出现中断异常那么判断传入的第二个参数是否抛出异常如果为true此处则抛出异常否则修改前面声明的变量为true
                        if (interruptable) {
                            throw e;
                        } else {
                            interrupted = true;
                        }
                    } finally {
                        // 不管最终如何都会对waiters进行-1操作
                        decWaiters();
                    }
                    // Check isDone() in advance, try to avoid calculating the elapsed time later.
                    // 能到这里说明已经被唤醒则判断是否执行成功，执行成功则返回true
                    if (isDone()) {
                        return true;
                    }
                    // Calculate the elapsed time here instead of in the while condition,
                    // try to avoid performance cost of System.nanoTime() in the first loop of while.
                    // 否则判断当前睡眠时间是否超过设置时间如果超过则返回当前的执行结果，否则继续循环
                    waitTime = timeoutNanos - (System.nanoTime() - startTime);
                }
                return isDone();
            } finally {
                // 当跳出循环后判断在等待过程中是否发生了中断异常如果发生则将当前线程进行中断
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Notify all progressive listeners.
     * <p>
     * No attempt is made to ensure notification order if multiple calls are made to this method before
     * the original invocation completes.
     * <p>
     * This will do an iteration over all listeners to get all of type {@link GenericProgressiveFutureListener}s.
     * @param progress the new progress.
     * @param total the total progress.
     */
    // 通知进度监听器，就是监听的进度条可以这么理解，第一个参数是当前的进度，第二个参数是总的进度
    @SuppressWarnings("unchecked")
    void notifyProgressiveListeners(final long progress, final long total) {
        // 从当前的监听器中获取到进度监听器，如果没有则return否则继续执行
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }
        // 对应进度监听器的自然是进度的任务管理所以会将当前的this转为进度管理器self
        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;
        // 获取通知处理器并且判断当前的线程是否是内部的处理器。
        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            // 如果是则判断是否是数组监听器
            // 如果是则调用notifyProgressiveListeners0进行通知
            // 否则调用notifyProgressiveListener0进行通知
            // 他俩的区别就在于监听器是否是数组
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                notifyProgressiveListeners0(
                        self, (GenericProgressiveFutureListener<?>[]) listeners, progress, total);
            } else {
                notifyProgressiveListener0(
                        self, (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners, progress, total);
            }
        } else {
            // 如果当前的线程不是内部的处理器线程那么走这里
            // 判断当前的监听器是否是数组监听器
            // 如果是则创建一个Runnable内部还是调用的notifyProgressiveListeners0方法只不过这里将通知的方法当做一个执行器中的任务丢给他叫他去执行
            // 这里和上方的区别就在于如果是是当前线程则直接执行否则使用执行器执行
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                final GenericProgressiveFutureListener<?>[] array =
                        (GenericProgressiveFutureListener<?>[]) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListeners0(self, array, progress, total);
                    }
                });
            } else {
                // 如果不是线程则直接提交一个任务给当前的执行器执行调用方法是notifyProgressiveListener0
                final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                        (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListener0(self, l, progress, total);
                    }
                });
            }
        }
    }

    /**
     * Returns a {@link GenericProgressiveFutureListener}, an array of {@link GenericProgressiveFutureListener}, or
     * {@code null}.
     */
    // 获取进度监听器列表，因为任务中只有一个字段存储监听器所以需要从该字段中进行筛选，此方法就是对这个字段进行类的筛选
    private synchronized Object progressiveListeners() {
        final GenericFutureListener listener = this.listener;
        // 获取当前任务的监听器，这里之所以使用一个临时变量进行接收是害怕其他线程如果修改了监听器那么下面的处理会出现未知异常，
        // 所以为了保证不出错此处将监听器做了处理。
        final DefaultFutureListeners listeners = this.listeners;
        if (listener == null && listeners == null) {
            // No listeners added
            // 如果等null那就说明没有监听器则退出方法
            return null;
        }
        // 判断监听器的类型是否为数组
        if (listeners != null) {
            // Copy DefaultFutureListeners into an array of listeners.
            // 如果是数组类型则将其转换为数组类型
            DefaultFutureListeners dfl = listeners;
            // 并且获取进度监听器在数组中的存在数量
            int progressiveSize = dfl.progressiveSize();
            // 数组等于0则返回null如果等于1则遍历它里面的监听器是否是进度监听器，如果是则返回否则返回null
            // 这里算是一个优化点但是case 1的时候并不是优化因为没有必要去遍历了 直接下表取值就是了。
            switch (progressiveSize) {
                case 0:
                    return null;
                case 1:
                    for (GenericFutureListener<?> l: dfl.listeners()) {
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }
            // 如果大于1那么获取数组列表
            GenericFutureListener<?>[] array = dfl.listeners();
            // 并且创建一个进度监听器数组列表
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            // 遍历前面获取监听的个数动态比较当前下标的监听器是否是进度监听器并且给上面创建的数组赋值
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }
            // 将遍历结果返回
            return copy;
        } else if (listener instanceof GenericProgressiveFutureListener) {
            // 如果不是数组类型并且类型是进度监听器则直接返回当前任务的监听器
            return listener;
        } else {
            // Only one listener was added and it's not a progressive listener.
            // 上面过滤的大多情况但是还有一个情况那就是如果只有一个监听器并且不是进度监听器这种情况走这里
            return null;
        }
    }

    // 通知进度监听器参数 传入监听器数组 当前的进度 总进度
    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    // 具体进度监听器的调用就是个方法的调用看过前面讲解的读者应该能看懂这里再不做解释，后续会有专门一节介绍netty的监听器
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
            }
        }
    }

    // 此方法是私有的静态方法所以获取不到当前任务的结果所以需要调用者传入
    private static boolean isCancelled0(Object result) {
        // 如果结果类型是CauseHolder并且结果还是取消异常那么则返回true
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }

    // 也是静态私有的判断是否执行完成
    private static boolean isDone0(Object result) {
        // 传入结果不等于null 并且 不是不能取消（因为不能取消则说明正在运行，而不管是SUCCESS 还 CANCELLATION_CAUSE_HOLDER 都是已经有确切结果的）
        return result != null && result != UNCANCELLABLE;
    }

    // 前面一直使用的异常存储的的类很简单就一个异常类存储的字段，而在之前也有很多比较都是根据这个字段进行的
    private static final class CauseHolder {
        final Throwable cause;
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    // 使用传入的执行器进行execute方法的调用
    private static void safeExecute(EventExecutor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }

    private static final class StacklessCancellationException extends CancellationException {

        private static final long serialVersionUID = -2974906711413716191L;

        private StacklessCancellationException() { }

        // Override fillInStackTrace() so we not populate the backtrace via a native call and so leak the
        // Classloader.
        @Override
        public Throwable fillInStackTrace() {
            return this;
        }

        static StacklessCancellationException newInstance(Class<?> clazz, String method) {
            return ThrowableUtil.unknownStackTrace(new StacklessCancellationException(), clazz, method);
        }
    }
}
