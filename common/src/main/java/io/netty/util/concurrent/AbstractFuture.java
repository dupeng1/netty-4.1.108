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

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Abstract {@link Future} implementation which does not allow for cancellation.
 *
 * @param <V>
 */
public abstract class AbstractFuture<V> implements Future<V> {

    // 对Future的实现获取执行结果
    @Override
    public V get() throws InterruptedException, ExecutionException {
        // 等待执行结果，此方法会进行阻塞，等待执行结束为止或者被中断，如果被中断则抛出异常。
        await();
        // 当执行结束获取当前的执行过程中的异常，可以看出await等待如果执行内部出错是不会抛出异常的。
        Throwable cause = cause();
        // 如果执行过程中没有异常，则获取执行的结果getNow非阻塞方法。
        if (cause == null) {
            return getNow();
        }
        // 否则则判断是否被取消，如果是被取消则后续可以做处理，毕竟是人为的。
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        // 如果不是被取消那么就代表是执行逻辑出错了，那么则封装异常并且抛出异常。
        throw new ExecutionException(cause);
    }

    // 也是获取执行结果只不过设置了超时时间
    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        // 等待一定时间的执行如果超时则返回false否则是true
        if (await(timeout, unit)) {
            // 能进来说明并未超时，则获取执行过程中的异常，以下的处理参考上方的get方法。
            Throwable cause = cause();
            if (cause == null) {
                return getNow();
            }
            if (cause instanceof CancellationException) {
                throw (CancellationException) cause;
            }
            throw new ExecutionException(cause);
        }
        // 到这一步则代表即使超时了也并没有完成任务则抛出TimeOut异常
        throw new TimeoutException();
    }
}
