package com.distsys.manager;

import inference.Inference.BatchInferenceRequest;
import inference.Inference.BatchInferenceResponse;
import inference.Inference.InferenceRequest;
import inference.Inference.InferenceResponse;
import inference.InferenceServiceGrpc;
import io.grpc.ManagedChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class BatchScheduler {
    private static final int BATCH_SIZE = 16;
    private static final int MAX_WAIT_MS = 50; // Wait max 50ms to fill a batch

    // Queue holds the Request Data AND a Future to complete when result arrives
    private final BlockingQueue<PendingJob> queue = new LinkedBlockingQueue<>();
    private final ClusterClient clusterClient;
    private final ExecutorService senderThread = Executors.newSingleThreadExecutor();

    public BatchScheduler(ClusterClient clusterClient) {
        this.clusterClient = clusterClient;
        startLoop();
    }

    // Called by HttpJobHandler
    public Future<InferenceResponse> submit(InferenceRequest req) {
        CompletableFuture<InferenceResponse> future = new CompletableFuture<>();
        queue.add(new PendingJob(req, future));
        return future;
    }

    private void startLoop() {
        senderThread.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    List<PendingJob> batch = new ArrayList<>();
                    
                    // 1. Block until at least one job is available
                    batch.add(queue.take()); 
                    
                    // 2. Drain up to BATCH_SIZE - 1 more items, waiting a bit if needed
                    long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
                    while (batch.size() < BATCH_SIZE) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) break;
                        
                        PendingJob next = queue.poll(remaining, TimeUnit.MILLISECONDS);
                        if (next == null) break;
                        batch.add(next);
                    }

                    // 3. Process the batch
                    if (!batch.isEmpty()) {
                        processBatch(batch);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private void processBatch(List<PendingJob> jobs) {
        try {
            // Convert to Proto Batch
            BatchInferenceRequest.Builder requestBuilder = BatchInferenceRequest.newBuilder()
                    .setBatchId("batch-" + System.currentTimeMillis());

            for (PendingJob job : jobs) {
                requestBuilder.addRequests(job.request);
            }

            // Send via gRPC (Pick a node for the whole batch)
            // Note: For simplicity, we send the whole batch to ONE worker.
            InferenceServiceGrpc.InferenceServiceBlockingStub stub = 
                clusterClient.getStubForJob(requestBuilder.getBatchId(), 0);

            BatchInferenceResponse response = stub.predictBatch(requestBuilder.build());

            // Map results back to Futures
            Map<String, InferenceResponse> responseMap = new ConcurrentHashMap<>();
            for (InferenceResponse resp : response.getResponsesList()) {
                responseMap.put(resp.getRequestId(), resp);
            }

            for (PendingJob job : jobs) {
                InferenceResponse res = responseMap.get(job.request.getRequestId());
                if (res != null) {
                    job.future.complete(res);
                } else {
                    job.future.completeExceptionally(new RuntimeException("Batch incomplete"));
                }
            }

        } catch (Exception e) {
            // Fail all jobs in this batch so clients don't hang
            for (PendingJob job : jobs) {
                job.future.completeExceptionally(e);
            }
        }
    }

    // Helper Class
    private static class PendingJob {
        InferenceRequest request;
        CompletableFuture<InferenceResponse> future;

        public PendingJob(InferenceRequest request, CompletableFuture<InferenceResponse> future) {
            this.request = request;
            this.future = future;
        }
    }
}
