package com.distsys.manager;

import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class K8sDiscoveryService {
  private final String workerServiceName;
  private final ClusterClient clusterClient;
  private final BatchScheduler batchScheduler;
  private final ChannelGroup activeWebSockets; // Added!
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  private Set<String> activeWorkers = new HashSet<>();

  // Updated Constructor
  public K8sDiscoveryService(
      String workerServiceName,
      ClusterClient clusterClient,
      BatchScheduler batchScheduler,
      ChannelGroup activeWebSockets) {
    this.workerServiceName = workerServiceName;
    this.clusterClient = clusterClient;
    this.batchScheduler = batchScheduler;
    this.activeWebSockets = activeWebSockets;
  }

  public void start() {
    System.out.println("[Discovery] Starting K8s DNS polling for service: " + workerServiceName);

    scheduler.scheduleAtFixedRate(
        () -> {
          try {
            InetAddress[] addresses = InetAddress.getAllByName(workerServiceName);
            Set<String> currentWorkers = new HashSet<>();

            for (InetAddress addr : addresses) {
              currentWorkers.add(addr.getHostAddress());
            }

            // Define the boolean outside the loops!
            boolean topologyChanged = false;

            // 1. Find NEW workers
            for (String ip : currentWorkers) {
              if (!activeWorkers.contains(ip)) {
                System.out.println("[Discovery] New Worker Pod discovered: " + ip);
                clusterClient.addNode(ip);
                topologyChanged = true;
              }
            }

            // 2. Find DEAD workers
            for (String ip : activeWorkers) {
              if (!currentWorkers.contains(ip)) {
                System.err.println("[Discovery] Worker Pod died/scaled down: " + ip);
                clusterClient.removeNode(ip);
                batchScheduler.removeWorker(ip); // Flush queue safely!
                topologyChanged = true;
              }
            }

            // Update state
            activeWorkers = currentWorkers;

            // 3. Broadcast to React Dashboard
            if (topologyChanged && activeWebSockets != null && !activeWebSockets.isEmpty()) {
              String workersJsonArray =
                  activeWorkers.stream()
                      .map(w -> "\"" + w + "\"")
                      .collect(Collectors.joining(", ", "[", "]"));

              String wsPayload =
                  "{\"type\": \"topology_update\", \"workers\": " + workersJsonArray + "}";
              activeWebSockets.writeAndFlush(new TextWebSocketFrame(wsPayload));
            }

          } catch (UnknownHostException e) {
            System.err.println("[Discovery] Waiting for worker pods to spin up...");
          } catch (Exception e) {
            System.err.println("[Discovery] Error polling DNS: " + e.getMessage());
          }
        },
        0,
        5,
        TimeUnit.SECONDS);
  }
}
