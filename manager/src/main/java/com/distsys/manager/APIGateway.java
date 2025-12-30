package com.distsys.manager;

import inference.InferenceServiceGrpc;
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
    private final InferenceServiceGrpc.InferenceServiceBlockingStub workerStub;
    // Track all connected dashboards so we can broadcast logs
    private final ChannelGroup activeWebSockets = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    public APIGateway(int port, InferenceServiceGrpc.InferenceServiceBlockingStub workerStub) {
        this.port = port;
        this.workerStub = workerStub;
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
                     // Allow payloads up to 10MB
		     p.addLast(new HttpObjectAggregator(10 * 1024 * 1024));
		     p.addLast(new ChunkedWriteHandler());
                     
                     // 1. Custom HTTP Handler (Handles REST API & passes WS handshake through)
                     p.addLast(new HttpJobHandler(workerStub, activeWebSockets));

                     // 2. WebSocket Protocol Handler (Handles handshake & frames)
                     p.addLast(new WebSocketServerProtocolHandler("/ws"));
                     
                     // 3. Simple handler to register new WS connections
                     p.addLast(new SimpleChannelInboundHandler<TextWebSocketFrame>() {
                         @Override
                         public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                             if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                                 System.out.println("[Dashboard] New Client Connected!");
                                 activeWebSockets.add(ctx.channel()); // Register channel
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
