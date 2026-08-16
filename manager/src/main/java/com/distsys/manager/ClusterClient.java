package com.distsys.manager;

import inference.InferenceServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC client connection pool and worker node router for the Inferenciate Manager cluster.
 * <p>
 * Maintains persistent {@link ManagedChannel} connections to C++ inference worker nodes on port 50051.
 * Integrates with {@link ConsistentHashRouter} to deterministically map incoming job keys to worker IP addresses,
 * ensuring load distribution, virtual node replica mapping, and graceful channel lifecycle management.
 * </p>
 */
public class ClusterClient {
  private final ConsistentHashRouter router;
  private final ConcurrentHashMap<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();

  /**
   * Initializes a new ClusterClient with a consistent hash router configured with 50 virtual node replicas per physical worker.
   */
  public ClusterClient() {
    // 50 virtual nodes per worker for uniform ring distribution and minimal variance
    this.router = new ConsistentHashRouter(50);
  }

  /**
   * Registers a new worker node IP address in the consistent hash ring and pre-warms its gRPC ManagedChannel.
   *
   * @param ipAddress host IP address of the worker pod or server instance
   */
  public void addNode(String ipAddress) {
    System.out.println("[Cluster] Adding node to Hash Ring: " + ipAddress);
    router.addNode(ipAddress);
    getChannel(ipAddress);
  }

  /**
   * Removes a worker node IP address from the hash ring and gracefully shuts down its cached gRPC channel.
   *
   * @param ipAddress host IP address of the worker node to remove
   */
  public void removeNode(String ipAddress) {
    System.out.println("[Cluster] Removing node: " + ipAddress);
    router.removeNode(ipAddress);
    ManagedChannel ch = channelCache.remove(ipAddress);
    if (ch != null) ch.shutdown();
  }

  /**
   * Resolves the target worker node IP for a given job ID and retry attempt count using consistent hashing,
   * returning a synchronous blocking gRPC service stub.
   *
   * @param jobId unique job identifier
   * @param attempt current execution attempt count (used to alter routing key on retry)
   * @return blocking gRPC InferenceServiceBlockingStub targeting the resolved worker node
   * @throws RuntimeException if no active worker nodes are registered in the cluster pool
   */
  public InferenceServiceGrpc.InferenceServiceBlockingStub getStubForJob(
      String jobId, int attempt) {
    if (channelCache.isEmpty()) {
      throw new RuntimeException("No active workers in the grid!");
    }

    String routingKey = jobId + "-retry-" + attempt;
    String targetNodeIp = router.routeJob(routingKey);

    if (targetNodeIp == null) {
      targetNodeIp = channelCache.keySet().iterator().next();
    }

    System.out.println(
        "[Router] Routing " + jobId + " (Attempt " + attempt + ") -> " + targetNodeIp);
    ManagedChannel channel = getChannel(targetNodeIp);
    return InferenceServiceGrpc.newBlockingStub(channel);
  }

  /**
   * Retrieves a synchronous blocking gRPC service stub directly targeting a specific worker node IP.
   *
   * @param ip worker node IP address
   * @return blocking gRPC stub targeting the specified worker node
   */
  public InferenceServiceGrpc.InferenceServiceBlockingStub getStub(String ip) {
    return InferenceServiceGrpc.newBlockingStub(getChannel(ip));
  }

  /**
   * Retrieves an asynchronous gRPC service stub for non-blocking stream interaction with a target worker node.
   *
   * @param ip worker host IP address
   * @return non-blocking gRPC InferenceServiceStub
   */
  public InferenceServiceGrpc.InferenceServiceStub getAsyncStub(String ip) {
    return InferenceServiceGrpc.newStub(getChannel(ip));
  }

  /**
   * Resolves the target worker IP address for a given job identifier using consistent hashing without creating stubs.
   *
   * @param jobId unique job identifier string
   * @return target worker IP address
   * @throws RuntimeException if no active workers exist in the channel connection pool
   */
  public String getWorkerIp(String jobId) {
    if (channelCache.isEmpty()) {
      throw new RuntimeException("No active workers in the grid!");
    }
    String targetIp = router.routeJob(jobId + "-retry-0");
    return targetIp != null ? targetIp : channelCache.keySet().iterator().next();
  }

  /**
   * Returns a snapshot list of active worker IP addresses currently maintained in the connection pool.
   *
   * @return List of active worker IP strings
   */
  public java.util.List<String> getActiveWorkers() {
    return new java.util.ArrayList<>(channelCache.keySet());
  }

  /**
   * Obtains an existing cached ManagedChannel or constructs a new channel for the target worker IP.
   * Configures plaintext transport and a 50 MB inbound message buffer limit to accommodate large tensor payloads.
   *
   * @param ip worker host IP address
   * @return active ManagedChannel instance
   */
  private ManagedChannel getChannel(String ip) {
    return channelCache.computeIfAbsent(
        ip,
        k ->
            ManagedChannelBuilder.forAddress(ip, 50051)
                .usePlaintext()
                .maxInboundMessageSize(50 * 1024 * 1024)
                .build());
  }
}
