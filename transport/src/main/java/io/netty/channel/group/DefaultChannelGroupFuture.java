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
package io.netty.channel.group;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.BlockingOperationException;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.internal.ObjectUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * The default {@link ChannelGroupFuture} implementation.
 */
// 默认管道组的操作管理，此类是提供给ChannelGroup接口使用的，
// 因为ChannelGroup是批量管理自然也需要有个能够批量管理的Future而此类就是这个Future
final class DefaultChannelGroupFuture extends DefaultPromise<Void> implements ChannelGroupFuture {
    // 管理的操作集合是哪个ChannelGroup的
    private final ChannelGroup group;
    // 当前组中的结果管理的所有管道和管道处理
    private final Map<Channel, ChannelFuture> futures;
    // 本组任务执行成功个数和失败个数
    private int successCount;
    private int failureCount;
    // 默认监听器的实现，用于记录执行结果，在futures中的每个任务执行完成都会操作此监听器用于记录操作结果，比如成功数和失败数与失败结果的封装
    private final ChannelFutureListener childListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            // 获取当前任务的执行结果
            boolean success = future.isSuccess();
            // 声明是否是最后一个处理结果
            boolean callSetDone;
            // 使用了DefaultChannelGroupFuture为锁是为了确保上方的计数变量的准确性
            synchronized (DefaultChannelGroupFuture.this) {
                // 如果是成功则成功计数加一否则失败计数加一
                if (success) {
                    successCount ++;
                } else {
                    failureCount ++;
                }
                // 如果成功数和失败数的和等于总处理数则返回true否则返回false
                callSetDone = successCount + failureCount == futures.size();
                // 断言他的结果必然是小于等于处理任务，如果出现大于那么逻辑肯定是有问题的需要抛出断言异常，当然前提是运行时开启了断言
                assert successCount + failureCount <= futures.size();
            }
            // 如果当前处理是最后一个结果
            if (callSetDone) {
                // 判断当前失败的个数是否大于0
                if (failureCount > 0) {
                    // 创建一个集合用于存储执行结果失败的管道与异常信息，设置个数为失败个数
                    List<Map.Entry<Channel, Throwable>> failed =
                            new ArrayList<Map.Entry<Channel, Throwable>>(failureCount);
                    // 遍历所有的任务
                    for (ChannelFuture f: futures.values()) {
                        // 判断当前的任务是否失败
                        if (!f.isSuccess()) {
                            // 如果失败则给集合添加错误的管道对于的错误信息
                            failed.add(new DefaultEntry<Channel, Throwable>(f.channel(), f.cause()));
                        }
                    }
                    // 给当前的任务设置为失败并且传入的异常是个自定义异常，此异常用于存储具体的错误数据信息作为返回值
                    setFailure0(new ChannelGroupException(failed));
                } else {
                    // 如果小于0则代表没有失败那么设置当前的管理是成功状态
                    setSuccess0();
                }
            }
        }
    };

    /**
     * Creates a new instance.
     */
    // 构造器，所属的ChannelGroup、futures代表当前任务组需要处理的任务们，executor任务执行器
    DefaultChannelGroupFuture(ChannelGroup group, Collection<ChannelFuture> futures,  EventExecutor executor) {
        super(executor);
        this.group = ObjectUtil.checkNotNull(group, "group");
        ObjectUtil.checkNotNull(futures, "futures");
        // 创建一个future集合 key是管道 value是对于的管道任务
        Map<Channel, ChannelFuture> futureMap = new LinkedHashMap<Channel, ChannelFuture>();
        // 将传入的list任务 动态添加到futureMap中
        for (ChannelFuture f: futures) {
            futureMap.put(f.channel(), f);
        }
        // 因为此任务集合是不允许修改的所以此处转换为了不允许修改的map，此map如果调用put remove等修改方法则会抛出异常
        this.futures = Collections.unmodifiableMap(futureMap);
        // 给管理的所有任务添加任务完成的监听器，传入的任务只要完成就会进入上方的完成监听器从而达到计数的效果
        for (ChannelFuture f: this.futures.values()) {
            f.addListener(childListener);
        }
        // 如果传入的任务是空则直接完成当前任务，这里可能有些绕 因为是Group他是对多个任务的管理，但是当前类也是一个任务只不过是用于管理其他任务集合的任务罢了
        // Done on arrival?
        if (this.futures.isEmpty()) {
            setSuccess0();
        }
    }
    // 此构造器参考上方构造器并没有特殊之处只是减少了将list转map的操作
    DefaultChannelGroupFuture(ChannelGroup group, Map<Channel, ChannelFuture> futures, EventExecutor executor) {
        super(executor);
        this.group = group;
        this.futures = Collections.unmodifiableMap(futures);
        for (ChannelFuture f: this.futures.values()) {
            f.addListener(childListener);
        }

        // Done on arrival?
        if (this.futures.isEmpty()) {
            setSuccess0();
        }
    }

    @Override
    public ChannelGroup group() {
        return group;
    }

    // 根据管道查找任务，就是使用的上方futures的get方法
    @Override
    public ChannelFuture find(Channel channel) {
        return futures.get(channel);
    }

    // 迭代器则是获取的futures的迭代器
    @Override
    public Iterator<ChannelFuture> iterator() {
        return futures.values().iterator();
    }

    // 是否部分成功
    @Override
    public synchronized boolean isPartialSuccess() {
        //successCount != 0 代表总有一个是成功的
        //successCount != futures.size() 不是所有的都成功的
        return successCount != 0 && successCount != futures.size();
    }

    // 是否部分失败
    // 参考isPartialSuccess内部解释
    @Override
    public synchronized boolean isPartialFailure() {
        return failureCount != 0 && failureCount != futures.size();
    }

    // 下面的方法都是使用父级的方法此处不做讲解，有疑问可以看父级实现
    @Override
    public DefaultChannelGroupFuture addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        super.addListener(listener);
        return this;
    }

    @Override
    public DefaultChannelGroupFuture addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        super.addListeners(listeners);
        return this;
    }

    @Override
    public DefaultChannelGroupFuture removeListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        super.removeListener(listener);
        return this;
    }

    @Override
    public DefaultChannelGroupFuture removeListeners(
            GenericFutureListener<? extends Future<? super Void>>... listeners) {
        super.removeListeners(listeners);
        return this;
    }

    @Override
    public DefaultChannelGroupFuture await() throws InterruptedException {
        super.await();
        return this;
    }

    @Override
    public DefaultChannelGroupFuture awaitUninterruptibly() {
        super.awaitUninterruptibly();
        return this;
    }

    @Override
    public DefaultChannelGroupFuture syncUninterruptibly() {
        super.syncUninterruptibly();
        return this;
    }

    @Override
    public DefaultChannelGroupFuture sync() throws InterruptedException {
        super.sync();
        return this;
    }

    @Override
    public ChannelGroupException cause() {
        return (ChannelGroupException) super.cause();
    }

    private void setSuccess0() {
        super.setSuccess(null);
    }

    private void setFailure0(ChannelGroupException cause) {
        super.setFailure(cause);
    }

    @Override
    public DefaultChannelGroupFuture setSuccess(Void result) {
        throw new IllegalStateException();
    }

    @Override
    public boolean trySuccess(Void result) {
        throw new IllegalStateException();
    }

    @Override
    public DefaultChannelGroupFuture setFailure(Throwable cause) {
        throw new IllegalStateException();
    }

    @Override
    public boolean tryFailure(Throwable cause) {
        throw new IllegalStateException();
    }

    @Override
    protected void checkDeadLock() {
        EventExecutor e = executor();
        if (e != null && e != ImmediateEventExecutor.INSTANCE && e.inEventLoop()) {
            throw new BlockingOperationException();
        }
    }

    private static final class DefaultEntry<K, V> implements Map.Entry<K, V> {
        private final K key;
        private final V value;

        DefaultEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K getKey() {
            return key;
        }

        @Override
        public V getValue() {
            return value;
        }

        @Override
        public V setValue(V value) {
            throw new UnsupportedOperationException("read-only");
        }
    }
}
