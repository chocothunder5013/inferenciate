package com.distsys.manager;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class GossipService {
    private static final int GOSSIP_PORT = 9999;
    // In Docker Bridge network, broadcast is tricky. 
    // If this fails in Docker, you might need the specific subnet broadcast IP (e.g., 172.17.255.255)
    private static final String BROADCAST_IP = "255.255.255.255"; 

    private final ClusterClient clusterClient;

    public GossipService(ClusterClient clusterClient) {
        this.clusterClient = clusterClient;
    }

    public void start() {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
             .channel(NioDatagramChannel.class)
             .option(ChannelOption.SO_BROADCAST, true)
             .handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                 @Override
                 protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                     String msg = packet.content().toString(CharsetUtil.UTF_8);
                     String senderIp = packet.sender().getAddress().getHostAddress();
                     
                     // Filter out our own broadcast if running on same IP (optional logic)
                     
                     if (msg.startsWith("HELLO")) {
                         // 1. Update the "Phonebook" (Registry)
                         boolean isNew = !PeerRegistry.isKnown(senderIp);
                         PeerRegistry.registerPeer(senderIp);

                         // 2. If it's a new node, add it to the Hash Ring immediately
                         if (isNew) {
                             System.out.println("[Gossip] Discovered New Worker: " + senderIp);
                             clusterClient.addNode(senderIp);
                         }
                     }
                 }
             });

            Channel ch = b.bind(GOSSIP_PORT).sync().channel();
            System.out.println("[Gossip] Service listening on UDP port " + GOSSIP_PORT);

            // Start Broadcasting "I am here" every 2 seconds
            group.scheduleAtFixedRate(() -> {
                try {
                    // Send a simple heartbeat packet
                    String heartbeat = "HELLO_FROM_MANAGER";
                    ch.writeAndFlush(new DatagramPacket(
                            Unpooled.copiedBuffer(heartbeat, CharsetUtil.UTF_8),
                            new InetSocketAddress(BROADCAST_IP, GOSSIP_PORT)));
                    
                    // Maintenance: Check for dead nodes every cycle
                    // In a real app, you'd iterate PeerRegistry, check timestamps, 
                    // and call clusterClient.removeNode(ip) if expired.
                } catch (Exception e) {
                    System.err.println("[Gossip] Broadcast failed: " + e.getMessage());
                }
            }, 0, 2, TimeUnit.SECONDS);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}