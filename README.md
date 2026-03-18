# Inferenciate: Distributed High-Throughput Inference Engine

## Overview
Inferenciate is a high-performance, distributed machine learning inference system designed for real-time image processing. It decouples the client-facing API from the heavy machine learning workloads, utilizing a custom Java-based Netty API Gateway that routes traffic to an elastic cluster of C++ worker nodes via gRPC. 

The system implements "True Tensor Batching," aggregating continuous HTTP/multipart data streams into contiguous memory blocks for optimal ONNX runtime execution, yielding significant throughput improvements over standard sequential inference loops.

## System Architecture

The architecture is divided into three highly specialized components:

1. **Dashboard (Frontend)**
   * Built with React, TypeScript, and Vite.
   * Maintains a persistent WebSocket connection to the Manager node.
   * Provides real-time cluster telemetry, live topology mapping, and asynchronous batch submission interfaces.

2. **Manager Node (Backend / API Gateway)**
   * Built with Java 17+ and Netty.
   * Operates as a non-blocking asynchronous event loop.
   * Implements a Consistent Hashing Router to distribute inference jobs across virtual nodes.
   * Features a custom Batch Scheduler that aggregates HTTP payloads and multiplexes them over persistent gRPC channels.
   * Broadcasts sub-millisecond telemetry to the frontend via WebSockets.

3. **Worker Node (Inference Engine)**
   * Built with C++17, gRPC, and the ONNX Runtime C++ API.
   * Performs zero-copy layout transformations, converting raw image byte arrays into NCHW tensor formats.
   * Executes hardware-accelerated matrix multiplication on aggregated batch tensors.

## Core Technologies
* **Frontend:** React, TypeScript, Vite, TailwindCSS
* **Backend:** Java, Netty, gRPC, Protocol Buffers
* **Inference:** C++, ONNX Runtime, STB Image
* **Infrastructure:** Docker, Docker Compose

## Key Features
* **True Tensor Batching:** Bypasses sequential processing by mapping multiple image payloads into a single multi-dimensional tensor, executing inference exactly once per batch.
* **Consistent Hashing Load Balancing:** Ensures deterministic routing of jobs across the worker cluster, minimizing cache misses and preventing hot-spotting.
* **Non-Blocking Telemetry:** Fully asynchronous WebSocket broadcasting updates the UI with latency, queue depth, and confidence scores without locking the HTTP routing threads.
* **Elastic Topology:** Worker nodes can be attached or detached dynamically. The Manager automatically rebalances the hash ring and updates the frontend topology map in real-time.

## Prerequisites
* Docker and Docker Compose
* Minimum 4GB RAM available for the Docker Daemon (to support ONNX memory allocation)

## Installation & Deployment

1. **Clone the repository:**
   ```bash
   git clone https://github.com/yourusername/inferenciate.git
   cd inferenciate
   ```

2. **Provision the cluster:**
   The entire stack is containerized. To build the images and spin up the Manager, Dashboard, and one Worker node, execute:
   ```bash
   docker-compose up --build -d
   ```

3. **Horizontal Scaling:**
   To dynamically add more C++ inference workers to the cluster, scale the worker service:
   ```bash
   docker-compose up -d --scale worker=3
   ```

## Usage
Once the cluster is online, navigate to `http://localhost:5173` to access the Dashboard. 

* **Submit Jobs:** Use the drag-and-drop interface to upload batches of images. 
* **Monitor Telemetry:** The system monitor will display real-time latency and queue depth metrics broadcasted directly from the Netty event loop.
* **Observe Topology:** Scaling the worker instances via Docker Compose will instantly reflect on the Cluster Topology map without requiring a page refresh.

## API Reference

### HTTP Endpoints
* `POST /api/job`: Submits a single image payload (`application/octet-stream`).
* `POST /api/batch`: Submits a high-throughput batch of images (`multipart/form-data`).

### WebSocket Events
* `ws://<MANAGER_HOST>:8080/ws`
* Consumes incoming JSON payloads dictating `topology_update` and `inference_result` event types.
