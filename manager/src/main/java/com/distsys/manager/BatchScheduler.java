package com.distsys.manager;

import inference.BatchInferenceRequest;
import inference.BatchInferenceResponse;
import inference.InferenceRequest;
import inference.InferenceResponse;
import inference.InferenceServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Dynamic batch scheduling engine for the Inferenciate Manager node.
 * <p>
 * Maintains isolated per-worker pending request queues and single-threaded dispatch loops.
 * Aggregates incoming single inference requests into dynamic batches based on batch capacity
 * ({@code BATCH_SIZE}) or time latency window ({@code MAX_WAIT_MS}), maximizing downstream
 * hardware throughput on C++ worker nodes while maintaining strict response SLAs.
 * </p>
 */
public class BatchScheduler {
  /** Target batch capacity before triggering immediate dispatch. */
  private static final int BATCH_SIZE = 2;
  /** Maximum latency wait window in milliseconds before dispatching incomplete batches. */
  private static final int MAX_WAIT_MS = 50;

  /** Thread-safe map tracking per-worker pending job queues. */
  private final ConcurrentHashMap<String, BlockingQueue<PendingJob>> workerQueues =
      new ConcurrentHashMap<>();
  /** Thread-safe map tracking per-worker single-threaded dispatch executors. */
  private final ConcurrentHashMap<String, ExecutorService> workerThreads =
      new ConcurrentHashMap<>();

  private final ClusterClient clusterClient;

  /**
   * Initializes a new BatchScheduler instance bound to the provided cluster client connection pool.
   *
   * @param clusterClient reference to the active ClusterClient instance
   */
  public BatchScheduler(ClusterClient clusterClient) {
    this.clusterClient = clusterClient;
  }

  /**
   * Returns the current queue depth (number of pending jobs waiting for dispatch) for a given worker node.
   *
   * @param workerAddress target worker IP address
   * @return current queue depth, or 0 if worker queue does not exist
   */
  public int getQueueDepth(String workerAddress) {
    BlockingQueue<PendingJob> queue = workerQueues.get(workerAddress);
    return queue != null ? queue.size() : 0;
  }

  /**
   * Submits a single inference request to the target worker node's queue for dynamic batching.
   * Dynamically initializes the background dispatch loop for the worker if not already present.
   *
   * @param workerAddress target worker IP address selected by consistent hashing
   * @param req protobuf InferenceRequest object
   * @return Future containing the completed InferenceResponse
   */
  public Future<InferenceResponse> submit(String workerAddress, InferenceRequest req) {
    CompletableFuture<InferenceResponse> future = new CompletableFuture<>();

    workerQueues.putIfAbsent(workerAddress, new LinkedBlockingQueue<>());
    workerThreads.computeIfAbsent(
        workerAddress,
        addr -> {
          ExecutorService executor = Executors.newSingleThreadExecutor();
          executor.submit(() -> startLoopForWorker(addr, workerQueues.get(addr)));
          return executor;
        });

    workerQueues.get(workerAddress).add(new PendingJob(req, future));
    return future;
  }

  /**
   * Submits a pre-aggregated bulk batch request directly to a C++ worker node without queueing.
   * Used for handling multipart bulk uploads received via POST /api/batch.
   *
   * @param workerAddress target worker IP address
   * @param batchReq pre-assembled protobuf BatchInferenceRequest object
   * @return CompletableFuture containing the full BatchInferenceResponse
   */
  public CompletableFuture<BatchInferenceResponse> submitBatch(
      String workerAddress, BatchInferenceRequest batchReq) {
    CompletableFuture<BatchInferenceResponse> future = new CompletableFuture<>();

    try {
      InferenceServiceGrpc.InferenceServiceStub asyncStub =
          clusterClient.getAsyncStub(workerAddress).withWaitForReady();

      asyncStub.predictBatch(
          batchReq,
          new StreamObserver<BatchInferenceResponse>() {
            @Override
            public void onNext(BatchInferenceResponse response) {
              future.complete(response);
            }

            @Override
            public void onError(Throwable t) {
              System.err.println("[BatchScheduler] Bulk Batch failed: " + t.getMessage());
              future.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
              // gRPC response stream completed successfully
            }
          });

    } catch (Exception e) {
      future.completeExceptionally(e);
    }

    return future;
  }

