FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive

# Install C++ build toolchain and gRPC/Protobuf dependencies
RUN apt-get update && apt-get install -y \
    build-essential cmake git pkg-config curl wget unzip tar \
    protobuf-compiler protobuf-compiler-grpc \
    libprotobuf-dev libgrpc++-dev libgrpc-dev libgomp1 ca-certificates

WORKDIR /build

# Install ONNX Runtime headers and libraries
RUN wget https://github.com/microsoft/onnxruntime/releases/download/v1.16.3/onnxruntime-linux-x64-1.16.3.tgz && \
    tar -zxvf onnxruntime-linux-x64-1.16.3.tgz && \
    cp -r onnxruntime-linux-x64-1.16.3/include/* /usr/local/include/ && \
    cp -r onnxruntime-linux-x64-1.16.3/lib/* /usr/local/lib/ && \
    ldconfig

# Download STB Image header library
RUN wget https://raw.githubusercontent.com/nothings/stb/master/stb_image.h -O /usr/local/include/stb_image.h

# Copy C++ worker source files and protobuf definitions
COPY worker/CMakeLists.txt ./
COPY worker/src ./src
COPY proto ./proto

# Build release executable
RUN cmake -B build -S . -DCMAKE_BUILD_TYPE=Release
RUN cmake --build build -j4

EXPOSE 50051

# Launch worker node binary
ENTRYPOINT ["/build/build/worker_node"]