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

public class BatchScheduler {
  private static final int BATCH_SIZE = 2;
  private static final int MAX_WAIT_MS = 50;

  private final ConcurrentHashMap<String, BlockingQueue<PendingJob>> workerQueues =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, ExecutorService> workerThreads =
      new ConcurrentHashMap<>();

  private final ClusterClient clusterClient;

  public BatchScheduler(ClusterClient clusterClient) {
    this.clusterClient = clusterClient;
  }

  public int getQueueDepth(String workerAddress) {
    BlockingQueue<PendingJob> queue = workerQueues.get(workerAddress);
    return queue != null ? queue.size() : 0;
  }

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

  // NEW: Graceful cleanup to prevent OutOfMemory crashes when workers die
  public void removeWorker(String workerAddress) {
    System.out.println("[BatchScheduler] Cleaning up resources for dead worker: " + workerAddress);

    ExecutorService executor = workerThreads.remove(workerAddress);
    if (executor != null) {
      executor.shutdownNow(); // Stop the loop
    }

    BlockingQueue<PendingJob> queue = workerQueues.remove(workerAddress);
    if (queue != null) {
      // Fail all pending jobs so clients aren't left hanging indefinitely
      List<PendingJob> abandonedJobs = new ArrayList<>();
      queue.drainTo(abandonedJobs);
      for (PendingJob job : abandonedJobs) {
        job.future.completeExceptionally(
            new RuntimeException("Worker died before processing batch."));
      }
    }
  }

  private void startLoopForWorker(String workerAddress, BlockingQueue<PendingJob> queue) {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        List<PendingJob> batch = new ArrayList<>();
        batch.add(queue.take()); // Blocks until at least one job arrives

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
        break; // Exit the loop safely on shutdown
      }
    }
  }

  // NEW: Completely non-blocking asynchronous gRPC call
  private void processBatchAsync(String workerAddress, List<PendingJob> jobs) {
    try {
      BatchInferenceRequest.Builder requestBuilder =
          BatchInferenceRequest.newBuilder().setBatchId("batch-" + System.currentTimeMillis());

      for (PendingJob job : jobs) {
        requestBuilder.addRequests(job.request);
      }

      // Grab the ASYNC stub
      InferenceServiceGrpc.InferenceServiceStub asyncStub =
          clusterClient.getAsyncStub(workerAddress).withWaitForReady();

      // Send the request and register a callback, freeing up the thread immediately!
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
              // gRPC stream closed successfully
            }
          });

    } catch (Exception e) {
      System.err.println("[BatchScheduler] Failed to dispatch async batch: " + e.getMessage());
      for (PendingJob job : jobs) {
        job.future.completeExceptionally(e);
      }
    }
  }

  private static class PendingJob {
    InferenceRequest request;
    CompletableFuture<InferenceResponse> future;

    public PendingJob(InferenceRequest request, CompletableFuture<InferenceResponse> future) {
      this.request = request;
      this.future = future;
    }
  }

  // NEW: Directly submit a pre-packaged batch from the UI, bypassing the queue
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
              // gRPC stream closed
            }
          });

    } catch (Exception e) {
      future.completeExceptionally(e);
    }

    return future;
  }
}
