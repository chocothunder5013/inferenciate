package com.distsys.manager;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import inference.InferenceServiceGrpc;
import java.util.concurrent.ConcurrentHashMap;

public class ClusterClient {
    private final ConsistentHashRouter router;
    // Cache open connections so we don't open a socket for every request
    private final ConcurrentHashMap<String, ManagedChannel> channelCache = new ConcurrentHashMap<>();

    public ClusterClient() {
        // 50 "Virtual Nodes" per real worker for better distribution
        this.router = new ConsistentHashRouter(50);
    }

    // Called by GossipService when a new node shouts "HELLO"
    public void addNode(String ipAddress) {
        System.out.println("[Cluster] Adding node to Hash Ring: " + ipAddress);
        router.addNode(ipAddress);
        // Pre-warm the connection
        getChannel(ipAddress); 
    }

    public void removeNode(String ipAddress) {
        System.out.println("[Cluster] Removing node: " + ipAddress);
        router.removeNode(ipAddress);
        ManagedChannel ch = channelCache.remove(ipAddress);
        if (ch != null) ch.shutdown();
    }

    // The Magic Method: Gets the specific stub for this Job ID
    // Get the N-th node in the ring for this job (attempt 0 = primary, 1 = backup, etc.)
    public InferenceServiceGrpc.InferenceServiceBlockingStub getStubForJob(String jobId, int attempt) {
        if (channelCache.isEmpty()) {
            throw new RuntimeException("No active workers in the grid!");
        }
        
        // Salt the hash with the attempt number to jump to a different point in the ring
        String routingKey = jobId + "-retry-" + attempt;
        String targetNodeIp = router.routeJob(routingKey);

        if (targetNodeIp == null) {
            // Fallback: Just grab any active node if hashing fails
            targetNodeIp = channelCache.keySet().iterator().next();
        }

        System.out.println("[Router] Routing " + jobId + " (Attempt " + attempt + ") -> " + targetNodeIp);
        ManagedChannel channel = getChannel(targetNodeIp);
        return InferenceServiceGrpc.newBlockingStub(channel);
    }

    private ManagedChannel getChannel(String ip) {
        // Compute target: if running in Docker, you might need port mapping logic.
        // For now, assuming all workers listen on 50051.
        String target = ip + ":50051"; 
        
        return channelCache.computeIfAbsent(ip, k -> 
            ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build()
        );
    }
}
