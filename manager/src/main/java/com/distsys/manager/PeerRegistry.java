package com.distsys.manager;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

public class PeerRegistry {
    // Map: NodeID (IP:Port) -> Last Heartbeat Timestamp
    private static final ConcurrentHashMap<String, Long> peers = new ConcurrentHashMap<>();
    private static final long EXPIRY_MS = 5000; // Nodes die after 5s silence
    public static boolean isKnown(String address) {
        return peers.containsKey(address);
    }
    public static void registerPeer(String address) {
        peers.put(address, System.currentTimeMillis());
    }

    public static Set<String> getActivePeers() {
        long now = System.currentTimeMillis();
        // Remove dead nodes
        peers.entrySet().removeIf(entry -> (now - entry.getValue()) > EXPIRY_MS);
        return peers.keySet();
    }

    public static void printPeers() {
        System.out.println("[Gossip] Active Nodes: " + getActivePeers());
    }
}
