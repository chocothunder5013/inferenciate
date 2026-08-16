package com.distsys.manager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe peer heartbeat registry for tracking cluster node liveness.
 * <p>
 * Maintains an in-memory timestamp registry of active peer nodes. Implements a sliding window TTL
 * ({@code EXPIRY_MS = 5000}) to automatically prune stale or uncommunicative peer nodes that fail
 * to send heartbeat updates within the designated 5-second window.
 * </p>
 */
public class PeerRegistry {
  /** In-memory timestamp mapping (node address -> last heartbeat timestamp in ms). */
  private static final ConcurrentHashMap<String, Long> peers = new ConcurrentHashMap<>();
  /** Heartbeat expiration threshold in milliseconds (5 seconds). */
  private static final long EXPIRY_MS = 5000;

  /**
   * Checks whether a peer address is currently registered in the peer table.
   *
   * @param address node IP or address string
   * @return {@code true} if peer is known and present in table; {@code false} otherwise
   */
  public static boolean isKnown(String address) {
    return peers.containsKey(address);
  }

  /**
   * Updates or registers the last active heartbeat timestamp for a peer node address.
   *
   * @param address node IP or address string
   */
  public static void registerPeer(String address) {
    peers.put(address, System.currentTimeMillis());
  }

  /**
   * Evaluates peer timestamps, automatically removes stale entries exceeding {@code EXPIRY_MS},
   * and returns the set of active peer address strings.
   *
   * @return Set of active, non-expired peer address strings
   */
  public static Set<String> getActivePeers() {
    long now = System.currentTimeMillis();
    peers.entrySet().removeIf(entry -> (now - entry.getValue()) > EXPIRY_MS);
    return peers.keySet();
  }

  /**
   * Logs currently active peer addresses to stdout for debugging and diagnostics.
   */
  public static void printPeers() {
    System.out.println("[Gossip] Active Nodes: " + getActivePeers());
  }
}
