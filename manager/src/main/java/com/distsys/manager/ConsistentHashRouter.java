package com.distsys.manager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashRouter {
    private final SortedMap<Integer, String> ring = new TreeMap<>();
    private final int numberOfReplicas; // Virtual nodes for better distribution

    public ConsistentHashRouter(int numberOfReplicas) {
        this.numberOfReplicas = numberOfReplicas;
    }

    public void addNode(String nodeAddress) {
        for (int i = 0; i < numberOfReplicas; i++) {
            int hash = getHash(nodeAddress + i);
            ring.put(hash, nodeAddress);
        }
    }

    public void removeNode(String nodeAddress) {
        for (int i = 0; i < numberOfReplicas; i++) {
            int hash = getHash(nodeAddress + i);
            ring.remove(hash);
        }
    }

    // Find the closest node for a given Job ID
    public String routeJob(String jobId) {
        if (ring.isEmpty()) {
            return null;
        }
        int hash = getHash(jobId);
        
        if (!ring.containsKey(hash)) {
            // Find the next available node on the ring (Clockwise)
            SortedMap<Integer, String> tailMap = ring.tailMap(hash);
            hash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        }
        return ring.get(hash);
    }

    // Helper: MD5 Hashing (Standard for Consistent Hashing)
    private int getHash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes());
            // Convert first 4 bytes to int
            return ((digest[3] & 0xFF) << 24) | 
                   ((digest[2] & 0xFF) << 16) | 
                   ((digest[1] & 0xFF) << 8) | 
                   (digest[0] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not supported", e);
        }
    }
}
