package com.distsys.manager;

import inference.InferenceRequest;
import inference.InferenceResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.CharsetUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Netty inbound channel handler for decoding HTTP REST API inference requests.
 * <p>
 * Routes HTTP endpoints:
 * <ul>
 *   <li>{@code POST /api/job}: Accepts raw binary image byte stream payloads (application/octet-stream).</li>
 *   <li>{@code POST /api/batch}: Accepts multipart/form-data multi-image batch submissions.</li>
 *   <li>{@code OPTIONS}: Responds with HTTP CORS headers for preflight browser checks.</li>
 *   <li>{@code /ws}: Passes WebSocket handshake frames down the Netty pipeline.</li>
 * </ul>
 * Upon completing inference, updates are dispatched back to HTTP clients and broadcasted via WebSocket
 * onto the Netty I/O event loop thread pool.
 * </p>
 */
public class HttpJobHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

  private final ClusterClient clusterClient;
  private final BatchScheduler scheduler;
  private final ChannelGroup activeWebSockets;

  /**
   * Constructs a new HttpJobHandler bound to cluster dependencies and WebSocket connection pool.
   *
   * @param clusterClient gRPC cluster client connection manager
   * @param scheduler dynamic batch scheduler instance
   * @param activeWebSockets group of connected WebSocket channel instances for telemetry broadcast
   */
  public HttpJobHandler(
      ClusterClient clusterClient, BatchScheduler scheduler, ChannelGroup activeWebSockets) {
    this.clusterClient = clusterClient;
    this.scheduler = scheduler;
    this.activeWebSockets = activeWebSockets;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
    // Forward WebSocket endpoint requests down the pipeline for WebSocketServerProtocolHandler
    if (req.uri().startsWith("/ws")) {
      ctx.fireChannelRead(req.retain());
      return;
    }

    // Intercept CORS preflight OPTIONS requests from browser clients
    if (req.method().equals(HttpMethod.OPTIONS)) {
      sendCorsResponse(ctx);
      return;
    }

    // Route POST requests to designated handler methods
    if (req.method().equals(HttpMethod.POST) && req.uri().equals("/api/job")) {
      handleJobSubmission(ctx, req);
    } else if (req.method().equals(HttpMethod.POST) && req.uri().equals("/api/batch")) {
      handleBatchSubmission(ctx, req);
    } else {
      sendResponse(ctx, "", HttpResponseStatus.NOT_FOUND);
    }
  }

  /**
   * Processes single image payload submissions at POST /api/job.
   * Reads raw bytes, constructs a gRPC InferenceRequest, resolves worker IP via consistent hashing,
   * submits request to BatchScheduler, and enforces a 30-second execution SLA timeout.
   *
   * @param ctx Netty channel context for sending response
   * @param req incoming full HTTP request object
   */
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
                    sendResponse(
                        ctx,
                        "{\"error\": \"Request Timed Out\"}",
                        HttpResponseStatus.REQUEST_TIMEOUT);
                  } else {
                    System.err.println(
                        "[Manager] Job " + jobId + " failed: " + exception.getMessage());
                    sendResponse(
                        ctx,
                        "{\"error\": \"Inference Failed\"}",
                        HttpResponseStatus.INTERNAL_SERVER_ERROR);
                  }
                  return;
                }

                // Dispatch HTTP response and WebSocket event on Netty event loop thread for thread safety
                ctx.executor()
                    .execute(
                        () -> {
                          if (activeWebSockets != null) {
                            int queueDepth = scheduler.getQueueDepth(workerIp);

                            String wsJson =
                                String.format(
                                    java.util.Locale.US,
                                    "{\"type\": \"inference_result\", \"jobId\": \"%s\","
                                        + " \"workerNode\": \"%s\", \"queueDepth\": %d, \"label\":"
                                        + " \"%s\", \"latencyMs\": %d, \"confidence\": %.2f}",
                                    jobId,
                                    workerIp,
                                    queueDepth,
                                    grpcResp.getClassLabel(),
                                    grpcResp.getExecutionTimeMs(),
                                    grpcResp.getConfidenceScore() * 100);

                            activeWebSockets.writeAndFlush(new TextWebSocketFrame(wsJson));
                          }

                          String resultJson =
                              String.format(
                                  java.util.Locale.US,
                                  "{\"label\": \"%s\", \"confidence\": %.2f}",
                                  grpcResp.getClassLabel(),
                                  grpcResp.getConfidenceScore());

                          sendResponse(ctx, resultJson, HttpResponseStatus.OK);
                        });
              });

    } catch (Exception e) {
      e.printStackTrace();
      sendResponse(ctx, "{\"error\": \"Bad Request\"}", HttpResponseStatus.BAD_REQUEST);
    }
  }

  /**
   * Encodes and writes formatted JSON HTTP responses to the Netty channel pipeline.
   *
   * @param ctx Netty channel handler context
   * @param json response payload body string
   * @param status HTTP response status code
   */
  private void sendResponse(ChannelHandlerContext ctx, String json, HttpResponseStatus status) {
    FullHttpResponse response =
        new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8));

    response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
    enableCors(response);
    ctx.writeAndFlush(response);
  }

  /**
   * Handles HTTP OPTIONS preflight request by returning empty 200 OK response with CORS headers.
   *
   * @param ctx Netty channel context
   */
  private void sendCorsResponse(ChannelHandlerContext ctx) {
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    enableCors(response);

    response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

    ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
  }

  /**
   * Appends Cross-Origin Resource Sharing (CORS) headers to outgoing HTTP responses.
   *
   * @param response Netty FullHttpResponse object
   */
  private void enableCors(FullHttpResponse response) {
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
    response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
  }

  /**
   * Processes multipart/form-data batch image submissions at POST /api/batch.
   * Uses Netty {@link HttpPostRequestDecoder} to extract file uploads, builds a BatchInferenceRequest,
   * routes to a C++ worker node, and enforces a 60-second SLA timeout.
   *
   * @param ctx Netty channel context
   * @param req incoming full HTTP request object containing multipart form body
   */
  private void handleBatchSubmission(ChannelHandlerContext ctx, FullHttpRequest req) {
    String batchId = "batch-" + java.util.UUID.randomUUID().toString();
    inference.BatchInferenceRequest.Builder batchBuilder =
        inference.BatchInferenceRequest.newBuilder().setBatchId(batchId);

    try {
      HttpPostRequestDecoder decoder =
          new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), req);

      for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
        if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
          FileUpload fileUpload = (FileUpload) data;
          if (fileUpload.isCompleted()) {
            String frontendImageId = fileUpload.getName();

            inference.InferenceRequest singleReq =
                inference.InferenceRequest.newBuilder()
                    .setRequestId(frontendImageId)
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

      CompletableFuture<inference.BatchInferenceResponse> future =
          (CompletableFuture<inference.BatchInferenceResponse>)
              scheduler.submitBatch(workerIp, batchBuilder.build());

      future
          .orTimeout(60, TimeUnit.SECONDS)
          .whenComplete(
              (grpcResp, exception) -> {
                ctx.executor()
                    .execute(
                        () -> {
                          if (exception != null) {
                            sendResponse(
                                ctx,
                                "{\"error\": \"Batch Failed\"}",
                                HttpResponseStatus.INTERNAL_SERVER_ERROR);
                            return;
                          }

                          int queueDepth = scheduler.getQueueDepth(workerIp);
                          StringBuilder jsonBuilder = new StringBuilder("[");

                          for (int i = 0; i < grpcResp.getResponsesCount(); i++) {
                            inference.InferenceResponse r = grpcResp.getResponses(i);

                            if (activeWebSockets != null) {
                              String wsJson =
                                  String.format(
                                      java.util.Locale.US,
                                      "{\"type\": \"inference_result\", \"jobId\": \"%s\","
                                          + " \"workerNode\": \"%s\", \"queueDepth\": %d,"
                                          + " \"label\": \"%s\", \"latencyMs\": %d, \"confidence\":"
                                          + " %.2f}",
                                      r.getRequestId(),
                                      workerIp,
                                      queueDepth,
                                      r.getClassLabel(),
                                      r.getExecutionTimeMs(),
                                      r.getConfidenceScore() * 100);
                              activeWebSockets.writeAndFlush(new TextWebSocketFrame(wsJson));
                            }

                            jsonBuilder.append(
                                String.format(
                                    java.util.Locale.US,
                                    "{\"id\": \"%s\", \"label\": \"%s\", \"confidence\": %.2f}",
                                    r.getRequestId(),
                                    r.getClassLabel(),
                                    r.getConfidenceScore()));

                            if (i < grpcResp.getResponsesCount() - 1) jsonBuilder.append(",");
                          }
                          jsonBuilder.append("]");

                          sendResponse(ctx, jsonBuilder.toString(), HttpResponseStatus.OK);
                        });
              });

    } catch (Exception e) {
      e.printStackTrace();
      sendResponse(ctx, "{\"error\": \"Bad Batch Request\"}", HttpResponseStatus.BAD_REQUEST);
    }
  }
}
