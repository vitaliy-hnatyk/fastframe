package dev.fastframe.netty;

import dev.fastframe.core.http.Router;
import dev.fastframe.observability.HttpMetrics;
import dev.fastframe.observability.Readiness;
import dev.fastframe.security.HttpSecurity;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.*;
import io.netty.handler.codec.http2.*;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/** HTTP/1.1 server with optional TLS/ALPN HTTP/2 or h2c. Use nonblocking handlers only. */
public final class NettyServer implements AutoCloseable {
    private final Router router;
    private final String host;
    private final int port;
    private final NettyOptions options;
    private final Semaphore permits;
    private final HttpMetrics metrics;
    private final Readiness readiness;
    private final boolean accessLog;
    private final HttpSecurity security;
    private final NettyTransport transport;
    private final ChannelGroup connections = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel serverChannel;

    public NettyServer(Router router, String host, int port) {
        this(router, host, port, NettyOptions.defaults());
    }

    public NettyServer(Router router, String host, int port, NettyOptions options) {
        this(router, host, port, options, new HttpMetrics(), new Readiness(), false);
    }

    public NettyServer(Router router, String host, int port, NettyOptions options,
                       HttpMetrics metrics, Readiness readiness, boolean accessLog) {
        this(router, host, port, options, metrics, readiness, accessLog, HttpSecurity.defaults());
    }

    public NettyServer(Router router, String host, int port, NettyOptions options,
                       HttpMetrics metrics, Readiness readiness, boolean accessLog, HttpSecurity security) {
        this(router, host, port, options, metrics, readiness, accessLog, security, NettyTransport.http11());
    }
    public NettyServer(Router router,String host,int port,NettyOptions options,NettyTransport transport){
        this(router,host,port,options,new HttpMetrics(),new Readiness(),false,HttpSecurity.defaults(),transport);
    }
    public NettyServer(Router router,String host,int port,NettyOptions options,HttpMetrics metrics,Readiness readiness,boolean accessLog,HttpSecurity security,NettyTransport transport){
        this.transport=Objects.requireNonNull(transport);
        this.security = Objects.requireNonNull(security, "security");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.accessLog = accessLog;
        this.options = Objects.requireNonNull(options, "options");
        this.permits = new Semaphore(options.maxConcurrentRequests());
        this.router = Objects.requireNonNull(router, "router");
        this.host = Objects.requireNonNull(host, "host");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        this.port = port;
    }

    public synchronized void start() throws InterruptedException {
        if (boss != null) throw new IllegalStateException("Server already started");
        final SslContext ssl;
        try{ssl=transport.tls()==null?null:transport.tls().build();}
        catch(javax.net.ssl.SSLException ex){throw new IllegalStateException("Invalid TLS PEM settings",ex);}
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
                            connections.add(channel);
                            if(transport.h2cPriorKnowledge()) configureH2(channel.pipeline());
                            else if(ssl==null) configureH1(channel.pipeline());
                            else {
                                channel.pipeline().addLast(ssl.newHandler(channel.alloc()));
                                if(transport.tls().http2())channel.pipeline().addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1){
                                  @Override protected void configurePipeline(ChannelHandlerContext ctx,String protocol){
                                    if(ApplicationProtocolNames.HTTP_2.equals(protocol))configureH2(ctx.pipeline());
                                    else if(ApplicationProtocolNames.HTTP_1_1.equals(protocol))configureH1(ctx.pipeline());
                                    else throw new IllegalStateException("Unsupported ALPN: "+protocol);
                                  }
                                });
                                else configureH1(channel.pipeline());
                            }
                        }
                    });
            serverChannel = bootstrap.bind(host, port).sync().channel();
            readiness.ready();
        } catch (InterruptedException | RuntimeException ex) {
            close();
            throw ex;
        }
    }

    private void configureH1(ChannelPipeline p){
        p.addLast(new HttpServerCodec());p.addLast(new HttpObjectAggregator(options.maxRequestBytes()));
        p.addLast(new NettyRequestHandler(router,options,permits,metrics,accessLog,security));
    }
    private void configureH2(ChannelPipeline p){
        p.addLast(Http2FrameCodecBuilder.forServer().build());
        p.addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>(){
           @Override protected void initChannel(Channel stream){
              connections.add(stream);
              stream.pipeline().addLast(new Http2StreamFrameToHttpObjectCodec(true));
              stream.pipeline().addLast(new HttpObjectAggregator(options.maxRequestBytes()));
              stream.pipeline().addLast(new NettyRequestHandler(router,options,permits,metrics,accessLog,security));
           }
        }));
    }
    public int boundPort() {
        if (serverChannel == null) throw new IllegalStateException("Server not started");
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    public void awaitClose() throws InterruptedException {
        Channel listening = serverChannel;
        if (listening == null) throw new IllegalStateException("Server not started");
        listening.closeFuture().sync();
    }

    /** Stop accepting requests, wait for existing handler work, then force-close on deadline.
     * Do not call from a Netty event loop. Cancellation remains cooperative. */
    public synchronized void shutdownGracefully(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("negative timeout");
        readiness.notReady();
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        connections.forEach(channel -> {
            if (channel.isActive()) channel.eventLoop().execute(() -> {
                NettyRequestHandler handler = channel.pipeline().get(NettyRequestHandler.class);
                if (handler == null) channel.close();
                else handler.beginDrain(channel);
            });
        });
        // ChannelGroup waits on the snapshot of connected clients; cap waiting time.
        if (!timeout.isZero()) connections.newCloseFuture().awaitUninterruptibly(timeout.toMillis());
        close();
    }

    @Override
    public synchronized void close() {
        readiness.notReady();
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        connections.close().awaitUninterruptibly();
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
