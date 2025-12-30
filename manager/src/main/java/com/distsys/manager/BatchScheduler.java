package com.distsys.manager;

import inference.BatchInferenceRequest;
import inference.BatchInferenceResponse;
import inference.InferenceRequest;
import inference.InferenceResponse;
import inference.InferenceServiceGrpc;
import io.grpc.ManagedChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class BatchScheduler {
    private static final int BATCH_SIZE = 16;
    private static final int MAX_WAIT_MS = 50;

    private final BlockingQueue<PendingJob> queue = new LinkedBlockingQueue<>();
    private final ClusterClient clusterClient;
    private final ExecutorService senderThread = Executors.newSingleThreadExecutor();

    public BatchScheduler(ClusterClient clusterClient) {
        this.clusterClient = clusterClient;
        startLoop();
    }

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
                    batch.add(queue.take()); 
                    
                    long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
                    while (batch.size() < BATCH_SIZE) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) break;
                        
                        PendingJob next = queue.poll(remaining, TimeUnit.MILLISECONDS);
                        if (next == null) break;
                        batch.add(next);
                    }

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
            BatchInferenceRequest.Builder requestBuilder = BatchInferenceRequest.newBuilder()
                    .setBatchId("batch-" + System.currentTimeMillis());

            for (PendingJob job : jobs) {
                requestBuilder.addRequests(job.request);
            }

            InferenceServiceGrpc.InferenceServiceBlockingStub stub = 
                clusterClient.getStubForJob(requestBuilder.getBatchId(), 0);

            BatchInferenceResponse response = stub.predictBatch(requestBuilder.build());

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
}