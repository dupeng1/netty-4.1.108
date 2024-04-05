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
 * The {@link CompleteFuture} which is succeeded already.  It is
 * recommended to use {@link EventExecutor#newSucceededFuture(Object)} instead of
 * calling the constructor of this future.
 */
// 完成成功结果类，继承与CompleteFuture，此类的所有方法都是符合完成标记的，
// 比如：isDone为true、isCancellable为false、isCancelled为false等父级的方法的固定返回值。
public final class SucceededFuture<V> extends CompleteFuture<V> {
    // 定义了一个结果对象，用来存储任务的执行返回值
    private final V result;

    /**
     * Creates a new instance.
     *
     * @param executor the {@link EventExecutor} associated with this future
     */
    // 传入了执行器，父级使用的，传入结果
    public SucceededFuture(EventExecutor executor, V result) {
        super(executor);
        this.result = result;
    }

    // 在父类中并没有去实现这个方法，而AbstractFuture实现get的时候就有此方法的调用，所以此类非常有必要实现
    @Override
    public Throwable cause() {
        return null;
    }

    // 设置是执行成功的
    @Override
    public boolean isSuccess() {
        return true;
    }

    // 返回创建时的结果
    @Override
    public V getNow() {
        return result;
    }
}
