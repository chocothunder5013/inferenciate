#include <grpcpp/grpcpp.h>
#include <onnxruntime_cxx_api.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <iostream>
#include <vector>

#include "inference.grpc.pb.h"

// STB Image for decoding raw image bytes sent from Java
#define STB_IMAGE_IMPLEMENTATION
#include <stb_image.h>

using grpc::Server;
using grpc::ServerBuilder;
using grpc::ServerContext;
using grpc::Status;
using inference::BatchInferenceRequest;
using inference::BatchInferenceResponse;
using inference::InferenceRequest;
using inference::InferenceResponse;
using inference::InferenceService;

// Helper: Softmax to turn raw neural net scores into percentages (0.0 to 1.0)
void softmax(std::vector<float>& input) {
  float max_val = *std::max_element(input.begin(), input.end());
  float sum = 0;
  for (float& val : input) {
    val = std::exp(val - max_val);
    sum += val;
  }
  for (float& val : input) val /= sum;
}

// Our highly-optimized True Tensor Batching engine
class InferenceServiceImpl final : public InferenceService::Service {
  Ort::Env env;
  Ort::Session session;

 public:
  InferenceServiceImpl()
      : env(ORT_LOGGING_LEVEL_WARNING, "WorkerNode"), session(nullptr) {
    Ort::SessionOptions session_options;
    session_options.SetIntraOpNumThreads(4);

    // This expects the volume mount we setup in docker-compose.yml!
    const char* model_path = "/models/resnet50-v2-7.onnx";

    try {
      session = Ort::Session(env, model_path, session_options);
      std::cout << "[Worker] ONNX Model loaded successfully!" << std::endl;
    } catch (const Ort::Exception& e) {
      std::cerr << "\n[CRITICAL ERROR] Failed to load model: " << e.what()
                << std::endl;
      exit(1);
    }
  }

  // 1. Single Prediction (Fallback)
  Status Predict(ServerContext* context, const InferenceRequest* request,
                 InferenceResponse* reply) override {
    BatchInferenceRequest batch_req;
    batch_req.set_batch_id("single-" + request->request_id());
    *batch_req.add_requests() = *request;

    BatchInferenceResponse batch_resp;
    Status s = PredictBatch(context, &batch_req, &batch_resp);

    if (s.ok() && batch_resp.responses_size() > 0) {
      *reply = batch_resp.responses(0);
    }
    return s;
  }

  // 2. TRUE BATCH PREDICTION
  Status PredictBatch(ServerContext* context,
                      const BatchInferenceRequest* request,
                      BatchInferenceResponse* reply) override {
    auto start_time = std::chrono::high_resolution_clock::now();
    int64_t batch_size = request->requests_size();

    std::cout << "[Worker] Processing True Batch " << request->batch_id()
              << " (Size: " << batch_size << ")" << std::endl;

    reply->set_batch_id(request->batch_id());
    if (batch_size == 0) return Status::OK;

    constexpr int64_t channels = 3;
    constexpr int64_t input_height = 224;
    constexpr int64_t input_width = 224;
    constexpr int64_t single_img_size = channels * input_height * input_width;

    // Allocate ONE massive contiguous block of memory for the whole batch
    std::vector<float> batched_tensor_values(batch_size * single_img_size);

    float mean[] = {0.485f, 0.456f, 0.406f};
    float std[] = {0.229f, 0.224f, 0.225f};

    // Decode and stitch all images into the giant tensor
    for (int b = 0; b < batch_size; ++b) {
      const auto& req = request->requests(b);
      const std::string& raw_data = req.image_data();

      int width, height, img_channels;
      unsigned char* img_data = stbi_load_from_memory(
          reinterpret_cast<const unsigned char*>(raw_data.c_str()),
          raw_data.size(), &width, &height, &img_channels, 3);

      if (!img_data) {
        return Status(grpc::StatusCode::INVALID_ARGUMENT,
                      "Corrupt image in batch at index " + std::to_string(b));
      }

      float scale_x = (float)width / input_width;
      float scale_y = (float)height / input_height;
      int64_t batch_offset = b * single_img_size;

      for (int y = 0; y < input_height; ++y) {
        for (int x = 0; x < input_width; ++x) {
          int src_x = (int)(x * scale_x);
          int src_y = (int)(y * scale_y);
          int src_idx = (src_y * width + src_x) * 3;

          for (int c = 0; c < 3; ++c) {
            float pixel = img_data[src_idx + c] / 255.0f;
            batched_tensor_values[batch_offset +
                                  c * input_height * input_width +
                                  y * input_width + x] =
                (pixel - mean[c]) / std[c];
          }
        }
      }
      stbi_image_free(img_data);
    }

    // Create the input tensor with the NCHW shape (N, 3, 224, 224)
    std::vector<int64_t> input_node_dims = {batch_size, channels, input_height,
                                            input_width};
    auto memory_info =
        Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);

    try {
      Ort::Value input_tensor = Ort::Value::CreateTensor<float>(
          memory_info, batched_tensor_values.data(),
          batched_tensor_values.size(), input_node_dims.data(),
          input_node_dims.size());

      // NEW: Dynamically grab the exact input and output names from the ONNX
      // model itself!
      Ort::AllocatorWithDefaultOptions allocator;
      auto input_name_allocated = session.GetInputNameAllocated(0, allocator);
      auto output_name_allocated = session.GetOutputNameAllocated(0, allocator);

      const char* input_names[] = {input_name_allocated.get()};
      const char* output_names[] = {output_name_allocated.get()};

      // RUN INFERENCE EXACTLY ONCE FOR THE ENTIRE BATCH!
      auto output_tensors = session.Run(Ort::RunOptions{nullptr}, input_names,
                                        &input_tensor, 1, output_names, 1);

      float* output_arr = output_tensors.front().GetTensorMutableData<float>();
      constexpr int num_classes = 1000;

      auto end_time = std::chrono::high_resolution_clock::now();
      auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(
          end_time - start_time);
      int64_t time_per_img = duration.count() / batch_size;

      // Slice up the output array and map it back to the respective responses
      for (int b = 0; b < batch_size; ++b) {
        std::vector<float> logits(output_arr + (b * num_classes),
                                  output_arr + ((b + 1) * num_classes));
        softmax(logits);

        auto max_it = std::max_element(logits.begin(), logits.end());
        int class_id = std::distance(logits.begin(), max_it);
        float confidence = *max_it;

        InferenceResponse* individual_resp = reply->add_responses();
        individual_resp->set_request_id(request->requests(b).request_id());
        individual_resp->set_class_label("Class " + std::to_string(class_id));
        individual_resp->set_confidence_score(confidence);
        individual_resp->set_execution_time_ms(time_per_img);
      }

      return Status::OK;

    } catch (const Ort::Exception& e) {
      return Status(grpc::StatusCode::INTERNAL, e.what());
    }
  }
};

void RunServer() {
  std::string server_address("[::]:50051");
  InferenceServiceImpl service;

  ServerBuilder builder;

  // NEW: Uncap the payload size to 50MB so batches don't crash the socket
  builder.SetMaxReceiveMessageSize(50 * 1024 * 1024);

  builder.AddListeningPort(server_address, grpc::InsecureServerCredentials());
  builder.RegisterService(&service);
  std::unique_ptr<Server> server(builder.BuildAndStart());
  std::cout << "[Worker] Server listening on " << server_address << std::endl;
  server->Wait();
}

int main(int argc, char** argv) {
  RunServer();
  return 0;
}