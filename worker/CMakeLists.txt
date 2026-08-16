# ==============================================================================
# Inferenciate C++ Worker Node Build Configuration
# ==============================================================================
# Builds the high-performance C++17 inference worker executable.
# Integrates Protocol Buffers, gRPC, STB Image, and ONNX Runtime CPU engine.
# ==============================================================================

cmake_minimum_required(VERSION 3.15)
project(InferenciateWorker CXX)

# Enforce strict C++17 language standard for ONNX Runtime C++ API compatibility
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Locate required system package dependencies (Threads, Protobuf, gRPC)
find_package(Threads REQUIRED)
find_package(Protobuf REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(GRPC REQUIRED grpc++)

# Locate ONNX Runtime native shared library and include headers
find_library(ONNXRUNTIME_LIB onnxruntime HINTS /usr/local/lib)
include_directories(/usr/local/include)

# Configure output directory for protoc compiler code generation
set(PROTO_SRC_DIR "${CMAKE_SOURCE_DIR}/proto")
set(PROTO_BUILD_DIR "${CMAKE_BINARY_DIR}/generated")
file(MAKE_DIRECTORY ${PROTO_BUILD_DIR})

find_program(PROTOC_COMPILER protoc)
find_program(GRPC_CPP_PLUGIN grpc_cpp_plugin)

# Generate C++ Protobuf and gRPC stubs from inference.proto schema
execute_process(
    COMMAND ${PROTOC_COMPILER} 
    --proto_path=${PROTO_SRC_DIR} 
    --cpp_out=${PROTO_BUILD_DIR} 
    --grpc_out=${PROTO_BUILD_DIR} 
    --plugin=protoc-gen-grpc=${GRPC_CPP_PLUGIN} 
    ${PROTO_SRC_DIR}/inference.proto
)

include_directories(${PROTO_BUILD_DIR} ${Protobuf_INCLUDE_DIRS} ${GRPC_INCLUDE_DIRS})

# Compile target worker_node executable from C++ source files and generated proto stubs
file(GLOB SOURCES "src/*.cpp")
file(GLOB PROTO_SRCS "${PROTO_BUILD_DIR}/*.pb.cc")

add_executable(worker_node ${SOURCES} ${PROTO_SRCS})

# Link third-party libraries (Protobuf, gRPC, ONNX Runtime, Threads) to worker binary
target_link_libraries(worker_node 
    PRIVATE 
    ${Protobuf_LIBRARIES} 
    ${GRPC_LIBRARIES} 
    ${ONNXRUNTIME_LIB} 
    Threads::Threads
)