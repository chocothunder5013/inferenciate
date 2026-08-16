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

/**
 * Service that periodically polls Kubernetes headless service DNS records to discover active C++ worker Pods.
 * <p>
 * Performs periodic DNS A/AAAA record queries against the Kubernetes internal DNS resolver (e.g. CoreDNS)
 * for the designated Headless Service domain ({@code workerServiceName}). Dynamically registers newly discovered
 * Pod IP addresses into the consistent hash ring and removes dead/scaled-down Pod IPs while broadcasting live
 * topology update events over WebSockets to connected UI dashboards.
 * </p>
 */
public class K8sDiscoveryService {
  private final String workerServiceName;
  private final ClusterClient clusterClient;
  private final BatchScheduler batchScheduler;
  private final ChannelGroup activeWebSockets;
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  private Set<String> activeWorkers = new HashSet<>();

  /**
   * Constructs a new K8sDiscoveryService instance bound to target cluster components.
   *
   * @param workerServiceName Kubernetes headless service DNS hostname
   * @param clusterClient cluster client connection pool manager
   * @param batchScheduler dynamic batching scheduler
   * @param activeWebSockets group of connected WebSocket client channels
   */
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

  /**
   * Starts periodic background DNS resolution polling (every 5 seconds) to track worker Pod lifecycle changes.
   */
  public void start() {
    System.out.println("[Discovery] Starting K8s DNS polling for service: " + workerServiceName);

    scheduler.scheduleAtFixedRate(
        () -> {
          try {
            // Perform DNS A-record resolution returning IP addresses for all running worker Pods
            InetAddress[] addresses = InetAddress.getAllByName(workerServiceName);
            Set<String> currentWorkers = new HashSet<>();

            for (InetAddress addr : addresses) {
              currentWorkers.add(addr.getHostAddress());
            }

            boolean topologyChanged = false;

            // Register newly discovered worker nodes in hash ring and client connection pool
            for (String ip : currentWorkers) {
              if (!activeWorkers.contains(ip)) {
                System.out.println("[Discovery] New Worker Pod discovered: " + ip);
                clusterClient.addNode(ip);
                topologyChanged = true;
              }
            }

            // Remove unreachable or scaled-down worker nodes from hash ring and scheduler queues
            for (String ip : activeWorkers) {
              if (!currentWorkers.contains(ip)) {
                System.err.println("[Discovery] Worker Pod died/scaled down: " + ip);
                clusterClient.removeNode(ip);
                batchScheduler.removeWorker(ip);
                topologyChanged = true;
              }
            }

            activeWorkers = currentWorkers;

            // Broadcast topology state updates to connected WebSocket dashboard clients when node list changes
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
