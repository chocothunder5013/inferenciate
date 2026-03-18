package com.distsys.manager;

import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

public class ManagerNode {

  public static void main(String[] args) {
    System.out.println("==================================================");
    System.out.println("  Starting Inferenciate Manager (Production Mode)");
    System.out.println("==================================================");

    String workerServiceName = System.getenv("WORKER_SERVICE_NAME");
    if (workerServiceName == null || workerServiceName.isEmpty()) {
      System.out.println("[WARN] WORKER_SERVICE_NAME not set. Defaulting to localhost for dev.");
      workerServiceName = "localhost";
    }

    // --- NEW: Global WebSocket Group ---
    // We create this at the top level so both the API and the Discovery service can use it!
    ChannelGroup activeWebSockets = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    // 2. Initialize the Core Engine
    ClusterClient clusterClient = new ClusterClient();
    BatchScheduler batchScheduler = new BatchScheduler(clusterClient);

    // 3. Start Kubernetes DNS Discovery
    if (!workerServiceName.equals("localhost")) {
      // UPDATED: Pass activeWebSockets here
      K8sDiscoveryService discoveryService =
          new K8sDiscoveryService(workerServiceName, clusterClient, batchScheduler, activeWebSockets);
      discoveryService.start();
    } else {
      clusterClient.addNode("localhost");
    }

    // 4. Start the HTTP API Gateway
    try {
      // UPDATED: Pass activeWebSockets here as well
      APIGateway apiGateway = new APIGateway(8080, clusterClient, batchScheduler, activeWebSockets);
      apiGateway.start();
    } catch (Exception e) {
      System.err.println("[CRITICAL] Failed to start API Gateway: " + e.getMessage());
      System.exit(1);
    }
  }
}