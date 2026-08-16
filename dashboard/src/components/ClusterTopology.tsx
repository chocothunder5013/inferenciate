import { useState, useEffect, useRef } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { Server, Cpu, Database } from "lucide-react";
import type { RealTelemetry } from "../types";

interface ClusterTopologyProps {
  /** Latest incoming telemetry event (topology update or inference result). */
  latestEvent: RealTelemetry | null;
  /** Currently selected worker node IP string for filtering telemetry graphs. */
  selectedWorker: string | null;
  /** Callback fired when user clicks a worker node card to toggle selection filter. */
  onSelectWorker: (workerId: string | null) => void;
}

/**
 * Cluster Topology visualization component.
 * <p>
 * Displays live topology map of the Inferenciate cluster including the central Manager Node
 * and active C++ Worker Nodes. Renders dynamic SVG interconnect wires, pulses worker cards upon
 * job receipt (800ms visual flash timer), and supports interactive node selection for filtering telemetry metrics.
 * </p>
 */
export function ClusterTopology({
  latestEvent,
  selectedWorker,
  onSelectWorker,
}: ClusterTopologyProps) {
  const [activeNodes, setActiveNodes] = useState<Record<string, boolean>>({});
  const [liveWorkers, setLiveWorkers] = useState<string[]>([]);

  // Per-worker pulse timers for visual activity indication
  const blinkTimers = useRef<Record<string, ReturnType<typeof setTimeout>>>({});

  useEffect(() => {
    if (!latestEvent) return;

    if (latestEvent.type === "topology_update" && latestEvent.workers) {
      setLiveWorkers(latestEvent.workers);
      return;
    }

    if (latestEvent.type === "inference_result" && latestEvent.workerNode) {
      const node = latestEvent.workerNode;
      setActiveNodes((prev) => ({ ...prev, [node]: true }));

      if (blinkTimers.current[node]) {
        clearTimeout(blinkTimers.current[node]);
      }

      // Pulse active worker node card visually for 800ms upon job completion
      blinkTimers.current[node] = setTimeout(() => {
        setActiveNodes((prev) => ({ ...prev, [node]: false }));
      }, 800);
    }
  }, [latestEvent]);

  // Clean up activity pulse timers on component unmount
  useEffect(() => {
    return () => {
      Object.values(blinkTimers.current).forEach(clearTimeout);
    };
  }, []);

  return (
    <div className="bg-[#0a0f18] border border-slate-800 rounded-xl p-4 shadow-inner h-full flex flex-col relative overflow-hidden group">
      {/* Header bar displaying active node count and filter clear trigger */}
      <div className="flex items-center justify-between mb-6 border-b border-slate-800 pb-2">
        <div className="flex items-center gap-2">
          <Database className="text-slate-400 w-4 h-4" />
          <h3 className="text-sm font-mono text-slate-400 uppercase tracking-wider">
            Live Topology ({liveWorkers.length} Nodes)
          </h3>
        </div>

        <AnimatePresence>
          {selectedWorker && (
            <motion.button
              initial={{ opacity: 0, x: 20 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: 20 }}
              onClick={() => onSelectWorker(null)}
              className="text-xs font-mono text-grid-alert hover:text-red-400 bg-grid-alert/10 px-2 py-1 rounded border border-grid-alert/30 transition-colors"
            >
              Clear Filter ✕
            </motion.button>
          )}
        </AnimatePresence>
      </div>

      {/* Dynamic SVG interconnect wiring and active worker topology layout */}
      <div className="flex-grow flex flex-col items-center justify-center gap-8 relative overflow-x-auto pb-4">
        {/* Manager Node visual badge */}
        <motion.div
          animate={{
            boxShadow: [
              "0px 0px 0px rgba(6,182,212,0)",
              "0px 0px 15px rgba(6,182,212,0.3)",
              "0px 0px 0px rgba(6,182,212,0)",
            ],
          }}
          transition={{ duration: 2, repeat: Infinity }}
          className="relative z-10 bg-grid-panel border border-grid-neon/50 rounded-lg p-3 flex flex-col items-center min-w-[120px]"
        >
          <Server className="w-6 h-6 text-grid-neon mb-1" />
          <span className="text-xs font-bold text-white tracking-widest uppercase">
            Manager
          </span>
        </motion.div>

        {/* SVG connection lines linking Manager node to dynamically placed worker node cards */}
        <div className="absolute top-[30%] left-0 w-full h-[40%] flex justify-center -z-0 opacity-20 pointer-events-none min-w-full">
          <svg width="100%" height="100%" preserveAspectRatio="none">
            {liveWorkers.map((workerId, index) => {
              const totalWorkers = liveWorkers.length;
              let targetX = 50;
              if (totalWorkers > 1) {
                const step = 70 / (totalWorkers - 1);
                targetX = 15 + index * step;
              }
              return (
                <path
                  key={`wire-${workerId}`}
                  d={`M 50% 0 Q ${targetX}% 50% ${targetX}% 100%`}
                  stroke="#06b6d4"
                  strokeWidth="2"
                  fill="none"
                  strokeDasharray="4 4"
                />
              );
            })}
          </svg>
        </div>

        {/* Worker node interactive cards */}
        <div className="flex items-center justify-center gap-4 min-w-full z-10 flex-nowrap">
          {liveWorkers.length === 0 ? (
            <span className="text-slate-500 font-mono text-xs animate-pulse">
              Waiting for workers...
            </span>
          ) : (
            liveWorkers.map((workerId) => {
              const isProcessing = activeNodes[workerId];
              const isSelected = selectedWorker === workerId;
              const isDimmed = selectedWorker !== null && !isSelected;
              const shortName = workerId.replace(/\./g, "-").substring(0, 10);

              return (
                <motion.button
                  key={workerId}
                  onClick={() => onSelectWorker(isSelected ? null : workerId)}
                  animate={{
                    scale: isSelected ? 1.1 : 1,
                    opacity: isDimmed ? 0.4 : 1,
                    borderColor: isSelected
                      ? "#06b6d4"
                      : isProcessing
                        ? "#10b981"
                        : "#1e293b",
                    backgroundColor: isSelected
                      ? "rgba(6, 182, 212, 0.15)"
                      : isProcessing
                        ? "rgba(16, 185, 129, 0.1)"
                        : "rgba(15, 23, 42, 0.8)",
                  }}
                  className={`flex-none w-[100px] border rounded-lg p-3 flex flex-col items-center transition-all duration-300 relative group cursor-pointer hover:border-slate-500`}
                >
                  <Cpu
                    className={`w-5 h-5 mb-2 transition-colors ${isSelected ? "text-grid-neon" : isProcessing ? "text-grid-success" : "text-slate-500"}`}
                  />
                  <span
                    className={`text-[10px] font-mono text-center truncate w-full ${isSelected ? "text-grid-neon font-bold" : "text-slate-300"}`}
                  >
                    {shortName}
                  </span>
                </motion.button>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
}
