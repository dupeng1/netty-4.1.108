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
package io.netty.channel.group;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.ServerChannel;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.Set;

/**
 * A thread-safe {@link Set} that contains open {@link Channel}s and provides
 * various bulk operations on them.  Using {@link ChannelGroup}, you can
 * categorize {@link Channel}s into a meaningful group (e.g. on a per-service
 * or per-state basis.)  A closed {@link Channel} is automatically removed from
 * the collection, so that you don't need to worry about the life cycle of the
 * added {@link Channel}.  A {@link Channel} can belong to more than one
 * {@link ChannelGroup}.
 *
 * <h3>Broadcast a message to multiple {@link Channel}s</h3>
 * <p>
 * If you need to broadcast a message to more than one {@link Channel}, you can
 * add the {@link Channel}s associated with the recipients and call {@link ChannelGroup#write(Object)}:
 * <pre>
 * <strong>{@link ChannelGroup} recipients =
 *         new {@link DefaultChannelGroup}({@link GlobalEventExecutor}.INSTANCE);</strong>
 * recipients.add(channelA);
 * recipients.add(channelB);
 * ..
 * <strong>recipients.write({@link Unpooled}.copiedBuffer(
 *         "Service will shut down for maintenance in 5 minutes.",
 *         {@link CharsetUtil}.UTF_8));</strong>
 * </pre>
 *
 * <h3>Simplify shutdown process with {@link ChannelGroup}</h3>
 * <p>
 * If both {@link ServerChannel}s and non-{@link ServerChannel}s exist in the
 * same {@link ChannelGroup}, any requested I/O operations on the group are
 * performed for the {@link ServerChannel}s first and then for the others.
 * <p>
 * This rule is very useful when you shut down a server in one shot:
 *
 * <pre>
 * <strong>{@link ChannelGroup} allChannels =
 *         new {@link DefaultChannelGroup}({@link GlobalEventExecutor}.INSTANCE);</strong>
 *
 * public static void main(String[] args) throws Exception {
 *     {@link ServerBootstrap} b = new {@link ServerBootstrap}(..);
 *     ...
 *     b.childHandler(new MyHandler());
 *
 *     // Start the server
 *     b.getPipeline().addLast("handler", new MyHandler());
 *     {@link Channel} serverChannel = b.bind(..).sync();
 *     <strong>allChannels.add(serverChannel);</strong>
 *
 *     ... Wait until the shutdown signal reception ...
 *
 *     // Close the serverChannel and then all accepted connections.
 *     <strong>allChannels.close().awaitUninterruptibly();</strong>
 * }
 *
 * public class MyHandler extends {@link ChannelInboundHandlerAdapter} {
 *     {@code @Override}
 *     public void channelActive({@link ChannelHandlerContext} ctx) {
 *         // closed on shutdown.
 *         <strong>allChannels.add(ctx.channel());</strong>
 *         super.channelActive(ctx);
 *     }
 * }
 * </pre>
 */
// 此接口是对Channel对象做的分组，分了组每一组都是一个ChannelGroup而他可以进行批量管理
public interface ChannelGroup extends Set<Channel>, Comparable<ChannelGroup> {

    /**
     * Returns the name of this group.  A group name is purely for helping
     * you to distinguish one group from others.
     */
    // 返回当前分组的名字
    String name();

    /**
     * Returns the {@link Channel} which has the specified {@link ChannelId}.
     *
     * @return the matching {@link Channel} if found. {@code null} otherwise.
     */
    // 根据ChannelId获取对于的Channel,Channel接口定义的时候就要一个id方法是用来返回当前的Channel的id
    Channel find(ChannelId id);

    /**
     * Writes the specified {@code message} to all {@link Channel}s in this
     * group. If the specified {@code message} is an instance of
     * {@link ByteBuf}, it is automatically
     * {@linkplain ByteBuf#duplicate() duplicated} to avoid a race
     * condition. The same is true for {@link ByteBufHolder}. Please note that this operation is asynchronous as
     * {@link Channel#write(Object)} is.
     *
     * @return itself
     */
    // 给当前组内的Channel群发消息，如果消息是ByteBuf类型将会不会出现发送内容错误的情况，因为他会将内容进行一份保存每次返回的都是最原始的对象
    // 并且此方法是异步的，因为Channel中的write是异步的。ChannelGroupFuture write(Object message);
    ChannelGroupFuture write(Object message);

