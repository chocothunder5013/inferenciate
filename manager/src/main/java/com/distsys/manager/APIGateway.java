package com.distsys.manager;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.GlobalEventExecutor;

public class APIGateway {
    private final int port;
    // UPDATED: Store ClusterClient instead of a single stub
    private final ClusterClient clusterClient; 
    private final ChannelGroup activeWebSockets = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    // UPDATED: Constructor accepts ClusterClient
    public APIGateway(int port, ClusterClient clusterClient) {
        this.port = port;
        this.clusterClient = clusterClient;
    }

    public void start() throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ChannelPipeline p = ch.pipeline();
                     p.addLast(new HttpServerCodec());
                     p.addLast(new HttpObjectAggregator(10 * 1024 * 1024));
                     p.addLast(new ChunkedWriteHandler());
                     
                     // UPDATED: Pass the clusterClient to the handler
                     p.addLast(new HttpJobHandler(clusterClient, activeWebSockets));

                     p.addLast(new WebSocketServerProtocolHandler("/ws"));
                     p.addLast(new SimpleChannelInboundHandler<TextWebSocketFrame>() {
                         @Override
                         public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                             if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                                 System.out.println("[Dashboard] New Client Connected!");
                                 activeWebSockets.add(ctx.channel());
                             }
                             super.userEventTriggered(ctx, evt);
                         }
                         @Override
                         protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {}
                     });
                 }
             });

            System.out.println("[Manager] API Gateway listening on port " + port);
            Channel ch = b.bind(port).sync().channel();
            ch.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}