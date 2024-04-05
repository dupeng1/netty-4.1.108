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

import static io.netty.util.internal.ObjectUtil.checkPositive;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {
    // 执行器数组，EventExecutor是group管理的执行器，在next方法返回的此执行器，final修饰说明数组长度是固定
    private final EventExecutor[] children;
    // 此set是对上方的执行器数组的一个副本，并且这个副本只读。
    private final Set<EventExecutor> readonlyChildren;
    // 中断执行器的数量，如果group被中断则会遍历调用children的中断方法，而每个children被中断都会进行一个计数
    // 而terminatedChildren则是对中断children的计数
    private final AtomicInteger terminatedChildren = new AtomicInteger();
    // 中断执行的返回结果，因为需要关闭一个执行组所以为了异步执行所以返回了一个应答，然后根据用于调用去决定是等待获取结果
    // 还是去设置一个结果事件
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);
    // 执行器选择器，什么是选择器呢，因为是线程组那么在来任务的时候将会选择使用哪个执行器去执行这个任务
    // 而此选择器则用到了，之前我们看到的定义next方法其实他的实现就是使用了这个选择器去返回执行器
    private final EventExecutorChooserFactory.EventExecutorChooser chooser;

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     *                          之前说过children是限制长度的而此参数就是用来设置此线程池的线程数大小
     * @param threadFactory     the ThreadFactory to use, or {@code null} if the default should be used.
     *                          线程的创建工厂，用于创建线程
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     *                          在创建执行器的时候传入固定参数
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        // 这里有个小逻辑如果传入的线程工厂不是null则把工厂包装给一个executor。如果默认传null则会用默认的线程工厂
        this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    // 除了传入线程工厂还有一个做法就是传入一个executor，上一个构造就是对此构造的封装
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
        // 传入了默认的执行器的选择器
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads          the number of threads that will be used by this instance.
     * @param executor          the Executor to use, or {@code null} if the default should be used.
     * @param chooserFactory    the {@link EventExecutorChooserFactory} to use.
     * @param args              arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    // 最终操作的构造器，扩展了执行器的选择器
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory, Object... args) {
        // 如果传入的线程数小于0则抛出异常
        checkPositive(nThreads, "nThreads");
        // 如果传入的执行器是空的则采用默认的线程工厂和默认的执行器
        if (executor == null) {
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
        }
        // 创建指定线程数的执行器数组
        children = new EventExecutor[nThreads];
        // 遍历创建执行器
        for (int i = 0; i < nThreads; i ++) {
            // 是否创建成功默认是false
            boolean success = false;
            try {
                // 使用了newChild方法创建执行器并且传入了executor和设置参数args
                children[i] = newChild(executor, args);
                // 未报异常则设置true
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                // 创建失败则抛出异常
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                // 如果success是false则代表创建失败，则将已经创建好的执行器进行关闭
                if (!success) {
                    // 关闭数组中所有已经构造好的NioEventLoop
                    for (int j = 0; j < i; j ++) {
                        children[j].shutdownGracefully();
                    }
                    // 同步等待这些NioEventLoop关闭
                    for (int j = 0; j < i; j ++) {
                        EventExecutor e = children[j];
                        try {
                            // 判断当前的执行器是否终止了如果没有则等待获取结果
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            // Let the caller handle the interruption.
                            // 抛出异常则中断当前线程
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }
        // 获取执行器的选择器
        // 1、EventExecutorChooser用于MultithreadEventExecutorGroup的next方法实现
        // 2、当提交一个Runnable任务时，就会调用next方法从children数组中取出一个EventExecutor(NioEventLoop)，
        // 3、并将这个任务交给这个EventExecutor所属的线程执行
        chooser = chooserFactory.newChooser(children);
        //创建一个future的监听器用于监听终止结果
        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                // 当此【执行器数组】中的【执行器】被关闭的时候回调用此方法进入这里，这里进行终止数加一然后比较是否已经达到了【执行器】的总数
                // 如果没有则跳过，如果有则设置当前执行器的终止future为success为null
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };
        // 添加监听器，在NioEventLoop被关闭时触发，当监听器触发则会进入上方的内部类实现
        for (EventExecutor e: children) {
            e.terminationFuture().addListener(terminationListener);
        }
        // 创建一个children的镜像set
        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        // 拷贝这个set
        Collections.addAll(childrenSet, children);
        // 并且设置此set内的所有数据不允许修改然后返回设置给readonlyChildren
        readonlyChildren = Collections.unmodifiableSet(childrenSet);
    }

    // 如果没有指定线程工厂(ThreadFactory)，那么默认调用newDefaultThreadFactory方法来构造一个线程工厂
    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass());
    }

    // next则是使用了选择器的next方法
    @Override
    public EventExecutor next() {
        return chooser.next();
    }

    // 迭代执行器的时候调用的是只读的set
    @Override
    public Iterator<EventExecutor> iterator() {
        return readonlyChildren.iterator();
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    // 获取当前执行器的数量
    public final int executorCount() {
        return children.length;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     *
     */
    // 声明了一个创建执行器的方法并且抽象的，因为每个执行器的实现都有特殊的操作所以此处抽象
    protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;

    // 调用【线程组】的关闭其实就是遍历【执行器】集合的关闭方法因为之前加了监听器去处理返回结果，所以此处返回的future用于监听是否执行结束了
    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l: children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    // 获取终止结果
    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    // 与关闭相同，只不过此方法无返回值，并且调用的方法不同，是执行器的shutdown
    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l: children) {
            l.shutdown();
        }
    }

    //这里要注意只有所有的执行器都是关闭中状态才会是true
    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l: children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    // 上方是关闭中，这里是关闭
    @Override
    public boolean isShutdown() {
        for (EventExecutor l: children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    // 是否终止
    @Override
    public boolean isTerminated() {
        for (EventExecutor l: children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    // 等待时间范围是否执行终止完成
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        //计算截至时间
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        // 遍历执行器
        loop: for (EventExecutor l: children) {
            // 死循环以便使用截至时间
            for (;;) {
                // 如果当前的时间是大于截至时间则会小于等于0
                long timeLeft = deadline - System.nanoTime();
                // 如果小于等于0则跳出loop就是最外层循环，不在循环
                if (timeLeft <= 0) {
                    break loop;
                }
                // 否则当前的线程等待计算的时间如果在时间内终止则跳出循环再次遍历下一个执行器然后计算时间再次重复操作
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        // 如果到了时间则获取当前的终止结果
        return isTerminated();
    }
}
