import { useEffect, useState, useRef } from "react";
import { Activity, Server, Radio } from "lucide-react";
import { SystemMonitor } from "./components/SystemMonitor";
import type { RealTelemetry } from "./types";
import { ImageUploader } from "./components/ImageUploader";
import { ClusterTopology } from "./components/ClusterTopology";
import { AuditLog } from "./components/AuditLog";
import { ChaosControl } from "./components/ChaosControl";

/**
 * Root Application component providing the main dashboard layout, managing persistent
 * WebSocket connections to the API Gateway, and distributing live telemetry state across components.
 *
 * Maintains:
 * - Persistent WebSocket connection to the Manager API Gateway (`/ws`).
 * - Real-time audit log buffer (capped at 50 recent inference events).
 * - Live worker topology status.
 * - Interactive worker selection filter.
 */
function App() {
  const [auditLogs, setAuditLogs] = useState<RealTelemetry[]>([]);
  const [isConnected, setIsConnected] = useState(false);
  const [telemetry, setTelemetry] = useState<RealTelemetry | null>(null);
  const [selectedWorker, setSelectedWorker] = useState<string | null>(null);
  const ws = useRef<WebSocket | null>(null);

  useEffect(() => {
    // Derive WebSocket endpoint URL from environment variable VITE_API_URL or default fallback
    const baseUrl = import.meta.env.VITE_API_URL || "http://localhost:8080";
    const wsUrl = `${baseUrl.replace(/^http/, "ws")}/ws`;

    const socket = new WebSocket(wsUrl);
    ws.current = socket;

    socket.onopen = () => {
      setIsConnected(true);
      socket.send("Dashboard hello!");
    };

    socket.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);

        if (data.type === "inference_result") {
          const newEvent: RealTelemetry = {
            type: "inference_result",
            jobId: data.jobId,
            latencyMs: data.latencyMs,
            confidence: data.confidence,
            time: new Date().toLocaleTimeString([], {
              hour12: false,
              second: "2-digit",
              minute: "2-digit",
            }),
            label: data.label,
            workerNode: data.workerNode,
            queueDepth: data.queueDepth,
          };

          setTelemetry(newEvent);
          // Append new inference event to audit log and keep most recent 50 entries
          setAuditLogs((prev) => [newEvent, ...prev].slice(0, 50));
        } else if (data.type === "topology_update") {
          // Process topology update event to refresh active cluster worker nodes
          setTelemetry({
            type: "topology_update",
            workers: data.workers,
          });
        }
      } catch (e) {
        console.warn("Received non-JSON websocket message:", event.data);
      }
    };

    socket.onclose = () => setIsConnected(false);

    return () => socket.close();
  }, []);

  return (
    <div className="min-h-screen bg-grid-dark p-6 font-sans selection:bg-grid-neon selection:text-white">
      {/* Header section displaying branding and live cluster connectivity status */}
      <header className="flex items-center justify-between mb-8 border-b border-slate-800 pb-4">
        <div className="flex items-center gap-3">
          <Activity className="text-grid-neon w-8 h-8 animate-pulse" />
          <h1 className="text-3xl font-bold tracking-tight text-white">
            Inferenciate
          </h1>
        </div>

        {/* Dynamic connection status indicator badge */}
        <div
          className={`flex items-center gap-2 px-4 py-2 rounded-full border ${
            isConnected
              ? "border-grid-success/30 bg-grid-success/10"
              : "border-grid-alert/30 bg-grid-alert/10"
          }`}
        >
          <Radio
            className={`w-4 h-4 ${
              isConnected
                ? "text-grid-success animate-pulse"
                : "text-grid-alert"
            }`}
          />
          <span
            className={`text-sm font-semibold tracking-wide ${
              isConnected ? "text-grid-success" : "text-grid-alert"
            }`}
          >
            {isConnected ? "CLUSTER ONLINE" : "CONNECTION LOST"}
          </span>
        </div>
      </header>

      {/* Main dashboard telemetry grid layout */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Real-time system performance & latency monitor panel */}
        <div className="lg:col-span-2 bg-grid-panel border border-slate-700 rounded-xl p-6 shadow-xl relative overflow-hidden group">
          <div className="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-transparent via-grid-neon to-transparent opacity-50"></div>
          <div className="flex items-center gap-2 mb-6">
            <Server className="text-grid-neon w-5 h-5" />
            <h2 className="text-xl font-semibold text-white">
              Cluster Telemetry
            </h2>
          </div>

          <div className="h-[450px] w-full">
            <SystemMonitor
              realData={telemetry}
              selectedWorker={selectedWorker}
            />
          </div>
        </div>

        {/* Sidebar panel containing job submission, chaos testing, and topology visualizer */}
        <div className="flex flex-col gap-6">
          {/* Multipart drag-and-drop batch image uploader */}
          <div className="bg-grid-panel border border-slate-700 rounded-xl p-6 shadow-xl hover:border-grid-neon/50 transition-colors duration-300">
            <h2 className="text-lg font-semibold text-white mb-4">
              Submit Inference Job
            </h2>
            <ImageUploader />
          </div>

          {/* Synthetic load generator control panel */}
          <ChaosControl />

          {/* Dynamic SVG topology map and worker selector */}
          <div className="flex-grow min-h-[300px]">
            <ClusterTopology
              latestEvent={telemetry}
              selectedWorker={selectedWorker}
              onSelectWorker={setSelectedWorker}
            />
          </div>
        </div>
      </div>

      {/* Inference execution audit log table */}
      <div className="mt-6 w-full">
        <AuditLog logs={auditLogs} />
      </div>
    </div>
  );
}

export default App;
