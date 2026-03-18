export interface PredictionResult {
  label: string;
  confidence: number;
}

export type RealTelemetry = 
  | {
      type: "inference_result";
      jobId: string;
      workerNode: string;
      queueDepth: number;
      label: string;
      latencyMs: number;
      confidence: number;
      time: string;
    }
  | {
      type: "topology_update";
      workers: string[];
    };