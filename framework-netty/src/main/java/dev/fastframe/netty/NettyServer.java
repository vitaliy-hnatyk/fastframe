package dev.fastframe.netty;

import dev.fastframe.core.http.Router;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.net.InetSocketAddress;
import java.util.Objects;

/** HTTP/1.1 server; use nonblocking handlers only. */
public final class NettyServer implements AutoCloseable {
    private final Router router;
    private final String host;
    private final int port;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel serverChannel;

    public NettyServer(Router router, String host, int port) {
        this.router = Objects.requireNonNull(router, "router");
        this.host = Objects.requireNonNull(host, "host");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        this.port = port;
    }

    public synchronized void start() throws InterruptedException {
        if (boss != null) throw new IllegalStateException("Server already started");
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(64 * 1024))
                                    .addLast(new NettyRequestHandler(router));
                        }
                    });
            serverChannel = bootstrap.bind(host, port).sync().channel();
        } catch (InterruptedException | RuntimeException ex) {
            close();
            throw ex;
        }
    }

    public int boundPort() {
        if (serverChannel == null) throw new IllegalStateException("Server not started");
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void awaitClose() throws InterruptedException {
        if (serverChannel == null) throw new IllegalStateException("Server not started");
        serverChannel.closeFuture().sync();
    }

    @Override
    public synchronized void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (workers != null) {
            workers.shutdownGracefully().syncUninterruptibly();
            workers = null;
        }
        if (boss != null) {
            boss.shutdownGracefully().syncUninterruptibly();
            boss = null;
        }
    }
}
