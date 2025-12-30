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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class HttpJobHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static BatchScheduler scheduler;
    private final ChannelGroup activeWebSockets;
    private final ClusterClient clusterClient;

    public HttpJobHandler(ClusterClient clusterClient, ChannelGroup activeWebSockets) {
        this.clusterClient = clusterClient;
        this.activeWebSockets = activeWebSockets;
        
        synchronized (HttpJobHandler.class) {
            if (scheduler == null) {
                System.out.println("[Manager] Initializing Smart Batch Scheduler...");
                scheduler = new BatchScheduler(clusterClient);
            }
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        if (req.uri().equals("/ws")) {
            ctx.fireChannelRead(req.retain());
            return;
        }

        if (req.method().equals(HttpMethod.OPTIONS)) {
            sendCorsResponse(ctx);
            return;
        }

        if (req.method().equals(HttpMethod.POST) && req.uri().equals("/api/job")) {
            handleJobSubmission(ctx, req);
        } else {
            ctx.writeAndFlush(new DefaultFullHttpResponse(req.protocolVersion(), HttpResponseStatus.NOT_FOUND));
        }
    }

    private void handleJobSubmission(ChannelHandlerContext ctx, FullHttpRequest req) {
        long startTime = System.currentTimeMillis();
        String jobId = "job-" + startTime;

        try {
            ByteBuf content = req.content();
            byte[] imageBytes = new byte[content.readableBytes()];
            content.readBytes(imageBytes);

            InferenceRequest grpcReq = InferenceRequest.newBuilder()
                    .setRequestId(jobId)
                    .setImageData(com.google.protobuf.ByteString.copyFrom(imageBytes))
                    .build();

            Future<InferenceResponse> future = scheduler.submit(grpcReq);
            InferenceResponse grpcResp = future.get(5, TimeUnit.SECONDS);

            if (activeWebSockets != null) {
                String logMessage = String.format("[System] Job %s Completed via Batching in %dms (Conf: %.2f%%)",
                        jobId, grpcResp.getExecutionTimeMs(), grpcResp.getConfidenceScore() * 100);
                activeWebSockets.writeAndFlush(new TextWebSocketFrame(logMessage));
            }

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