    /**
     * Writes the specified {@code message} to all {@link Channel}s in this
     * group that are matched by the given {@link ChannelMatcher}. If the specified {@code message} is an instance of
     * {@link ByteBuf}, it is automatically
     * {@linkplain ByteBuf#duplicate() duplicated} to avoid a race
     * condition. The same is true for {@link ByteBufHolder}. Please note that this operation is asynchronous as
     * {@link Channel#write(Object)} is.
     *
     * @return the {@link ChannelGroupFuture} instance that notifies when
     *         the operation is done for all channels
     */
    // 是上方方法的重载，增加了匹配，用于匹配channel，这样就可以在组内匹配指定channel进行发送消息
    ChannelGroupFuture write(Object message, ChannelMatcher matcher);

    /**
     * Writes the specified {@code message} to all {@link Channel}s in this
     * group that are matched by the given {@link ChannelMatcher}. If the specified {@code message} is an instance of
     * {@link ByteBuf}, it is automatically
     * {@linkplain ByteBuf#duplicate() duplicated} to avoid a race
     * condition. The same is true for {@link ByteBufHolder}. Please note that this operation is asynchronous as
     * {@link Channel#write(Object)} is.
     *
     * If {@code voidPromise} is {@code true} {@link Channel#voidPromise()} is used for the writes and so the same
     * restrictions to the returned {@link ChannelGroupFuture} apply as to a void promise.
     *
     * @return the {@link ChannelGroupFuture} instance that notifies when
     *         the operation is done for all channels
     */
    // 上方方法的重载，添加了是否返回无效的应答，此应答就是VoidChannelGroupFuture，如果是false那么将返回DefaultChannelGroupFuture
    ChannelGroupFuture write(Object message, ChannelMatcher matcher, boolean voidPromise);

    /**
     * Flush all {@link Channel}s in this
     * group. If the specified {@code messages} are an instance of
     * {@link ByteBuf}, it is automatically
     * {@linkplain ByteBuf#duplicate() duplicated} to avoid a race
     * condition. Please note that this operation is asynchronous as
     * {@link Channel#write(Object)} is.
     *
     * @return the {@link ChannelGroupFuture} instance that notifies when
     *         the operation is done for all channels
     */
    // 刷新组内全部的channel管道，说白了就是循环channel集合然后调用每个channel的flush方法
    ChannelGroup flush();

    /**
     * Flush all {@link Channel}s in this group that are matched by the given {@link ChannelMatcher}.
     * If the specified {@code messages} are an instance of
     * {@link ByteBuf}, it is automatically
     * {@linkplain ByteBuf#duplicate() duplicated} to avoid a race
     * condition. Please note that this operation is asynchronous as
     * {@link Channel#write(Object)} is.
     *
     * @return the {@link ChannelGroupFuture} instance that notifies when
     *         the operation is done for all channels
     */
    // 上方方法的重载刷新匹配到的管道
    ChannelGroup flush(ChannelMatcher matcher);

    /**
     * Shortcut for calling {@link #write(Object)} and {@link #flush()}.
     */
    // 结合了write与flush方法
    ChannelGroupFuture writeAndFlush(Object message);

    /**
     * @deprecated Use {@link #writeAndFlush(Object)} instead.
     */
    // 此方法已经废弃了内部实现调用的是writeAndFlush方法
    @Deprecated
    ChannelGroupFuture flushAndWrite(Object message);

    /**
     * Shortcut for calling {@link #write(Object)} and {@link #flush()} and only act on
     * {@link Channel}s that are matched by the {@link ChannelMatcher}.
     */
    // 结合flush(ChannelMatcher matcher)与write(Object message, ChannelMatcher matcher)方法，可以过滤管道
    ChannelGroupFuture writeAndFlush(Object message, ChannelMatcher matcher);

