import grpc
from concurrent import futures
import time
import inference_pb2
import inference_pb2_grpc

class MockInferenceService(inference_pb2_grpc.InferenceServiceServicer):
    def Predict(self, request, context):
        print(f"[MockWorker] Received request: {request.request_id}")
        return inference_pb2.InferenceResponse(
            request_id=request.request_id,
            class_label="Mock_Labrador",
            confidence_score=0.99,
            execution_time_ms=15
        )

def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    inference_pb2_grpc.add_InferenceServiceServicer_to_server(MockInferenceService(), server)
    server.add_insecure_port('[::]:50051')
    print("[MockWorker] Listening on port 50051...")
    server.start()
    server.wait_for_termination()

if __name__ == '__main__':
    serve()
