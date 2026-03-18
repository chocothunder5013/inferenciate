cmake_minimum_required(VERSION 3.15)
project(InferenciateWorker CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 1. Find System Packages (Ubuntu style)
find_package(Threads REQUIRED)
find_package(Protobuf REQUIRED)
find_package(PkgConfig REQUIRED)
pkg_check_modules(GRPC REQUIRED grpc++)

# 2. Find ONNX Runtime (Downloaded in Dockerfile)
find_library(ONNXRUNTIME_LIB onnxruntime HINTS /usr/local/lib)
include_directories(/usr/local/include)

# 3. Generate gRPC Protobuf files dynamically!
set(PROTO_SRC_DIR "${CMAKE_SOURCE_DIR}/proto")
set(PROTO_BUILD_DIR "${CMAKE_BINARY_DIR}/generated")
file(MAKE_DIRECTORY ${PROTO_BUILD_DIR})

find_program(PROTOC_COMPILER protoc)
find_program(GRPC_CPP_PLUGIN grpc_cpp_plugin)

# This command compiles inference.proto into C++ code during the build
execute_process(
    COMMAND ${PROTOC_COMPILER} 
    --proto_path=${PROTO_SRC_DIR} 
    --cpp_out=${PROTO_BUILD_DIR} 
    --grpc_out=${PROTO_BUILD_DIR} 
    --plugin=protoc-gen-grpc=${GRPC_CPP_PLUGIN} 
    ${PROTO_SRC_DIR}/inference.proto
)

include_directories(${PROTO_BUILD_DIR} ${Protobuf_INCLUDE_DIRS} ${GRPC_INCLUDE_DIRS})

# 4. Build the Executable
file(GLOB SOURCES "src/*.cpp")
file(GLOB PROTO_SRCS "${PROTO_BUILD_DIR}/*.pb.cc")

add_executable(worker_node ${SOURCES} ${PROTO_SRCS})

# 5. Link everything together
target_link_libraries(worker_node 
    PRIVATE 
    ${Protobuf_LIBRARIES} 
    ${GRPC_LIBRARIES} 
    ${ONNXRUNTIME_LIB} 
    Threads::Threads
)