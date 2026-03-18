FROM ubuntu:22.04
ENV DEBIAN_FRONTEND=noninteractive

# 1. Install EVERYTHING the worker needs to build and run
RUN apt-get update && apt-get install -y \
    build-essential cmake git pkg-config curl wget unzip tar \
    protobuf-compiler protobuf-compiler-grpc \
    libprotobuf-dev libgrpc++-dev libgrpc-dev libgomp1 ca-certificates

WORKDIR /build

# 2. Install ONNX Runtime & STB Image
RUN wget https://github.com/microsoft/onnxruntime/releases/download/v1.16.3/onnxruntime-linux-x64-1.16.3.tgz && \
    tar -zxvf onnxruntime-linux-x64-1.16.3.tgz && \
    cp -r onnxruntime-linux-x64-1.16.3/include/* /usr/local/include/ && \
    cp -r onnxruntime-linux-x64-1.16.3/lib/* /usr/local/lib/ && \
    ldconfig

# NEW: Download stb_image.h directly into the system include path
RUN wget https://raw.githubusercontent.com/nothings/stb/master/stb_image.h -O /usr/local/include/stb_image.h

# 3. Copy our code (Removed vcpkg.json since we aren't using it)
COPY worker/CMakeLists.txt ./
COPY worker/src ./src
COPY proto ./proto

# 4. Build the binary
RUN cmake -B build -S . -DCMAKE_BUILD_TYPE=Release
RUN cmake --build build -j4

EXPOSE 50051

# 5. Run the binary directly from the build folder
ENTRYPOINT ["/build/build/worker_node"]