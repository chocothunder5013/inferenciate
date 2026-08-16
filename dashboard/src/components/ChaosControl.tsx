import { useState, useRef, useEffect } from "react";
import { Flame, Settings2, Activity, XOctagon } from "lucide-react";

/**
 * Chaos Control synthetic load generator component.
 * <p>
 * Generates continuous synthetic HTTP POST requests against the API Gateway endpoint (`/api/job`)
 * using a minimal 1x1 byte-array PNG payload. Enables dynamic stress testing of cluster batching,
 * worker load distribution, queue depth build-up, and auto-scaling mechanisms under customizable load (1-50 RPS).
 * </p>
 */
export function ChaosControl() {
  const [isTesting, setIsTesting] = useState(false);
  const [rps, setRps] = useState(10);
  const [stats, setStats] = useState({ sent: 0, success: 0, failed: 0 });
  const intervalRef = useRef<number | null>(null);

  // In-memory 1x1 transparent PNG byte array payload used for synthetic load testing requests
  const dummyImage = new Uint8Array([
    137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0,
    0, 0, 1, 8, 6, 0, 0, 0, 31, 21, 196, 137, 0, 0, 0, 10, 73, 68, 65, 84, 120,
    156, 99, 0, 1, 0, 0, 5, 0, 1, 13, 10, 45, 180, 0, 0, 0, 0, 73, 69, 78, 68,
    174, 66, 96, 130,
  ]);

  /**
   * Dispatches a single synthetic image POST request to /api/job and updates stress counters.
   */
  const fireRequest = async () => {
    setStats((prev) => ({ ...prev, sent: prev.sent + 1 }));
    try {
      const apiUrl = import.meta.env.VITE_API_URL || "http://localhost:8080";
      const response = await fetch(`${apiUrl}/api/job`, {
        method: "POST",
        headers: { "Content-Type": "application/octet-stream" },
        body: dummyImage.buffer,
      });
      if (response.ok) {
        setStats((prev) => ({ ...prev, success: prev.success + 1 }));
      } else {
        setStats((prev) => ({ ...prev, failed: prev.failed + 1 }));
      }
    } catch {
      setStats((prev) => ({ ...prev, failed: prev.failed + 1 }));
    }
  };

  /**
   * Starts the synthetic request generation loop based on configured requests per second (RPS).
   */
  const startChaos = () => {
    setIsTesting(true);
    setStats({ sent: 0, success: 0, failed: 0 });

    const msPerRequest = 1000 / rps;

    intervalRef.current = window.setInterval(() => {
      fireRequest();
    }, msPerRequest);
  };

  /**
   * Stops active request generation timers and resets control state.
   */
  const stopChaos = () => {
    setIsTesting(false);
    if (intervalRef.current !== null) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  };

  // Clear active load generation timers on component unmount
  useEffect(() => {
    return () => {
      if (intervalRef.current !== null) {
        clearInterval(intervalRef.current);
      }
    };
  }, []);

  return (
    <div className="bg-[#1a0b14] border border-rose-900/50 rounded-xl p-6 shadow-[0_0_30px_rgba(225,29,72,0.1)] relative overflow-hidden group">
      {/* Background hazard warning pattern */}
      <div className="absolute inset-0 opacity-10 bg-[repeating-linear-gradient(45deg,transparent,transparent_10px,#e11d48_10px,#e11d48_20px)] pointer-events-none"></div>

      <div className="relative z-10">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2 text-rose-500">
            <Flame className="w-5 h-5 animate-pulse" />
            <h2 className="text-lg font-bold tracking-widest uppercase">
              Chaos Test
            </h2>
          </div>
          {isTesting && (
            <div className="flex items-center gap-2 bg-rose-500/20 text-rose-400 px-3 py-1 rounded-full text-xs font-mono font-bold animate-pulse">
              <Activity className="w-3 h-3" /> ACTIVE
            </div>
          )}
        </div>

        <div className="space-y-6">
          {/* Target load rate slider (1 - 50 RPS) */}
          <div>
            <div className="flex justify-between text-xs font-mono text-slate-400 mb-2">
              <span className="flex items-center gap-1">
                <Settings2 className="w-3 h-3" /> Target Load
              </span>
              <span className="text-rose-400 font-bold">{rps} Req / Sec</span>
            </div>
            <input
              type="range"
              min="1"
              max="50"
              value={rps}
              onChange={(e) => setRps(Number(e.target.value))}
              disabled={isTesting}
              className="w-full h-2 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-rose-500 disabled:opacity-50"
            />
          </div>

          {/* Test execution trigger buttons */}
          {!isTesting ? (
            <button
              onClick={startChaos}
              className="w-full py-3 bg-rose-600 hover:bg-rose-500 text-white font-bold rounded-lg shadow-[0_0_15px_rgba(225,29,72,0.4)] transition-all uppercase tracking-wider flex items-center justify-center gap-2"
            >
              <Flame className="w-5 h-5" /> Initiate Load Spike
            </button>
          ) : (
            <button
              onClick={stopChaos}
              className="w-full py-3 bg-slate-800 hover:bg-slate-700 text-rose-400 border border-rose-900/50 font-bold rounded-lg transition-all uppercase tracking-wider flex items-center justify-center gap-2"
            >
              <XOctagon className="w-5 h-5" /> Abort Sequence
            </button>
          )}

          {/* Real-time stress metrics summary */}
          <div className="grid grid-cols-3 gap-2 pt-2 border-t border-rose-900/30">
            <div className="text-center">
              <div className="text-[10px] text-slate-500 font-mono uppercase">
                Dispatched
              </div>
              <div className="text-lg font-bold text-white font-mono">
                {stats.sent}
              </div>
            </div>
            <div className="text-center">
              <div className="text-[10px] text-emerald-500 font-mono uppercase">
                200 OK
              </div>
              <div className="text-lg font-bold text-emerald-400 font-mono">
                {stats.success}
              </div>
            </div>
            <div className="text-center">
              <div className="text-[10px] text-rose-500 font-mono uppercase">
                Failed
              </div>
              <div className="text-lg font-bold text-rose-400 font-mono">
                {stats.failed}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
