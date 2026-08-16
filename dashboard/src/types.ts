/**
 * Model prediction classification label and normalized confidence score structure.
 */
export interface PredictionResult {
  /** Predicted class label string (e.g. "Class 263"). */
  label: string;
  /** Prediction confidence score normalized percentage (0.0 to 100.0 or float representation). */
  confidence: number;
}

/**
 * Discriminatory union type representing real-time WebSocket telemetry payloads emitted by the Java API Gateway.
 */
export type RealTelemetry =
  | {
      /** Telemetry variant indicating completed single or batch item inference execution. */
      type: "inference_result";
      /** Unique job identifier matching the submitted request. */
      jobId: string;
      /** IP address or hostname of the C++ worker node that processed this job. */
      workerNode: string;
      /** Current queue depth of pending jobs on the worker node at completion time. */
      queueDepth: number;
      /** Predicted classification label string. */
      label: string;
      /** Total execution latency in milliseconds. */
      latencyMs: number;
      /** Prediction confidence score percentage (0 to 100). */
      confidence: number;
      /** Formatted local timestamp string (HH:mm:ss) of event receipt. */
      time: string;
    }
  | {
      /** Telemetry variant indicating cluster topology change or initial connection worker snapshot. */
      type: "topology_update";
      /** Array of active worker node IP addresses currently registered in the manager ring. */
      workers: string[];
    };
