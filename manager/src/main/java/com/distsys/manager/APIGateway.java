package com.distsys.manager;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.stream.ChunkedWriteHandler;

public class APIGateway {
  private final int port;
  private final ClusterClient clusterClient;
  private final BatchScheduler batchScheduler;
  private final ChannelGroup activeWebSockets;

  public APIGateway(int port, ClusterClient clusterClient, BatchScheduler batchScheduler, ChannelGroup activeWebSockets) {
    this.port = port;
    this.clusterClient = clusterClient;
    this.batchScheduler = batchScheduler;
    this.activeWebSockets = activeWebSockets;
  }

  public void start() throws Exception {
    EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    EventLoopGroup workerGroup = new NioEventLoopGroup();
    try {
      ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .childHandler(
              new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                  ChannelPipeline p = ch.pipeline();
                  p.addLast(new HttpServerCodec());
                  p.addLast(new HttpObjectAggregator(10 * 1024 * 1024));
                  p.addLast(new ChunkedWriteHandler());

                  // Pass the injected WebSockets down to the Job Handler
                  p.addLast(new HttpJobHandler(clusterClient, batchScheduler, activeWebSockets));

                  p.addLast(new WebSocketServerProtocolHandler("/ws"));
                  p.addLast(
                      new SimpleChannelInboundHandler<TextWebSocketFrame>() {
                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
                            throws Exception {
                          if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                            System.out.println("[Dashboard] New Client Connected!");
                            activeWebSockets.add(ctx.channel());

                            java.util.List<String> workers = clusterClient.getActiveWorkers();
                            
                            StringBuilder jsonBuilder = new StringBuilder("{\"type\": \"topology_update\", \"workers\": [");
                            for (int i = 0; i < workers.size(); i++) {
                              jsonBuilder.append("\"").append(workers.get(i)).append("\"");
                              if (i < workers.size() - 1) jsonBuilder.append(",");
                            }
                            jsonBuilder.append("]}");
                            
                            ctx.channel().writeAndFlush(new TextWebSocketFrame(jsonBuilder.toString()));
                          }
                          super.userEventTriggered(ctx, evt);
                        }

                        // THIS WAS ACCIDENTALLY DELETED!
                        @Override
                        protected void channelRead0(
                            ChannelHandlerContext ctx, TextWebSocketFrame frame) {}
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