    /**
     * Shortcut for calling {@link #write(Object, ChannelMatcher, boolean)} and {@link #flush()} and only act on
     * {@link Channel}s that are matched by the {@link ChannelMatcher}.
     */
    // 结合flush(ChannelMatcher matcher);与write(Object message, ChannelMatcher matcher, boolean voidPromise);方法。
    ChannelGroupFuture writeAndFlush(Object message, ChannelMatcher matcher, boolean voidPromise);

    /**
     * @deprecated Use {@link #writeAndFlush(Object, ChannelMatcher)} instead.
     */
    // 内部调用writeAndFlush(Object message, ChannelMatcher matcher)方法，此方法已废弃
    @Deprecated
    ChannelGroupFuture flushAndWrite(Object message, ChannelMatcher matcher);

    /**
     * Disconnects all {@link Channel}s in this group from their remote peers.
     *
     * @return the {@link ChannelGroupFuture} instance that notifies when
     *         the operation is done for all channels
     */
    // 操作组内所有的channel断开连接
    ChannelGroupFuture disconnect();

    /**
     * Disconnects all {@link Channel}s in this group from their remote peers,
     * that are matched by the given {@link ChannelMatcher}.
     *
     * @return the {@link ChannelGroupFuture} instance that notifies when
     *         the operation is done for all channels
     */
    // 上方方法的重构只是添加了筛选channel的功能
    ChannelGroupFuture disconnect(ChannelMatcher matcher);

    /**
     * Closes all {@link Channel}s in this group.  If the {@link Channel} is
     * connected to a remote peer or bound to a local address, it is
     * automatically disconnected and unbound.
     *
     * @return the {@link ChannelGroupFuture} instance that notifies when
     *         the operation is done for all channels
     */
    // 关闭channel管道
    ChannelGroupFuture close();

    /**
     * Closes all {@link Channel}s in this group that are matched by the given {@link ChannelMatcher}.
     * If the {@link Channel} is  connected to a remote peer or bound to a local address, it is
     * automatically disconnected and unbound.
     *
     * @return the {@link ChannelGroupFuture} instance that notifies when
     *         the operation is done for all channels
     */
    // 重构上方方法添加了管道的过滤
    ChannelGroupFuture close(ChannelMatcher matcher);

    /**
     * @deprecated This method will be removed in the next major feature release.
     *
     * Deregister all {@link Channel}s in this group from their {@link EventLoop}.
     * Please note that this operation is asynchronous as {@link Channel#deregister()} is.
     *
     * @return the {@link ChannelGroupFuture} instance that notifies when
     *         the operation is done for all channels
     */
    // 用于注销事件使用EventLoop中的
    @Deprecated
    ChannelGroupFuture deregister();

    /**
     * @deprecated This method will be removed in the next major feature release.
     *
     * Deregister all {@link Channel}s in this group from their {@link EventLoop} that are matched by the given
     * {@link ChannelMatcher}. Please note that this operation is asynchronous as {@link Channel#deregister()} is.
     *
     * @return the {@link ChannelGroupFuture} instance that notifies when
     *         the operation is done for all channels
     */
    // 重载上方代码加入管道筛选已经废弃不建议使用
    @Deprecated
    ChannelGroupFuture deregister(ChannelMatcher matcher);

    /**
     * Returns the {@link ChannelGroupFuture} which will be notified when all {@link Channel}s that are part of this
     * {@link ChannelGroup}, at the time of calling, are closed.
     */
    // 获取管道关闭时的Future，可以使用它监听关闭
    ChannelGroupFuture newCloseFuture();

    /**
     * Returns the {@link ChannelGroupFuture} which will be notified when all {@link Channel}s that are part of this
     * {@link ChannelGroup}, at the time of calling, are closed.
     */
    // 重载上方方法获取指定管道的关闭Future
    ChannelGroupFuture newCloseFuture(ChannelMatcher matcher);
}
