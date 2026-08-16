# Inferenciate Dashboard

The Inferenciate Dashboard is a reactive telemetry and visualization frontend interface for the Inferenciate Distributed Machine Learning Inference Engine. Engineered with React 18, TypeScript, Vite, Tailwind CSS, Recharts, Framer Motion, and Lucide React icons, it connects over persistent WebSockets to provide real-time visibility into inference latency, job throughput, confidence metrics, and cluster topology.

---

## Key Features

* **Real-Time Telemetry & Monitoring (`SystemMonitor.tsx`):**
  * Live rolling 30-second time-series area chart displaying per-job execution latency.
  * Same-second event aggregation (weighted latency and confidence calculation).
  * Throughput bar chart tracking jobs processed per second.
  * Average prediction confidence percentage trend line.
  * Summary KPI cards for Average Latency, Peak Throughput, and Average Confidence.

* **Cluster Topology Visualizer (`ClusterTopology.tsx`):**
  * Dynamic visual representation of the central Java Manager Node and C++ Worker Nodes.
  * Dynamic SVG interconnect wiring mapping manager-to-worker topology channels.
  * Flash pulse animation (800ms window) whenever a worker node completes a job.
  * Interactive worker filtering (clicking a worker card filters telemetry graphs for that specific node).

* **Drag-and-Drop Batch Image Uploader (`ImageUploader.tsx`):**
  * Client-side drag-and-drop or file selector batch upload manager.
  * Instant image preview thumbnails using `URL.createObjectURL`.
  * Packages multiple pending images into a single `multipart/form-data` request (`POST /api/batch`).
  * Live overlay badges displaying top predicted ImageNet class labels and animated confidence progress bars.

* **Integrated Chaos Testing Controller (`ChaosControl.tsx`):**
  * In-memory transparent 1x1 PNG byte array load generator.
  * Configurable target load slider (1 to 50 requests per second).
  * Automated stress testing of API Gateway dynamic batch scheduling and worker auto-scaling under synthetic load spike conditions.

* **Inference Audit Log (`AuditLog.tsx`):**
  * Scrollable tabular audit log displaying up to 50 recent inference execution events.
  * Highlights high-confidence predictions (>=80%) and latency alerts (>100ms).
  * Smooth entry animations powered by Framer Motion.

---

## Technical Stack & Architecture

| Layer | Technology |
|---|---|
| **Framework** | React 18 (TypeScript) |
| **Build Tool** | Vite |
| **Styling** | Tailwind CSS with custom neon slate theme |
| **Data Visualization** | Recharts (`ResponsiveContainer`, `AreaChart`, `BarChart`, `LineChart`) |
| **Animations** | Framer Motion |
| **Icons** | Lucide React |
| **Networking** | Native Browser `WebSocket` API & `fetch` API |

---

## Environment Variables

| Variable | Default Value | Description |
|---|---|---|
| `VITE_API_URL` | `http://localhost:8080` | Manager API Gateway HTTP URL. The WebSocket endpoint is automatically derived as `ws://<HOST>:8080/ws`. |

---

## Development & Build Workflow

```bash
# 1. Install dependencies
npm install

# 2. Launch development server with Hot Module Replacement (HMR)
npm run dev

# 3. Type check and build production distribution bundle
npm run build

# 4. Preview production build locally
npm run preview
```
