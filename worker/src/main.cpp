#include <iostream>
#include <vector>
#include <algorithm>
#include <cmath>
#include <grpcpp/grpcpp.h>
#include "inference.grpc.pb.h"
#include <onnxruntime_cxx_api.h>

// STB Image for loading JPEGs
#define STB_IMAGE_IMPLEMENTATION
#include <stb_image.h>

using grpc::Server;
using grpc::ServerBuilder;
using grpc::ServerContext;
using grpc::Status;
using inference::InferenceService;
using inference::InferenceRequest;
using inference::InferenceResponse;
using inference::BatchInferenceRequest;
using inference::BatchInferenceResponse;

// Helper: Softmax to turn raw scores into probabilities
void softmax(std::vector<float>& input) {
    float max_val = *std::max_element(input.begin(), input.end());
    float sum = 0;
    for (float& val : input) {
        val = std::exp(val - max_val);
        sum += val;
    }
    for (float& val : input) val /= sum;
}

class InferenceServiceImpl final : public InferenceService::Service {
    Ort::Env env;
    Ort::Session session;
    
public:
    InferenceServiceImpl() : 
        env(ORT_LOGGING_LEVEL_WARNING, "WorkerNode"), 
        session(nullptr) {
        
        Ort::SessionOptions session_options;
        session_options.SetIntraOpNumThreads(1);
        
        // Ensure this path matches your Dockerfile location!
        const char* model_path = "resnet50-v2-7.onnx"; 
        
        try {
            session = Ort::Session(env, model_path, session_options);
            std::cout << "[Worker] ONNX Model loaded successfully!" << std::endl;
        } catch (const Ort::Exception& e) {
            std::cerr << "\n[CRITICAL ERROR] Failed to load model: " << e.what() << std::endl;
            exit(1); 
        }
    }

    // ---------------------------------------------------------
    // 1. Single Prediction (Legacy / Direct calls)
    // ---------------------------------------------------------
    Status Predict(ServerContext* context, const InferenceRequest* request,
                   InferenceResponse* reply) override {
        return RunInference(request, reply);
    }

    // ---------------------------------------------------------
    // 2. Batch Prediction (New High-Throughput Method)
    // ---------------------------------------------------------
    Status PredictBatch(ServerContext* context, const BatchInferenceRequest* request,
                        BatchInferenceResponse* reply) override {
        
        std::cout << "[Worker] Processing Batch " << request->batch_id() 
                  << " (Size: " << request->requests_size() << ")" << std::endl;

        reply->set_batch_id(request->batch_id());

        // Process each request in the batch sequentially
        // (Optimisation tip: In real life, you'd stack these into one tensor (N, 3, 224, 224) 
        // and run session.Run() once. For now, a loop is fine.)
        for (const auto& individual_req : request->requests()) {
            InferenceResponse* individual_resp = reply->add_responses();
            
            Status s = RunInference(&individual_req, individual_resp);
            if (!s.ok()) {
                return s; // Fail the whole batch if one crashes (or handle gracefully)
            }
        }
        return Status::OK;
    }

private:
    // ---------------------------------------------------------
    // Core Logic (Refactored)
    // ---------------------------------------------------------
    Status RunInference(const InferenceRequest* request, InferenceResponse* reply) {
        auto start_time = std::chrono::high_resolution_clock::now();

        // A. Decode Image
        const std::string& raw_data = request->image_data();
        int width, height, channels;
        unsigned char* img_data = stbi_load_from_memory(
            reinterpret_cast<const unsigned char*>(raw_data.c_str()), 
            raw_data.size(), &width, &height, &channels, 3);

        if (!img_data) {
            return Status(grpc::StatusCode::INTERNAL, "Failed to decode image");
        }

        // B. Preprocess (Resize to 224x224 & Normalize)
        constexpr int64_t input_width = 224;
        constexpr int64_t input_height = 224;
        constexpr int64_t input_size = input_width * input_height * 3;
        
        std::vector<float> input_tensor_values(input_size);
        float mean[] = {0.485f, 0.456f, 0.406f};
        float std[]  = {0.229f, 0.224f, 0.225f};

        float scale_x = (float)width / input_width;
        float scale_y = (float)height / input_height;

        for (int y = 0; y < input_height; ++y) {
            for (int x = 0; x < input_width; ++x) {
                int src_x = (int)(x * scale_x);
                int src_y = (int)(y * scale_y);
                int src_idx = (src_y * width + src_x) * 3;

                for (int c = 0; c < 3; ++c) {
                    float pixel = img_data[src_idx + c] / 255.0f;
                    input_tensor_values[c * input_width * input_height + y * input_width + x] = 
                        (pixel - mean[c]) / std[c];
                }
            }
        }
        stbi_image_free(img_data);

        // C. Create Tensor & Run
        std::vector<int64_t> input_node_dims = {1, 3, input_height, input_width};
        auto memory_info = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        
        try {
            Ort::Value input_tensor = Ort::Value::CreateTensor<float>(
                memory_info, input_tensor_values.data(), input_tensor_values.size(), 
                input_node_dims.data(), input_node_dims.size());

            const char* input_names[] = {"data"}; 
            const char* output_names[] = {"resnetv27_dense0_fwd"}; 

            auto output_tensors = session.Run(
                Ort::RunOptions{nullptr}, input_names, &input_tensor, 1, output_names, 1);

            // D. Post-process
            float* floatarr = output_tensors.front().GetTensorMutableData<float>();
            std::vector<float> results(floatarr, floatarr + 1000); 
            softmax(results);

            auto max_it = std::max_element(results.begin(), results.end());
            int class_id = std::distance(results.begin(), max_it);
            float confidence = *max_it;

            auto end_time = std::chrono::high_resolution_clock::now();
            auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time);

            // E. Set Response
            reply->set_request_id(request->request_id());
            reply->set_class_label("Class " + std::to_string(class_id)); 
            reply->set_confidence_score(confidence);
            reply->set_execution_time_ms(duration.count());

            return Status::OK;

        } catch (const Ort::Exception& e) {
             return Status(grpc::StatusCode::INTERNAL, e.what());
        }
    }
};

void RunServer() {
    std::string server_address("0.0.0.0:50051");
    InferenceServiceImpl service;

    ServerBuilder builder;
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