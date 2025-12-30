cmake_minimum_required(VERSION 3.15)
project(worker CXX)

set(CMAKE_CXX_STANDARD 17)

# 1. Find Dependencies
find_package(protobuf CONFIG REQUIRED)
find_package(gRPC CONFIG REQUIRED)
find_package(onnxruntime CONFIG REQUIRED)
find_package(Stb REQUIRED)

# 2. Proto/gRPC Generation Setup
# Locate the protoc compiler and the grpc_cpp_plugin
# 1. Handle Protoc
if(NOT PROTOC_EXECUTABLE)
    # Try to find it in the vcpkg tool folder automatically
    find_program(PROTOC_EXECUTABLE protoc PATHS "${CMAKE_BINARY_DIR}/vcpkg_installed/x64-linux/tools/protobuf" NO_DEFAULT_PATH)
endif()

if(NOT PROTOC_EXECUTABLE)
    # Fallback to the target property if still not found
    get_target_property(PROTOC_EXECUTABLE protobuf::protoc IMPORTED_LOCATION)
endif()

# 2. Handle gRPC Plugin (Do the same for safety)
if(NOT GRPC_CPP_PLUGIN)
    find_program(GRPC_CPP_PLUGIN grpc_cpp_plugin PATHS "${CMAKE_BINARY_DIR}/vcpkg_installed/x64-linux/tools/grpc" NO_DEFAULT_PATH)
endif()

if(NOT GRPC_CPP_PLUGIN)
    get_target_property(GRPC_CPP_PLUGIN gRPC::grpc_cpp_plugin IMPORTED_LOCATION)
endif()
set(PROTO_SRC_DIR "${CMAKE_SOURCE_DIR}/../proto")
set(GENERATED_DIR "${CMAKE_BINARY_DIR}/generated")
file(MAKE_DIRECTORY ${GENERATED_DIR})

set(PROTO_FILES "${PROTO_SRC_DIR}/inference.proto")
set(PROTO_SRCS "${GENERATED_DIR}/inference.pb.cc" "${GENERATED_DIR}/inference.grpc.pb.cc")
set(PROTO_HDRS "${GENERATED_DIR}/inference.pb.h" "${GENERATED_DIR}/inference.grpc.pb.h")

# 3. Command to Generate C++ from Proto
add_custom_command(
    OUTPUT ${PROTO_SRCS} ${PROTO_HDRS}
    COMMAND ${PROTOC_EXECUTABLE}
    ARGS --grpc_out=${GENERATED_DIR}
         --cpp_out=${GENERATED_DIR}
         --plugin=protoc-gen-grpc=${GRPC_CPP_PLUGIN}
         -I ${PROTO_SRC_DIR}
         ${PROTO_FILES}
    DEPENDS ${PROTO_FILES}
)

# 4. Define the Executable
add_executable(worker_node
    src/main.cpp
    ${PROTO_SRCS}
    ${PROTO_HDRS}
)

# 5. Include Directories
target_include_directories(worker_node PUBLIC
    ${GENERATED_DIR}
    ${CMAKE_SOURCE_DIR}/src
    ${Stb_INCLUDE_DIR}
    # Add the base include path (where vcpkg puts everything)
    "${CMAKE_BINARY_DIR}/vcpkg_installed/x64-linux/include"
    # Add the specific onnxruntime folder just in case
    "${CMAKE_BINARY_DIR}/vcpkg_installed/x64-linux/include/onnxruntime"
)
# 6. Link Libraries
target_link_libraries(worker_node PRIVATE
    protobuf::libprotobuf
    gRPC::grpc++
    onnxruntime::onnxruntime
)
