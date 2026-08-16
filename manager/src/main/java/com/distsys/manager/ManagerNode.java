package com.distsys.manager;

import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

/**
 * Main application entry point for the Inferenciate Manager node.
 * <p>
 * Responsible for bootstrapping core distributed system components:
 * <ul>
 *   <li>Initializes global WebSocket channel group for telemetry broadcasting.</li>
 *   <li>Instantiates {@link ClusterClient} connection pool and {@link BatchScheduler} engine.</li>
 *   <li>Configures worker discovery strategy (Kubernetes DNS resolution vs local single-node mode).</li>
 *   <li>Launches Netty {@link APIGateway} listening on port 8080.</li>
 * </ul>
 * </p>
 */
public class ManagerNode {

  /**
   * Application bootstrap main method.
   *
   * @param args command-line arguments (unused; environment variables take precedence)
   */
  public static void main(String[] args) {
    System.out.println("==================================================");
    System.out.println("  Starting Inferenciate Manager (Production Mode)");
    System.out.println("==================================================");

    String workerServiceName = System.getenv("WORKER_SERVICE_NAME");
    if (workerServiceName == null || workerServiceName.isEmpty()) {
      System.out.println("[WARN] WORKER_SERVICE_NAME not set. Defaulting to localhost for dev.");
      workerServiceName = "localhost";
    }

    // Shared ChannelGroup for broadcasting WebSocket telemetry events across services
    ChannelGroup activeWebSockets = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    // Initialize core cluster client and batch scheduler engines
    ClusterClient clusterClient = new ClusterClient();
    BatchScheduler batchScheduler = new BatchScheduler(clusterClient);

    // Configure worker node discovery strategy
    if (!workerServiceName.equals("localhost")) {
      K8sDiscoveryService discoveryService =
          new K8sDiscoveryService(
              workerServiceName, clusterClient, batchScheduler, activeWebSockets);
      discoveryService.start();
    } else {
      clusterClient.addNode("localhost");
    }

    // Start Netty HTTP API Gateway and WebSocket server listening on port 8080
    try {
      APIGateway apiGateway = new APIGateway(8080, clusterClient, batchScheduler, activeWebSockets);
      apiGateway.start();
    } catch (Exception e) {
      System.err.println("[CRITICAL] Failed to start API Gateway: " + e.getMessage());
      System.exit(1);
    }
  }
}
