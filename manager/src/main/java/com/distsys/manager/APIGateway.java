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

/**
 * Netty-based HTTP and WebSocket API Gateway for the Inferenciate Manager node.
 * <p>
 * Serves as the primary ingress entry point for incoming client HTTP inference requests
 * and maintains active persistent WebSocket connections for broadcasting real-time cluster state,
 * node topology updates, and per-job execution telemetry to monitoring clients (e.g. Dashboard).
 * </p>
 */
public class APIGateway {
  private final int port;
  private final ClusterClient clusterClient;
  private final BatchScheduler batchScheduler;
  private final ChannelGroup activeWebSockets;

  /**
   * Constructs a new API Gateway instance with designated network port and cluster references.
   *
   * @param port listening TCP port for HTTP and WebSocket traffic (typically 8080)
   * @param clusterClient gRPC client pool and consistent hash routing router manager
   * @param batchScheduler dynamic batching scheduler engine for aggregating requests
   * @param activeWebSockets thread-safe channel group containing all active dashboard WebSockets
   */
  public APIGateway(
      int port,
      ClusterClient clusterClient,
      BatchScheduler batchScheduler,
      ChannelGroup activeWebSockets) {
    this.port = port;
    this.clusterClient = clusterClient;
    this.batchScheduler = batchScheduler;
    this.activeWebSockets = activeWebSockets;
  }

  /**
   * Initializes the asynchronous Netty server bootstrap, configures channel pipeline handlers,
   * binds to the target TCP port, and blocks until the server socket closes.
   *
   * @throws Exception if binding to the specified port fails or event loops fail initialization
   */
  public void start() throws Exception {
    // Boss group accepts incoming connections (single acceptor thread)
    EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    // Worker group handles network I/O, HTTP parsing, and execution dispatch (default core * 2)
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
                  // Decodes HTTP requests and encodes HTTP responses
                  p.addLast(new HttpServerCodec());
                  // Aggregates chunked HTTP messages into FullHttpRequest (10 MB maximum payload body)
                  p.addLast(new HttpObjectAggregator(10 * 1024 * 1024));
                  // Supports asynchronous writing of large data streams
                  p.addLast(new ChunkedWriteHandler());

                  // Inbound HTTP route handler for single (/api/job) and batch (/api/batch) endpoints
                  p.addLast(new HttpJobHandler(clusterClient, batchScheduler, activeWebSockets));

                  // Protocol handler for upgrading HTTP requests to WebSockets at URI endpoint /ws
                  p.addLast(new WebSocketServerProtocolHandler("/ws"));
                  p.addLast(
                      new SimpleChannelInboundHandler<TextWebSocketFrame>() {
                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
                            throws Exception {
                          // Triggered immediately after WebSocket handshake successfully completes
                          if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                            System.out.println("[Dashboard] New Client Connected!");
                            activeWebSockets.add(ctx.channel());

                            java.util.List<String> workers = clusterClient.getActiveWorkers();

                            // Construct JSON payload containing active cluster worker IPs
                            StringBuilder jsonBuilder =
                                new StringBuilder("{\"type\": \"topology_update\", \"workers\": [");
                            for (int i = 0; i < workers.size(); i++) {
                              jsonBuilder.append("\"").append(workers.get(i)).append("\"");
                              if (i < workers.size() - 1) jsonBuilder.append(",");
                            }
                            jsonBuilder.append("]}");

                            // Immediately transmit initial cluster topology snapshot to connected client
                            ctx.channel()
                                .writeAndFlush(new TextWebSocketFrame(jsonBuilder.toString()));
                          }
                          super.userEventTriggered(ctx, evt);
                        }

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
      // Gracefully terminate Netty thread pools on shutdown
      bossGroup.shutdownGracefully();
      workerGroup.shutdownGracefully();
    }
  }
}
