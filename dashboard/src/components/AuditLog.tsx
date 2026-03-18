import { motion, AnimatePresence } from "framer-motion";
import { List, ShieldCheck, ShieldAlert, Clock, Zap } from "lucide-react";
import type { RealTelemetry } from "../types";

export function AuditLog({ logs }: { logs: RealTelemetry[] }) {
  return (
    <div className="bg-[#0a0f18] border border-slate-800 rounded-xl shadow-inner flex flex-col h-[400px] overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between p-4 border-b border-slate-800 bg-[#0f172a]/50">
        <div className="flex items-center gap-2">
          <List className="text-slate-400 w-5 h-5" />
          <h3 className="text-sm font-semibold text-slate-300 tracking-wide uppercase">
            Inference Audit Log
          </h3>
        </div>
        <div className="text-xs font-mono text-slate-500">
          Showing last {logs.length} events
        </div>
      </div>

      {/* Table Container */}
      <div className="flex-grow overflow-auto custom-scrollbar relative">
        <table className="w-full text-left border-collapse">
          <thead className="sticky top-0 bg-[#0f172a] z-10 shadow-md">
            <tr>
              <th className="p-3 text-xs font-mono text-slate-400 font-medium border-b border-slate-700">Time</th>
              <th className="p-3 text-xs font-mono text-slate-400 font-medium border-b border-slate-700">Job ID</th>
              <th className="p-3 text-xs font-mono text-slate-400 font-medium border-b border-slate-700">Worker Node</th>
              <th className="p-3 text-xs font-mono text-slate-400 font-medium border-b border-slate-700">Prediction</th>
              <th className="p-3 text-xs font-mono text-slate-400 font-medium border-b border-slate-700">Confidence</th>
              <th className="p-3 text-xs font-mono text-slate-400 font-medium border-b border-slate-700">Latency</th>
            </tr>
          </thead>
          <tbody className="font-mono text-xs">
            <AnimatePresence>
              {logs.length === 0 ? (
                <tr>
                  <td colSpan={6} className="p-8 text-center text-slate-600">
                    Awaiting cluster activity...
                  </td>
                </tr>
              ) : (
                logs.map((log, i) => {
                  // Fallbacks for missing data
                  const jobId = log.jobId ? log.jobId.substring(0, 8) : "unknown";
                  const node = log.workerNode || "gateway";
                  const label = log.label || "N/A";
                  const isHighConf = log.confidence >= 80;

                  return (
                    <motion.tr
                      key={log.jobId || i}
                      initial={{ opacity: 0, y: -10, backgroundColor: "rgba(6, 182, 212, 0.2)" }}
                      animate={{ opacity: 1, y: 0, backgroundColor: "rgba(0,0,0,0)" }}
                      transition={{ duration: 0.5 }}
                      className="border-b border-slate-800/50 hover:bg-slate-800/30 transition-colors group"
                    >
                      <td className="p-3 text-slate-500 flex items-center gap-2">
                        <Clock className="w-3 h-3" /> {log.time}
                      </td>
                      <td className="p-3 text-cyan-500">{jobId}</td>
                      <td className="p-3 text-slate-300">{node}</td>
                      <td className="p-3 text-white font-bold uppercase">{label}</td>
                      <td className="p-3">
                        <div className={`flex items-center gap-1.5 px-2 py-1 rounded w-max ${isHighConf ? 'bg-emerald-500/10 text-emerald-400' : 'bg-amber-500/10 text-amber-400'}`}>
                          {isHighConf ? <ShieldCheck className="w-3 h-3" /> : <ShieldAlert className="w-3 h-3" />}
                          {log.confidence.toFixed(1)}%
                        </div>
                      </td>
                      <td className="p-3 text-slate-400 flex items-center gap-1">
                        <Zap className={`w-3 h-3 ${log.latencyMs > 100 ? 'text-amber-500' : 'text-slate-500'}`} />
                        {log.latencyMs}ms
                      </td>
                    </motion.tr>
                  );
                })
              )}
            </AnimatePresence>
          </tbody>
        </table>
      </div>
    </div>
  );
}
