package com.distsys.manager;

import inference.Inference.InferenceRequest;
import inference.Inference.InferenceResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.CharsetUtil;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class HttpJobHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    // Singleton Scheduler (Lazy loaded) to manage the batching queue across all connections
    private static BatchScheduler scheduler;
    private final ChannelGroup activeWebSockets;
    private final ClusterClient clusterClient;

    public HttpJobHandler(ClusterClient clusterClient, ChannelGroup activeWebSockets) {
        this.clusterClient = clusterClient;
        this.activeWebSockets = activeWebSockets;
        
        // Initialize the scheduler if it doesn't exist yet
        synchronized (HttpJobHandler.class) {
            if (scheduler == null) {
                System.out.println("[Manager] Initializing Smart Batch Scheduler...");
                scheduler = new BatchScheduler(clusterClient);
            }
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        // 1. Pass WebSocket Handshakes
        if (req.uri().equals("/ws")) {
            ctx.fireChannelRead(req.retain());
            return;
        }

        // 2. Handle CORS
        if (req.method().equals(HttpMethod.OPTIONS)) {
            sendCorsResponse(ctx);
            return;
        }

        // 3. Handle Job Submission (POST /api/job)
        if (req.method().equals(HttpMethod.POST) && req.uri().equals("/api/job")) {
            handleJobSubmission(ctx, req);
        } else {
            // 404 for everything else
            ctx.writeAndFlush(new DefaultFullHttpResponse(req.protocolVersion(), HttpResponseStatus.NOT_FOUND));
        }
    }

    private void handleJobSubmission(ChannelHandlerContext ctx, FullHttpRequest req) {
        long startTime = System.currentTimeMillis();
        String jobId = "job-" + startTime;

        try {
            // A. Read the raw image bytes
            ByteBuf content = req.content();
            byte[] imageBytes = new byte[content.readableBytes()];
            content.readBytes(imageBytes);

            // B. Create the Proto Request
            InferenceRequest grpcReq = InferenceRequest.newBuilder()
                    .setRequestId(jobId)
                    .setImageData(com.google.protobuf.ByteString.copyFrom(imageBytes))
                    .build();

            // C. Submit to Batch Scheduler (Async)
            // This puts the job in the queue. The Scheduler will pack it and send it when ready.
            Future<InferenceResponse> future = scheduler.submit(grpcReq);

            // D. Wait for the result (Blocking this Netty thread briefly)
            // In a production non-blocking system, you would use listeners, but this is fine for now.
            InferenceResponse grpcResp = future.get(5, TimeUnit.SECONDS);

            // E. Broadcast to Dashboard
            if (activeWebSockets != null) {
                String logMessage = String.format("[System] Job %s Completed via Batching in %dms (Conf: %.2f%%)",
                        jobId, grpcResp.getExecutionTimeMs(), grpcResp.getConfidenceScore() * 100);
                activeWebSockets.writeAndFlush(new TextWebSocketFrame(logMessage));
            }

            // F. Send HTTP Response
            String resultJson = String.format("{\"label\": \"%s\", \"confidence\": %.2f}",
                    grpcResp.getClassLabel(), grpcResp.getConfidenceScore());
            
            sendJson(ctx, resultJson, HttpResponseStatus.OK);

        } catch (TimeoutException e) {
            System.err.println("[Manager] Job " + jobId + " timed out waiting for batch execution.");
            sendJson(ctx, "{\"error\": \"Request Timed Out\"}", HttpResponseStatus.REQUEST_TIMEOUT);
        } catch (InterruptedException | ExecutionException e) {
            System.err.println("[Manager] Job " + jobId + " failed: " + e.getMessage());
            sendJson(ctx, "{\"error\": \"Inference Failed\"}", HttpResponseStatus.INTERNAL_SERVER_ERROR);
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ctx, "{\"error\": \"Bad Request\"}", HttpResponseStatus.BAD_REQUEST);
        }
    }

    private void sendJson(ChannelHandlerContext ctx, String json, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(json, CharsetUtil.UTF_8));

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        enableCors(response);
        ctx.writeAndFlush(response);
    }

    private void sendCorsResponse(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        enableCors(response);
        ctx.writeAndFlush(response);
    }

    private void enableCors(FullHttpResponse response) {
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
    }
}