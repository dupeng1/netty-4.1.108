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

import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ThreadFactory} implementation with a simple naming rule.
 */
//默认的线程工厂
public class DefaultThreadFactory implements ThreadFactory {
    // 用于设置线程名称前缀，其意义为这个线程工厂的序列号
    private static final AtomicInteger poolId = new AtomicInteger();
    // 用于设置线程名称后缀，每生成一个线程就加1
    private final AtomicInteger nextId = new AtomicInteger();
    // 线程的名称前缀
    private final String prefix;
    //是否是守护线程
    private final boolean daemon;
    // 默认设置的线程优先级
    private final int priority;
    // 生产的线程所属的线程组
    protected final ThreadGroup threadGroup;

    // 下面是线程工厂的构造这里统一说明一下
    // poolType 是Class类型他最终会被转换成类名用于poolName的使用
    // poolName 线程名但是不是完整的他会拼接一些其他数据比如poolId
    // daemon 是否为守护线程除非手动设置否则默认都是false
    // priority 线程的优先级 默认是NORM_PRIORITY 也是系统默认的
    public DefaultThreadFactory(Class<?> poolType) {
        this(poolType, false, Thread.NORM_PRIORITY);
    }

    public DefaultThreadFactory(String poolName) {
        this(poolName, false, Thread.NORM_PRIORITY);
    }

    public DefaultThreadFactory(Class<?> poolType, boolean daemon) {
        this(poolType, daemon, Thread.NORM_PRIORITY);
    }

    public DefaultThreadFactory(String poolName, boolean daemon) {
        this(poolName, daemon, Thread.NORM_PRIORITY);
    }

    public DefaultThreadFactory(Class<?> poolType, int priority) {
        this(poolType, false, priority);
    }

    public DefaultThreadFactory(String poolName, int priority) {
        this(poolName, false, priority);
    }

    public DefaultThreadFactory(Class<?> poolType, boolean daemon, int priority) {
        this(toPoolName(poolType), daemon, priority);
    }

    //此方法是将Class类型的poolType获取类名作用于线程名
    public static String toPoolName(Class<?> poolType) {
        ObjectUtil.checkNotNull(poolType, "poolType");
        // 根据Class获取类名
        String poolName = StringUtil.simpleClassName(poolType);
        switch (poolName.length()) {
            case 0:
                return "unknown";
            case 1:
                return poolName.toLowerCase(Locale.US);
            default:
                if (Character.isUpperCase(poolName.charAt(0)) && Character.isLowerCase(poolName.charAt(1))) {
                    return Character.toLowerCase(poolName.charAt(0)) + poolName.substring(1);
                } else {
                    return poolName;
                }
        }
    }

    /**
     * DefaultThreadFactory默认将生产的线程的名称前缀设为类名+"-"+poolId.incrementAndGet()+"-"，例如"NioEventLoopGroup-1-"，
     * 线程优先级默认为5，
     * 并将其设置为守护线程
     * @param poolName
     * @param daemon
     * @param priority
     * @param threadGroup
     */
    public DefaultThreadFactory(String poolName, boolean daemon, int priority, ThreadGroup threadGroup) {
        ObjectUtil.checkNotNull(poolName, "poolName");

        if (priority < Thread.MIN_PRIORITY || priority > Thread.MAX_PRIORITY) {
            throw new IllegalArgumentException(
                    "priority: " + priority + " (expected: Thread.MIN_PRIORITY <= priority <= Thread.MAX_PRIORITY)");
        }

        prefix = poolName + '-' + poolId.incrementAndGet() + '-';
        this.daemon = daemon;
        this.priority = priority;
        this.threadGroup = threadGroup;
    }

    public DefaultThreadFactory(String poolName, boolean daemon, int priority) {
        this(poolName, daemon, priority, null);
    }

    // 创建线程
    @Override
    public Thread newThread(Runnable r) {
        //将其包装为FastThreadLocalRunnable，并调用newThread方法构造线程
        Thread t = newThread(FastThreadLocalRunnable.wrap(r), prefix + nextId.incrementAndGet());
        try {
            // 如果创建的线程与工厂不一致，比如工厂设置的守护线程工厂daemon是true，那么创建的线程是false将会进行设置
            if (t.isDaemon() != daemon) {//设置是否为守护线程
                t.setDaemon(daemon);
            }
            // 优先级与上方一样
            if (t.getPriority() != priority) {//设置线程优先级
                t.setPriority(priority);
            }
        } catch (Exception ignored) {
            // Doesn't matter even if failed to set.
        }
        return t;
    }

    // DefaultThreadFactory生产的线程实例都属于FastThreadLocalThread类，FastThreadLocalThread继承了Thread类
    protected Thread newThread(Runnable r, String name) {
        return new FastThreadLocalThread(threadGroup, r, name);
    }
}
