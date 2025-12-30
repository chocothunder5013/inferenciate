package com.distsys.manager;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import inference.InferenceServiceGrpc;

public class ManagerNode {
    // 1. Single instance of our Cluster State
    public static final ClusterClient cluster = new ClusterClient();

    public static void main(String[] args) throws Exception {
        System.out.println("[Manager] GridMind Booting...");

        // 2. Start Gossip (It will now update 'cluster' automatically)
        // Note: You need to update GossipService to call cluster.addNode()
        new GossipService(cluster).start(); 

        // 3. Pass the 'cluster' to the API Gateway instead of a single stub
        new APIGateway(8080, cluster).start();
    }
}