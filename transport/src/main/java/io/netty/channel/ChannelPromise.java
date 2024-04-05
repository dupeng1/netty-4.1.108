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

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

/**
 * Special {@link ChannelFuture} which is writable.
 */

/**
 * 可以【修改】当前【异步结果】的【状态】，并且在【修改】【状态】是会触发【监听器】
 */

//此接口继承了两个接口ChannelFuture与Promise具体讲解请看上文。
//前面介绍Promise的时候说过他是一个特殊的Future他可以手动设置成功，
// 设置成功需要一个结果值而这里的定义则是结果值是一个Void无效的类型可以看出只要是实现当前的Future则都没有返回值即使有返回值也是Void。

public interface ChannelPromise extends ChannelFuture, Promise<Void> {
    // 这里再一次重新定义了ChannelFuture的channel方法，这个重写毫无意义。。估计是为了以后修改，修改方式则是将Channel改成他的子类实现，暂时不管看当前接口到时候的实现即可
    @Override
    Channel channel();
    // 这里仅仅是对父类的一个重写，此重写是为了返回this为ChannelPromise的对象，这样方便调用ChannelPromise中声明的方法
    @Override
    ChannelPromise setSuccess(Void result);
    // 既然是无效值说明不设置也可以所以这里将上方的传参去掉了
    ChannelPromise setSuccess();
    // 尝试成功也是一样，去除了无效的结果值
    boolean trySuccess();

    @Override
    ChannelPromise setFailure(Throwable cause);

    @Override
    ChannelPromise addListener(GenericFutureListener<? extends Future<? super Void>> listener);

    @Override
    ChannelPromise addListeners(GenericFutureListener<? extends Future<? super Void>>... listeners);

    @Override
    ChannelPromise removeListener(GenericFutureListener<? extends Future<? super Void>> listener);

    @Override
    ChannelPromise removeListeners(GenericFutureListener<? extends Future<? super Void>>... listeners);

    @Override
    ChannelPromise sync() throws InterruptedException;

    @Override
    ChannelPromise syncUninterruptibly();

    @Override
    ChannelPromise await() throws InterruptedException;

    @Override
    ChannelPromise awaitUninterruptibly();

    /**
     * Returns a new {@link ChannelPromise} if {@link #isVoid()} returns {@code true} otherwise itself.
     */
    // 如果isVoid是true则返回全新的ChannelPromise，否则返回this
    ChannelPromise unvoid();
}
