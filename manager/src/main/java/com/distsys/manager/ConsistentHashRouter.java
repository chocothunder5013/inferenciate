package com.distsys.manager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Consistent hashing router that maps job request identifiers to worker nodes on a circular ring.
 * <p>
 * Implements standard consistent hashing using a 32-bit integer MD5 hash ring ({@link TreeMap}).
 * Configurable virtual node replicas ({@code numberOfReplicas}) are placed onto the ring for each
 * physical worker node, smoothing out request distribution and minimizing partition movement when
 * worker nodes scale up or down.
 * </p>
 * <p>
 * Thread safety is guaranteed via a {@link ReentrantReadWriteLock}, enabling highly concurrent
 * non-blocking job routing operations alongside atomic ring topology updates.
 * </p>
 */
public class ConsistentHashRouter {
  /** Sorted map representing the circular hash ring (32-bit integer hash -> node address). */
  private final SortedMap<Integer, String> ring = new TreeMap<>();
  /** Number of virtual node replicas created per physical node address. */
  private final int numberOfReplicas;
  /** Read/write lock ensuring safe concurrent access to the underlying TreeMap ring. */
  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  /**
   * Constructs a consistent hash router with a specified virtual node replica factor.
   *
   * @param numberOfReplicas number of virtual node points to generate per physical worker address
   */
  public ConsistentHashRouter(int numberOfReplicas) {
    this.numberOfReplicas = numberOfReplicas;
  }

  /**
   * Generates and places {@code numberOfReplicas} virtual node points for a worker address onto the hash ring.
   * Acquires the write lock to mutate the hash ring atomically.
   *
   * @param nodeAddress IP address or hostname of the worker node
   */
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

  /**
   * Removes all virtual node points associated with a worker address from the hash ring.
   * Acquires the write lock to mutate the hash ring atomically.
   *
   * @param nodeAddress IP address or hostname of the worker node to remove
   */
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

  /**
   * Routes a job request identifier to the first worker node encountered clockwise on the hash ring.
   * Acquires the shared read lock to permit concurrent job routing across threads without blocking.
   *
   * @param jobId request identifier string used as the hashing key
   * @return worker node IP address responsible for processing this job, or {@code null} if ring is empty
   */
  public String routeJob(String jobId) {
    lock.readLock().lock();
    try {
      if (ring.isEmpty()) return null;

      int hash = getHash(jobId);
      if (!ring.containsKey(hash)) {
        SortedMap<Integer, String> tailMap = ring.tailMap(hash);
        // If hash exceeds largest key in ring, wrap around to the first key in the ring
        hash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
      }
      return ring.get(hash);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Computes a uniform 32-bit signed integer hash for a string key using MD5 digest bit truncation.
   * Extracts the first 4 bytes of MD5 in little-endian order to map keys evenly across 32-bit integer space.
   *
   * @param key target input key string
   * @return 32-bit integer hash value
   * @throws RuntimeException if MD5 algorithm provider is missing from Java security context
   */
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
