package com.distsys.manager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ConsistentHashRouter {
  private final SortedMap<Integer, String> ring = new TreeMap<>();
  private final int numberOfReplicas;

  // NEW: ReadWriteLock for high-concurrency thread safety
  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  public ConsistentHashRouter(int numberOfReplicas) {
    this.numberOfReplicas = numberOfReplicas;
  }

  public void addNode(String nodeAddress) {
    lock.writeLock().lock();
    try {
      for (int i = 0; i < numberOfReplicas; i++) {
        ring.put(getHash(nodeAddress + "-replica-" + i), nodeAddress);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void removeNode(String nodeAddress) {
    lock.writeLock().lock();
    try {
      for (int i = 0; i < numberOfReplicas; i++) {
        ring.remove(getHash(nodeAddress + "-replica-" + i));
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  public String routeJob(String jobId) {
    lock.readLock().lock();
    try {
      if (ring.isEmpty()) return null;

      int hash = getHash(jobId);
      if (!ring.containsKey(hash)) {
        SortedMap<Integer, String> tailMap = ring.tailMap(hash);
        hash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
      }
      return ring.get(hash);
    } finally {
      lock.readLock().unlock();
    }
  }

  private int getHash(String key) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] digest = md.digest(key.getBytes());
      return ((digest[3] & 0xFF) << 24)
          | ((digest[2] & 0xFF) << 16)
          | ((digest[1] & 0xFF) << 8)
          | (digest[0] & 0xFF);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("MD5 not supported", e);
    }
  }
}
