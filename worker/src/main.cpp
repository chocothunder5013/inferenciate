#include <grpcpp/grpcpp.h>
#include <onnxruntime_cxx_api.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <iostream>
#include <vector>

#include "inference.grpc.pb.h"

// STB image decoder single-header library implementation definition
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

/**
 * Computes numerically stable Softmax activation over raw floating-point logits.
 * <p>
 * Subtracts maximum logit value (max_val) prior to exponentiation to prevent floating-point
 * overflow during exponent computation, then normalizes by sum of exponentials to yield valid probabilities.
 * </p>
 *
 * @param input vector of raw model output logits (modified in-place to output probabilities)
 */
void softmax(std::vector<float>& input) {
  float max_val = *std::max_element(input.begin(), input.end());
  float sum = 0;
  for (float& val : input) {
    val = std::exp(val - max_val);
    sum += val;
  }
  for (float& val : input) val /= sum;
}

/**
 * High-performance gRPC Inference Service implementation backed by ONNX Runtime CPU engine.
 * <p>
 * Loads ResNet-50 v2 ONNX model during service initialization and processes incoming batch requests
 * by decoding image binary streams, preprocessing into NCHW tensor formats, executing unified tensor inference,
 * and mapping raw output logits to ImageNet classification labels.
 * </p>
 */
class InferenceServiceImpl final : public InferenceService::Service {
  Ort::Env env;
  Ort::Session session;

 public:
  /**
   * Initializes ONNX Runtime environment, configures CPU intra-op thread pools (4 threads),
   * and loads the pre-trained ResNet-50 v2 ONNX model binary from disk.
   */
  InferenceServiceImpl()
      : env(ORT_LOGGING_LEVEL_WARNING, "WorkerNode"), session(nullptr) {
    Ort::SessionOptions session_options;
    // Set 4 CPU execution threads for parallel ONNX operator execution
    session_options.SetIntraOpNumThreads(4);

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

  /**
   * Single request prediction handler.
   * Wraps the single InferenceRequest into a BatchInferenceRequest of size 1 and delegates to PredictBatch.
   *
   * @param context gRPC server call context
   * @param request single image inference request
   * @param reply target output response object
   * @return gRPC status code
   */
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

  /**
   * Contiguous tensor batch prediction handler.
   * <p>
   * Performs end-to-end batched inference execution:
   * 1. Decodes raw image byte streams using STB Image decoder into RGB pixel arrays.
   * 2. Resizes spatial dimensions to 224x224 and normalizes RGB channels (ImageNet mean & std).
   * 3. Packs normalized values into a single contiguous flat buffer representing an NCHW tensor (N, 3, 224, 224).
   * 4. Executes a single ONNX Runtime Session Run call for the entire batch.
   * 5. Computes Softmax probabilities, resolves top-1 class labels, and populates gRPC response objects.
   * </p>
   *
   * @param context gRPC server call context
   * @param request batch inference request containing N image payloads
   * @param reply target batch inference response containing N prediction results
   * @return gRPC status code (OK on success, INVALID_ARGUMENT or INTERNAL on failure)
   */
  Status PredictBatch(ServerContext* context,
                      const BatchInferenceRequest* request,
                      BatchInferenceResponse* reply) override {
    auto start_time = std::chrono::high_resolution_clock::now();
    int64_t batch_size = request->requests_size();

    std::cout << "[Worker] Processing True Batch " << request->batch_id()
              << " (Size: " << batch_size << ")" << std::endl;

    reply->set_batch_id(request->batch_id());
    if (batch_size == 0) return Status::OK;

    // ResNet-50 v2 input shape constants: (N, 3, 224, 224)
    constexpr int64_t channels = 3;
    constexpr int64_t input_height = 224;
    constexpr int64_t input_width = 224;
    constexpr int64_t single_img_size = channels * input_height * input_width;

    // Allocate contiguous memory buffer for the full input batch tensor [N * 3 * 224 * 224]
    std::vector<float> batched_tensor_values(batch_size * single_img_size);

    // Standard ImageNet normalization coefficients (RGB channels)
    float mean[] = {0.485f, 0.456f, 0.406f};
    float std[] = {0.229f, 0.224f, 0.225f};

    // Decode raw image byte streams and transform into planar NCHW layout
    for (int b = 0; b < batch_size; ++b) {
      const auto& req = request->requests(b);
      const std::string& raw_data = req.image_data();

      int width, height, img_channels;
      // Load raw image bytes into uncompressed 3-channel RGB pixel buffer
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

      // Resample spatial coordinates and convert interleaved HWC to planar NCHW format
      for (int y = 0; y < input_height; ++y) {
        for (int x = 0; x < input_width; ++x) {
          int src_x = (int)(x * scale_x);
          int src_y = (int)(y * scale_y);
          int src_idx = (src_y * width + src_x) * 3;

          for (int c = 0; c < 3; ++c) {
            float pixel = img_data[src_idx + c] / 255.0f;
            // NCHW indexing formula: batch_offset + (channel * height * width) + (y * width) + x
            batched_tensor_values[batch_offset +
                                  c * input_height * input_width +
                                  y * input_width + x] =
                (pixel - mean[c]) / std[c];
          }
        }
      }
      // Free uncompressed STB image pixel buffer
      stbi_image_free(img_data);
    }

    // Construct ONNX Runtime Cpu CPU tensor wrapper around batched_tensor_values memory
    std::vector<int64_t> input_node_dims = {batch_size, channels, input_height,
                                            input_width};
    auto memory_info =
        Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);

    try {
      Ort::Value input_tensor = Ort::Value::CreateTensor<float>(
          memory_info, batched_tensor_values.data(),
          batched_tensor_values.size(), input_node_dims.data(),
          input_node_dims.size());

      // Retrieve dynamic input and output node names from model metadata
      Ort::AllocatorWithDefaultOptions allocator;
      auto input_name_allocated = session.GetInputNameAllocated(0, allocator);
      auto output_name_allocated = session.GetOutputNameAllocated(0, allocator);

      const char* input_names[] = {input_name_allocated.get()};
      const char* output_names[] = {output_name_allocated.get()};

      // Execute unified ONNX Runtime inference run for the complete batch tensor
      auto output_tensors = session.Run(Ort::RunOptions{nullptr}, input_names,
                                        &input_tensor, 1, output_names, 1);

      float* output_arr = output_tensors.front().GetTensorMutableData<float>();
      constexpr int num_classes = 1000; // ImageNet 1000 classification categories

      auto end_time = std::chrono::high_resolution_clock::now();
      auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(
          end_time - start_time);
      int64_t time_per_img = duration.count() / batch_size;

      // Extract class predictions and populate gRPC response objects
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

/**
 * Initializes and starts the gRPC server listener on port 50051.
 * Sets maximum receive message size to 50MB to support large multi-image batch payloads.
 */
void RunServer() {
  std::string server_address("[::]:50051");
  InferenceServiceImpl service;

  ServerBuilder builder;

  // Set maximum payload receive size to 50MB to support large batch transmissions
  builder.SetMaxReceiveMessageSize(50 * 1024 * 1024);

  builder.AddListeningPort(server_address, grpc::InsecureServerCredentials());
  builder.RegisterService(&service);
  std::unique_ptr<Server> server(builder.BuildAndStart());
  std::cout << "[Worker] Server listening on " << server_address << std::endl;
  server->Wait();
}

/**
 * Main application entry point for the C++ Worker Node.
 */
int main(int argc, char** argv) {
  RunServer();
  return 0;
}