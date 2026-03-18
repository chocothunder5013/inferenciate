import { useState, useEffect, useMemo } from "react";
import {
  LineChart,
  Line,
  AreaChart,
  Area,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts";
import { Activity, Zap, Target, Clock, Cpu } from "lucide-react";
import type { RealTelemetry } from "../types";

interface TelemetryPoint {
  time: string;
  latencyMs: number;
  throughput: number;
  confidence: number;
}

const MAX_DATA_POINTS = 30; // Increased for a better historical view

export function SystemMonitor({
  realData,
  selectedWorker
}: {
  realData: RealTelemetry | null;
  selectedWorker?: string | null;
}) {
  const [data, setData] = useState<TelemetryPoint[]>([]);

  // Seed the chart with empty data for a smooth initial UI
  useEffect(() => {
    const emptyData = Array.from({ length: MAX_DATA_POINTS }).map((_, i) => ({
      time: new Date(Date.now() - (MAX_DATA_POINTS - i) * 1000).toLocaleTimeString([], {
        hour12: false,
        second: "2-digit",
        minute: "2-digit",
      }),
      latencyMs: 0,
      throughput: 0,
      confidence: 0,
    }));
    setData(emptyData);
  }, []);

  // Process live data
  useEffect(() => {
    if (realData) {
      if (selectedWorker && realData.workerNode && realData.workerNode !== selectedWorker) {
        return; // Ignore this telemetry point because we are filtering!
      }
      setData((prev) => {
        const newPoint = {
          time: realData.time,
          latencyMs: realData.latencyMs,
          throughput: 1,
          confidence: realData.confidence,
        };

        const lastPoint = prev[prev.length - 1];
        if (lastPoint && lastPoint.time === newPoint.time) {
          // Aggregate jobs in the same second
          const updatedPrev = [...prev];
          updatedPrev[updatedPrev.length - 1] = {
            ...lastPoint,
            throughput: lastPoint.throughput + 1,
            // Rolling average for latency and confidence in the same second
            latencyMs: Math.round((lastPoint.latencyMs * lastPoint.throughput + newPoint.latencyMs) / (lastPoint.throughput + 1)),
            confidence: (lastPoint.confidence * lastPoint.throughput + newPoint.confidence) / (lastPoint.throughput + 1),
          };
          return updatedPrev;
        }

        return [...prev.slice(1), newPoint]; // Slide window
      });
    }
  }, [realData]);

  // Calculate live KPIs based on recent data
  const metrics = useMemo(() => {
    const recent = data.filter(d => d.throughput > 0); // Only count active seconds
    if (recent.length === 0) return { avgLatency: 0, peakThroughput: 0, avgConfidence: 0 };
    
    const latest10 = recent.slice(-10);
    return {
      avgLatency: Math.round(latest10.reduce((acc, curr) => acc + curr.latencyMs, 0) / latest10.length),
      peakThroughput: Math.max(...data.map(d => d.throughput)),
      avgConfidence: (latest10.reduce((acc, curr) => acc + curr.confidence, 0) / latest10.length).toFixed(1)
    };
  }, [data]);

  const CustomTooltip = ({ active, payload, label }: any) => {
    if (active && payload && payload.length) {
      return (
        <div className="bg-[#0f172a]/95 border border-slate-700 p-3 rounded-lg shadow-2xl backdrop-blur-md">
          <p className="text-slate-400 text-xs mb-2 font-mono border-b border-slate-700 pb-1">{label}</p>
          {payload.map((entry: any, index: number) => (
            <p key={index} className="text-white font-bold flex items-center gap-2 text-sm">
              <span style={{ color: entry.color }}>●</span>
              {entry.name}: <span className="font-mono">{entry.value.toFixed(1)}</span>
            </p>
          ))}
        </div>
      );
    }
    return null;
  };

  return (
    <div className="w-full h-full flex flex-col gap-4 overflow-y-auto pr-2 pb-4">
      
      {/* KPI Stat Cards */}
      <div className="grid grid-cols-3 gap-4 mb-2">
        <div className="bg-[#0a0f18] border border-slate-800 rounded-lg p-3 flex items-center gap-3">
          <div className="p-2 bg-cyan-500/10 rounded-md"><Clock className="w-5 h-5 text-cyan-400" /></div>
          <div>
            <p className="text-xs text-slate-400 font-semibold uppercase tracking-wider">Avg Latency</p>
            <p className="text-xl font-bold text-white font-mono">{metrics.avgLatency} <span className="text-sm text-slate-500">ms</span></p>
          </div>
        </div>
        <div className="bg-[#0a0f18] border border-slate-800 rounded-lg p-3 flex items-center gap-3">
          <div className="p-2 bg-purple-500/10 rounded-md"><Zap className="w-5 h-5 text-purple-400" /></div>
          <div>
            <p className="text-xs text-slate-400 font-semibold uppercase tracking-wider">Peak T-Put</p>
            <p className="text-xl font-bold text-white font-mono">{metrics.peakThroughput} <span className="text-sm text-slate-500">j/s</span></p>
          </div>
        </div>
        <div className="bg-[#0a0f18] border border-slate-800 rounded-lg p-3 flex items-center gap-3">
          <div className="p-2 bg-emerald-500/10 rounded-md"><Target className="w-5 h-5 text-emerald-400" /></div>
          <div>
            <p className="text-xs text-slate-400 font-semibold uppercase tracking-wider">Avg Conf</p>
            <p className="text-xl font-bold text-white font-mono">{metrics.avgConfidence}<span className="text-sm text-slate-500">%</span></p>
          </div>
        </div>
      </div>

      {/* Chart 1: Inference Latency */}
      <div className="bg-[#0a0f18] border border-slate-800 rounded-xl p-4 shadow-inner flex-grow min-h-[200px]">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Activity className="w-4 h-4 text-cyan-400" />
            <h3 className="text-sm font-semibold text-slate-300">Inference Latency Trend</h3>
          </div>
          {/* Live Indicator */}
          <div className="flex items-center gap-2">
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-cyan-400 opacity-75"></span>
              <span className="relative inline-flex rounded-full h-2 w-2 bg-cyan-500"></span>
            </span>
            <span className="text-xs font-mono text-cyan-500/70 uppercase">Live</span>
          </div>
        </div>
        <div className="h-[calc(100%-2rem)] w-full">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={data}>
              <defs>
                <linearGradient id="colorLatency" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#06b6d4" stopOpacity={0.3}/>
                  <stop offset="95%" stopColor="#06b6d4" stopOpacity={0}/>
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
              <XAxis dataKey="time" stroke="#475569" fontSize={10} tickMargin={10} minTickGap={20} />
              <YAxis stroke="#475569" fontSize={10} domain={[0, 'auto']} />
              <Tooltip content={<CustomTooltip />} />
              <Area 
                isAnimationActive={false} /* Disabled for smooth live streaming */
                type="monotone" 
                dataKey="latencyMs" 
                name="Latency (ms)" 
                stroke="#06b6d4" 
                strokeWidth={2}
                fillOpacity={1} 
                fill="url(#colorLatency)" 
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Grid for smaller charts */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 h-48">
        
        {/* Chart 2: Batch Throughput */}
        <div className="bg-[#0a0f18] border border-slate-800 rounded-xl p-4 shadow-inner flex flex-col">
          <div className="flex items-center gap-2 mb-2">
            <Cpu className="w-4 h-4 text-purple-500" />
            <h3 className="text-sm font-semibold text-slate-300">Throughput (Jobs/sec)</h3>
          </div>
          <div className="flex-grow w-full">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={data}>
                <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
                <Tooltip content={<CustomTooltip />} cursor={{ fill: '#1e293b' }} />
                <Bar 
                  isAnimationActive={false}
                  dataKey="throughput" 
                  name="Jobs" 
                  fill="#8b5cf6" 
                  radius={[4, 4, 0, 0]} 
                />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>

        {/* Chart 3: Model Confidence */}
        <div className="bg-[#0a0f18] border border-slate-800 rounded-xl p-4 shadow-inner flex flex-col">
          <div className="flex items-center gap-2 mb-2">
            <Target className="w-4 h-4 text-emerald-500" />
            <h3 className="text-sm font-semibold text-slate-300">Avg Confidence (%)</h3>
          </div>
          <div className="flex-grow w-full">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={data}>
                <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
                <YAxis stroke="#475569" fontSize={10} domain={[0, 100]} hide />
                <Tooltip content={<CustomTooltip />} />
                <Line 
                  isAnimationActive={false}
                  type="stepAfter" 
                  dataKey="confidence" 
                  name="Confidence" 
                  stroke="#10b981" 
                  strokeWidth={2}
                  dot={false}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>

      </div>
    </div>
  );
}