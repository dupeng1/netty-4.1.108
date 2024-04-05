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
package io.netty.channel;

import io.netty.util.concurrent.CompleteFuture;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.internal.ObjectUtil;

/**
 * A skeletal {@link ChannelFuture} implementation which represents a
 * {@link ChannelFuture} which has been completed already.
 */
// 与CompleteFuture类一样也是定义结果的，只不过这里是针对管道操作进行的定义，而CompleteFuture是针对所有任务定义的，
// 此类是在CompleteFuture的基础上提出关于管道的任务做了更细致的定义。并且这个类实现了前面讲述的ChannelFuture接口
abstract class CompleteChannelFuture extends CompleteFuture<Void> implements ChannelFuture {
    // 既然是关于管道的自然需要相关联
    private final Channel channel;

    /**
     * Creates a new instance.
     *
     * @param channel the {@link Channel} associated with this future
     */
    // 既然是继承于CompleteFuture自然需要一个执行器，判断传入的管道不能为null
    protected CompleteChannelFuture(Channel channel, EventExecutor executor) {
        super(executor);
        this.channel = ObjectUtil.checkNotNull(channel, "channel");
    }

    //获取执行器
    @Override
    protected EventExecutor executor() {
        EventExecutor e = super.executor();
        if (e == null) {
            // 如果创建结果时并没有传入执行器则返回管道的执行器
            return channel().eventLoop();
        } else {
            return e;
        }
    }

    @Override
    public ChannelFuture addListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        super.addListener(listener);
        return this;
    }

    @Override
    public ChannelFuture addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        super.addListeners(listeners);
        return this;
    }

    @Override
    public ChannelFuture removeListener(GenericFutureListener<? extends Future<? super Void>> listener) {
        super.removeListener(listener);
        return this;
    }

    @Override
    public ChannelFuture removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners) {
        super.removeListeners(listeners);
        return this;
    }

    @Override
    public ChannelFuture syncUninterruptibly() {
        return this;
    }

    @Override
    public ChannelFuture sync() throws InterruptedException {
        return this;
    }

    @Override
    public ChannelFuture await() throws InterruptedException {
        return this;
    }

    @Override
    public ChannelFuture awaitUninterruptibly() {
        return this;
    }

    // 返回传入的管道
    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public Void getNow() {
        return null;
    }

    @Override
    public boolean isVoid() {
        return false;
    }
}
