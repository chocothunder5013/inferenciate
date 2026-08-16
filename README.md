# Inferenciate

Inferenciate is a distributed, high-throughput machine learning inference system engineered for low-latency, real-time computer vision processing. It decouples asynchronous client API management from compute-heavy tensor inference by pairing a non-blocking Java (Netty) API Gateway with a high-performance cluster of native C++ (ONNX Runtime) worker nodes over gRPC.

---

## Documentation Index

Comprehensive technical documentation is organized in the [`docs/`](file:///home/ishangupta/inferenciate/docs) directory:

* [System Architecture Specification](file:///home/ishangupta/inferenciate/docs/ARCHITECTURE.md): Complete architecture overview, component decoupling, sequence diagrams, and end-to-end data pipelines.
* [Dynamic Batch Scheduling Specification](file:///home/ishangupta/inferenciate/docs/DYNAMIC_BATCHING.md): Deep dive into time-bounded queue draining, SLA latency windows, and thread concurrency models.
* [Consistent Hashing & Cluster Routing Specification](file:///home/ishangupta/inferenciate/docs/CONSISTENT_HASHING.md): 32-bit MD5 circular ring math, virtual node replica factors, and deterministic retry routing.
* [Native Inference Worker Engine Specification](file:///home/ishangupta/inferenciate/docs/WORKER_ENGINE.md): STB image decoding, planar NCHW tensor memory packing, ONNX Runtime session execution, and numerically stable Softmax.
* [API & Protocol Specifications](file:///home/ishangupta/inferenciate/docs/API_AND_PROTOCOLS.md): Full specifications for the HTTP REST API, gRPC Protocol, and WebSocket telemetry stream.
* [Deployment & Operations Guide](file:///home/ishangupta/inferenciate/docs/DEPLOYMENT_AND_OPERATIONS.md): Multi-container Docker Compose setup, production Kubernetes manifests, and CoreDNS headless discovery.

---

## Architecture Overview

```
                      +-----------------------------+
                      |   React Web Dashboard       |
                      |   (Vite + WebSockets)       |
                      +--------------+--------------+
                                     | WS Telemetry (/ws)
                                     v
                      +-----------------------------+
                      |   Java Manager Node         |
                      |   - Netty API Gateway (8080)|
                      |   - Consistent Hash Router  |
                      |   - Dynamic Batch Scheduler |
                      |   - K8s DNS Discovery       |
                      +--------------+--------------+
                                     |
           +-------------------------+-------------------------+
           | gRPC (PredictBatch)     | gRPC (PredictBatch)     | gRPC (PredictBatch)
           v                         v                         v
+--------------------+    +--------------------+    +--------------------+
|  C++ Worker Pod 1  |    |  C++ Worker Pod 2  |    |  C++ Worker Pod 3  |
|  - ONNX Runtime    |    |  - ONNX Runtime    |    |  - ONNX Runtime    |
|  - ResNet-50 v2    |    |  - ResNet-50 v2    |    |  - ResNet-50 v2    |
|  - Contiguous NCHW |    |  - Contiguous NCHW |    |  - Contiguous NCHW |
+--------------------+    +--------------------+    +--------------------+
```

The system is partitioned into three core decoupled services:

1. **Manager Node (`manager/`)**
   * **Asynchronous Ingress:** Non-blocking HTTP API Gateway built on Java 17 and Netty.
   * **Consistent Hashing Router:** Uses MD5 hashing with 50 virtual node replicas per worker for uniform distribution and minimal re-mapping during scaling.
   * **Dynamic Batching Engine:** Accumulates single image requests into time-bounded batches (`BATCH_SIZE = 2`, `MAX_WAIT_MS = 50ms`), maximizing SIMD hardware efficiency without violating latency SLAs.
   * **Kubernetes DNS Auto-Discovery:** Periodically polls Kubernetes Headless Service DNS A-records (5s interval) to register new worker Pod IPs and remove scaled-down/dead Pods.
   * **Telemetry Hub:** Broadcasts real-time JSON execution metrics and topology snapshots over WebSockets (`/ws`) to connected clients.

2. **Worker Node (`worker/`)**
   * **Native Inference Engine:** High-performance C++17 process powered by ONNX Runtime C++ API and gRPC.
   * **Contiguous NCHW Batching:** Decodes raw byte streams (JPEG/PNG) via STB Image, resizes, normalizes (ImageNet mean/std), and packs pixels into a single contiguous multi-dimensional float tensor `(N, 3, 224, 224)`.
   * **Unified Session Run:** Executes tensor batch inference in a single ONNX Runtime session call, utilizing CPU intra-op thread pools (4 threads).
   * **Numerically Stable Softmax:** Normalizes raw output logits using max-subtraction to prevent floating-point overflow.

3. **Dashboard (`dashboard/`)**
   * **Reactive Monitoring Interface:** Modern UI built with React 18, TypeScript, Vite, Tailwind CSS, Recharts, and Framer Motion.
   * **Live Visualizations:** Rolling 30-second latency area chart, throughput bar chart, confidence line chart, interactive topology wiring diagram, drag-and-drop batch uploader, and an integrated synthetic chaos load generator.

---

## Technology Stack

* **Language Runtimes:** Java 17 (OpenJDK), C++17 (GCC/Clang), Node.js 20+, TypeScript 5
* **Networking & RPC:** Netty 4.1, gRPC 1.x, Protocol Buffers 3.x, WebSockets
* **Machine Learning & Vision:** ONNX Runtime C++ API, STB Image (`stb_image.h`), ResNet-50 v2 ONNX Model
* **Frontend UI:** React 18, Vite, Tailwind CSS, Recharts, Framer Motion, Lucide React
* **Containerization & Orchestration:** Docker, Docker Compose, Kubernetes (Headless Services, LoadBalancers)

---

## Key Distributed Systems Concepts

* **Contiguous Tensor Batching:** Eliminates sequential execution overhead by transforming multi-image requests into a unified `(N, 3, 224, 224)` memory buffer, maximizing CPU vectorization and SIMD instruction throughput.
* **Consistent Hash Ring with Virtual Nodes:** Maps requests deterministically to worker nodes using 32-bit MD5 hashes. Virtual node replicas (50 per worker) minimize ring imbalance and partition movement when nodes join or leave.
* **Dynamic Time-Bounded Schedulers:** Drains worker queues via hybrid blocking/polling: takes available requests immediately and polls for up to `BATCH_SIZE` items within a `MAX_WAIT_MS` window.
* **Non-Blocking Telemetry Streaming:** Decouples telemetry emission from HTTP request loops, broadcasting events asynchronously to WebSockets on Netty event loop threads.

---

## API Reference

### HTTP API Endpoints

#### 1. Single Image Inference
* **Endpoint:** `POST /api/job`
* **Content-Type:** `application/octet-stream`
* **Body:** Raw binary encoded image bytes (JPEG, PNG, etc.)
* **Response (200 OK):**
  ```json
  {
    "label": "Class 263",
    "confidence": 94.82
  }
  ```

#### 2. Multi-Image Batch Inference
* **Endpoint:** `POST /api/batch`
* **Content-Type:** `multipart/form-data`
* **Form Fields:** Multiple file uploads (e.g. `image1`, `image2`, ...)
* **Response (200 OK):**
  ```json
  [
    {
      "id": "image1",
      "label": "Class 263",
      "confidence": 94.82
    },
    {
      "id": "image2",
      "label": "Class 609",
      "confidence": 88.15
    }
  ]
  ```

### WebSocket Telemetry Stream

* **URL:** `ws://<MANAGER_HOST>:8080/ws`
* **Events Emitted:**
  * `topology_update`: Sent on connection or worker list change.
    ```json
    { "type": "topology_update", "workers": ["10.244.0.5", "10.244.0.6"] }
    ```
  * `inference_result`: Sent upon job completion.
    ```json
    {
      "type": "inference_result",
      "jobId": "job-a1b2c3d4",
      "workerNode": "10.244.0.5",
      "queueDepth": 1,
      "label": "Class 263",
      "latencyMs": 14,
      "confidence": 94.82
    }
  ```

---

## Deployment & Operations

### Local Containerized Setup (Docker Compose)

1. Build and start the entire stack (Manager, Worker, Dashboard):
   ```bash
   docker-compose up --build -d
   ```

2. Scale the C++ worker inference pool dynamically:
   ```bash
   docker-compose up -d --scale worker=3
   ```

3. Access endpoints:
   * **Dashboard UI:** `http://localhost:5173`
   * **API Gateway:** `http://localhost:8080`

### Production Kubernetes Deployment

Apply the unified manifest to deploy Headless Discovery Services, Worker Deployments, Manager Deployments, and LoadBalancer Ingress:

```bash
kubectl apply -f k8s-inferenciate.yaml
```

---

## Uniqueness & Technical Highlights

1. **Heterogeneous High-Performance Architecture:** Combines Java Netty's asynchronous event-driven I/O model for managing client connections with C++ ONNX Runtime's compiled native execution speed for tensor math.
2. **Zero-Copy Tensor Normalization Pipeline:** Performs in-place image byte decoding, spatial resampling, mean/std normalization, and planar NCHW layout packing directly inside C++ native memory before invoking ONNX Runtime.
3. **Seamless K8s Auto-Discovery + Local Fallback:** Automatically switches between DNS A-record polling against Kubernetes Headless Services and standalone localhost development mode.
4. **Interactive Telemetry & Chaos Injection:** Offers live topology interconnect visualization, worker filtering, and a synthetic load generator built right into the UI for real-time stress testing.

---

## Comparative Analysis

| Feature | **Inferenciate** | **Triton Inference Server** | **TorchServe** | **Ray Serve** | **TF Serving** |
|---|---|---|---|---|---|
| **Primary Language** | Java (Manager) + C++ (Worker) | C++ | Java / Python | Python | C++ |
| **Transport Protocols** | HTTP/1.1, gRPC, WebSockets | HTTP/1.1, gRPC | HTTP/1.1, gRPC | HTTP/1.1, Ray RPC | HTTP/1.1, gRPC |
| **Dynamic Batching** | Time-bounded queue window (`take` + `poll`) | Server-side batch scheduler | Dynamic batching handler | Max batch size / wait window | Batching HTTP/gRPC filter |
| **Model Runtime** | ONNX Runtime C++ API | Multi-backend (TensorRT, ONNX, Torch) | PyTorch / LibTorch | Python model arbitrary code | TensorFlow C++ SavedModel |
| **Discovery Mechanism** | K8s Headless Service DNS polling | External K8s ingress / Mesh | K8s service routing | Ray Cluster Head Node | K8s service routing |
| **Real-time UI Telemetry** | Built-in React WebSocket Dashboard | Prometheus metrics endpoint | Prometheus metrics endpoint | Ray Dashboard | Prometheus metrics endpoint |
| **Footprint / Overhead** | Lightweight container footprint | Large binary footprint | Medium Python/JVM overhead | Medium Python process overhead | Medium C++/TF binary footprint |
