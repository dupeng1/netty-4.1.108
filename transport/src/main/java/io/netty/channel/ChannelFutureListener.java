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


/**
 * Listens to the result of a {@link ChannelFuture}.  The result of the
 * asynchronous {@link Channel} I/O operation is notified once this listener
 * is added by calling {@link ChannelFuture#addListener(GenericFutureListener)}.
 *
 * <h3>Return the control to the caller quickly</h3>
 *
 * {@link #operationComplete(Future)} is directly called by an I/O
 * thread.  Therefore, performing a time consuming task or a blocking operation
 * in the handler method can cause an unexpected pause during I/O.  If you need
 * to perform a blocking operation on I/O completion, try to execute the
 * operation in a different thread using a thread pool.
 */
// 与ChannelGroupFutureListener接口一样仅仅是一个监听器的别名，针对ChannelFuture做的约束监听器
public interface ChannelFutureListener extends GenericFutureListener<ChannelFuture> {

    /**
     * A {@link ChannelFutureListener} that closes the {@link Channel} which is
     * associated with the specified {@link ChannelFuture}.
     */
    // 内部定义的默认实现CLOSE 当ChannelFuture关闭的时候如果添加了此监听器则会将管道一并关闭
    ChannelFutureListener CLOSE = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) {
            future.channel().close();
        }
    };

    /**
     * A {@link ChannelFutureListener} that closes the {@link Channel} when the
     * operation ended up with a failure or cancellation rather than a success.
     */
    // 当future设置失败的时候触发CLOSE_ON_FAILURE监听器会将管道关闭
    ChannelFutureListener CLOSE_ON_FAILURE = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) {
            if (!future.isSuccess()) {
                future.channel().close();
            }
        }
    };

    /**
     * A {@link ChannelFutureListener} that forwards the {@link Throwable} of the {@link ChannelFuture} into the
     * {@link ChannelPipeline}. This mimics the old behavior of Netty 3.
     */
    // CLOSE_ON_FAILURE是这是失败则关闭，而FIRE_EXCEPTION_ON_FAILURE设置失败则传播异常具体在讲述pipeline的时候会进行讲解
    ChannelFutureListener FIRE_EXCEPTION_ON_FAILURE = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) {
            if (!future.isSuccess()) {
                future.channel().pipeline().fireExceptionCaught(future.cause());
            }
        }
    };

    // Just a type alias
}
