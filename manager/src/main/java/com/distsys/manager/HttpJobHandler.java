package com.distsys.manager;

import inference.InferenceRequest;
import inference.InferenceResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.CharsetUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;

public class HttpJobHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

  private final ClusterClient clusterClient;
  private final BatchScheduler scheduler;
  private final ChannelGroup activeWebSockets;

  // UPDATED: Proper dependency injection, no static singletons!
  public HttpJobHandler(
      ClusterClient clusterClient, BatchScheduler scheduler, ChannelGroup activeWebSockets) {
    this.clusterClient = clusterClient;
    this.scheduler = scheduler;
    this.activeWebSockets = activeWebSockets;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
    if (req.uri().startsWith("/ws")) {
      ctx.fireChannelRead(req.retain());
      return;
    }

    if (req.method().equals(HttpMethod.OPTIONS)) {
      sendCorsResponse(ctx);
      return;
    }

    if (req.method().equals(HttpMethod.POST) && req.uri().equals("/api/job")) {
      handleJobSubmission(ctx, req);
    } else if (req.method().equals(HttpMethod.POST) && req.uri().equals("/api/batch")) {
      // NEW: Route to the batch handler
      handleBatchSubmission(ctx, req);
    } else {
      sendResponse(ctx, "", HttpResponseStatus.NOT_FOUND);
    }
  }

  private void handleJobSubmission(ChannelHandlerContext ctx, FullHttpRequest req) {
    long startTime = System.currentTimeMillis();
    String jobId = "job-" + java.util.UUID.randomUUID().toString();

    try {
      ByteBuf content = req.content();
      byte[] imageBytes = new byte[content.readableBytes()];
      content.readBytes(imageBytes);

      InferenceRequest grpcReq =
          InferenceRequest.newBuilder()
              .setRequestId(jobId)
              .setImageData(com.google.protobuf.ByteString.copyFrom(imageBytes))
              .build();

      String workerIp = clusterClient.getWorkerIp(jobId);

      CompletableFuture<InferenceResponse> future =
          (CompletableFuture<InferenceResponse>) scheduler.submit(workerIp, grpcReq);

      future
          .orTimeout(30, TimeUnit.SECONDS)
          .whenComplete(
              (grpcResp, exception) -> {
                if (exception != null) {
                  if (exception instanceof TimeoutException) {
                    System.err.println("[Manager] Job " + jobId + " timed out.");
                    sendResponse(ctx, "{\"error\": \"Request Timed Out\"}", HttpResponseStatus.REQUEST_TIMEOUT);
                  } else {
                    System.err.println("[Manager] Job " + jobId + " failed: " + exception.getMessage());
                    sendResponse(ctx, "{\"error\": \"Inference Failed\"}", HttpResponseStatus.INTERNAL_SERVER_ERROR);
                  }
                  return;
                }

                // FIX #4: Shift the response writing back to Netty's thread
                ctx.executor().execute(() -> {
                  if (activeWebSockets != null) {
                    int queueDepth = scheduler.getQueueDepth(workerIp);
                    
                    // FIX #1: Added java.util.Locale.US to prevent comma-decimal JSON crashes
                    String wsJson = String.format(java.util.Locale.US,
                        "{\"type\": \"inference_result\", \"jobId\": \"%s\", \"workerNode\": \"%s\", \"queueDepth\": %d, \"label\": \"%s\", \"latencyMs\": %d, \"confidence\": %.2f}",
                        jobId, workerIp, queueDepth, grpcResp.getClassLabel(), grpcResp.getExecutionTimeMs(), grpcResp.getConfidenceScore() * 100);
                        
                    activeWebSockets.writeAndFlush(new TextWebSocketFrame(wsJson));
                  }

                  // FIX #1: Added java.util.Locale.US here as well
                  String resultJson = String.format(java.util.Locale.US,
                      "{\"label\": \"%s\", \"confidence\": %.2f}",
                      grpcResp.getClassLabel(), grpcResp.getConfidenceScore());

                  sendResponse(ctx, resultJson, HttpResponseStatus.OK);
                });
              });

    } catch (Exception e) {
      e.printStackTrace();
      sendResponse(ctx, "{\"error\": \"Bad Request\"}", HttpResponseStatus.BAD_REQUEST);
    }
  }

  private void sendResponse(ChannelHandlerContext ctx, String json, HttpResponseStatus status) {
    FullHttpResponse response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8));

    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
    enableCors(response);
    ctx.writeAndFlush(response);
  }

  private void sendCorsResponse(ChannelHandlerContext ctx) {
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    enableCors(response);

    // NEW: Tell the browser the response is complete!
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

    // Flush and cleanly close the preflight channel
    ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
  }

  private void enableCors(FullHttpResponse response) {
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
  }

  private void handleBatchSubmission(ChannelHandlerContext ctx, FullHttpRequest req) {
    String batchId = "batch-" + java.util.UUID.randomUUID().toString();
    inference.BatchInferenceRequest.Builder batchBuilder = inference.BatchInferenceRequest.newBuilder().setBatchId(batchId);

    try {
      // Initialize Netty's multipart decoder
      HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), req);

      for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
        if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
          FileUpload fileUpload = (FileUpload) data;
          if (fileUpload.isCompleted()) {
            
            // The frontend attached the React item.id as the field name!
            String frontendImageId = fileUpload.getName(); 
            
            inference.InferenceRequest singleReq = inference.InferenceRequest.newBuilder()
                .setRequestId(frontendImageId) // Crucial for mapping results back to the UI
                .setImageData(com.google.protobuf.ByteString.copyFrom(fileUpload.get()))
                .build();
                
            batchBuilder.addRequests(singleReq);
          }
        }
      }
      decoder.destroy();

      if (batchBuilder.getRequestsCount() == 0) {
         sendResponse(ctx, "{\"error\": \"Empty batch\"}", HttpResponseStatus.BAD_REQUEST);
         return;
      }

      String workerIp = clusterClient.getWorkerIp(batchId);
      
      // IMPORTANT: You will need to make sure BatchScheduler has a submitBatch() method!
      CompletableFuture<inference.BatchInferenceResponse> future = 
          (CompletableFuture<inference.BatchInferenceResponse>) scheduler.submitBatch(workerIp, batchBuilder.build());

      future.orTimeout(60, TimeUnit.SECONDS).whenComplete((grpcResp, exception) -> {
        ctx.executor().execute(() -> {
          if (exception != null) {
            sendResponse(ctx, "{\"error\": \"Batch Failed\"}", HttpResponseStatus.INTERNAL_SERVER_ERROR);
            return;
          }

          // 1. Get current queue depth for the telemetry
          int queueDepth = scheduler.getQueueDepth(workerIp);

          // 2. Build the HTTP JSON response AND broadcast to WebSockets simultaneously
          StringBuilder jsonBuilder = new StringBuilder("[");
          
          for (int i = 0; i < grpcResp.getResponsesCount(); i++) {
            inference.InferenceResponse r = grpcResp.getResponses(i);
            
            // --- NEW: Broadcast each result to the React Dashboard via WebSocket ---
            if (activeWebSockets != null) {
              String wsJson = String.format(java.util.Locale.US,
                  "{\"type\": \"inference_result\", \"jobId\": \"%s\", \"workerNode\": \"%s\", \"queueDepth\": %d, \"label\": \"%s\", \"latencyMs\": %d, \"confidence\": %.2f}",
                  r.getRequestId(), workerIp, queueDepth, r.getClassLabel(), r.getExecutionTimeMs(), r.getConfidenceScore() * 100);
              activeWebSockets.writeAndFlush(new TextWebSocketFrame(wsJson));
            }
            // -----------------------------------------------------------------------

            // Append to the HTTP response
            jsonBuilder.append(String.format(java.util.Locale.US,
                "{\"id\": \"%s\", \"label\": \"%s\", \"confidence\": %.2f}",
                r.getRequestId(), r.getClassLabel(), r.getConfidenceScore()));
            
            if (i < grpcResp.getResponsesCount() - 1) jsonBuilder.append(",");
          }
          jsonBuilder.append("]");

          // 3. Send the final HTTP response to the Image Uploader
          sendResponse(ctx, jsonBuilder.toString(), HttpResponseStatus.OK);
        });
      });

    } catch (Exception e) {
      e.printStackTrace();
      sendResponse(ctx, "{\"error\": \"Bad Batch Request\"}", HttpResponseStatus.BAD_REQUEST);
    }
  }
}