  /**
   * Cleans up resources, shuts down background dispatch loops, and fails active jobs exceptionally
   * when a worker node detaches, dies, or fails heartbeat checks.
   *
   * @param workerAddress worker node IP address to purge
   */
  public void removeWorker(String workerAddress) {
    System.out.println("[BatchScheduler] Cleaning up resources for dead worker: " + workerAddress);

    ExecutorService executor = workerThreads.remove(workerAddress);
    if (executor != null) {
      executor.shutdownNow();
    }

    BlockingQueue<PendingJob> queue = workerQueues.remove(workerAddress);
    if (queue != null) {
      List<PendingJob> abandonedJobs = new ArrayList<>();
      queue.drainTo(abandonedJobs);
      for (PendingJob job : abandonedJobs) {
        job.future.completeExceptionally(
            new RuntimeException("Worker died before processing batch."));
      }
    }
  }

  /**
   * Continuous dispatch loop executed on a dedicated thread for each active worker node.
   * Drains pending requests from the queue using a hybrid algorithm:
   * 1. Blocks on {@code take()} until at least one request arrives.
   * 2. Polls for additional requests until reaching {@code BATCH_SIZE} or exceeding {@code MAX_WAIT_MS}.
   * 3. Dispatches aggregated batch asynchronously via gRPC.
   *
   * @param workerAddress target worker IP address
   * @param queue blocking queue containing pending jobs for this worker
   */
  private void startLoopForWorker(String workerAddress, BlockingQueue<PendingJob> queue) {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        List<PendingJob> batch = new ArrayList<>();
        batch.add(queue.take()); // Blocks until at least one job is available

        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        while (batch.size() < BATCH_SIZE) {
          long remaining = deadline - System.currentTimeMillis();
          if (remaining <= 0) break;

          PendingJob next = queue.poll(remaining, TimeUnit.MILLISECONDS);
          if (next == null) break;
          batch.add(next);
        }

        if (!batch.isEmpty()) {
          processBatchAsync(workerAddress, batch);
        }

      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  /**
   * Asynchronously dispatches a bundled batch of inference requests to the worker via non-blocking gRPC stub.
   * Maps incoming batch response items back to individual job futures by request ID upon completion.
   *
   * @param workerAddress worker node IP address
   * @param jobs list of PendingJob items included in this batch execution
   */
  private void processBatchAsync(String workerAddress, List<PendingJob> jobs) {
    try {
      BatchInferenceRequest.Builder requestBuilder =
          BatchInferenceRequest.newBuilder().setBatchId("batch-" + System.currentTimeMillis());

      for (PendingJob job : jobs) {
        requestBuilder.addRequests(job.request);
      }

      InferenceServiceGrpc.InferenceServiceStub asyncStub =
          clusterClient.getAsyncStub(workerAddress).withWaitForReady();

      asyncStub.predictBatch(
          requestBuilder.build(),
          new StreamObserver<BatchInferenceResponse>() {
            @Override
            public void onNext(BatchInferenceResponse response) {
              Map<String, InferenceResponse> responseMap = new ConcurrentHashMap<>();
              for (InferenceResponse resp : response.getResponsesList()) {
                responseMap.put(resp.getRequestId(), resp);
              }

              for (PendingJob job : jobs) {
                InferenceResponse res = responseMap.get(job.request.getRequestId());
                if (res != null) {
                  job.future.complete(res);
                } else {
                  job.future.completeExceptionally(
                      new RuntimeException("Job missing from batch response"));
                }
              }
            }

            @Override
            public void onError(Throwable t) {
              System.err.println(
                  "[BatchScheduler] Async Batch failed for "
                      + workerAddress
                      + ": "
                      + t.getMessage());
              for (PendingJob job : jobs) {
                job.future.completeExceptionally(t);
              }
            }

            @Override
            public void onCompleted() {
              // gRPC response stream completed successfully
            }
          });

    } catch (Exception e) {
      System.err.println("[BatchScheduler] Failed to dispatch async batch: " + e.getMessage());
      for (PendingJob job : jobs) {
        job.future.completeExceptionally(e);
      }
    }
  }

  /**
   * Internal data structure holding an individual request payload and its associated completable future.
   */
  private static class PendingJob {
    InferenceRequest request;
    CompletableFuture<InferenceResponse> future;

    public PendingJob(InferenceRequest request, CompletableFuture<InferenceResponse> future) {
      this.request = request;
      this.future = future;
    }
  }
}
