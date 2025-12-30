# Build Stage
FROM ubuntu:22.04 AS build

# Install build tools and vcpkg dependencies
RUN apt-get update && apt-get install -y \
    build-essential cmake git curl zip unzip tar pkg-config \
    && rm -rf /var/lib/apt/lists/*

# Install vcpkg
WORKDIR /opt
RUN git clone https://github.com/microsoft/vcpkg.git
RUN ./vcpkg/bootstrap-vcpkg.sh

# Copy Source
WORKDIR /app
COPY vcpkg.json .
COPY CMakeLists.txt .
COPY src ./src
COPY proto ../proto

# Build with Dynamic Linking
RUN mkdir build && cd build && \
    cmake .. -DCMAKE_TOOLCHAIN_FILE=/opt/vcpkg/scripts/buildsystems/vcpkg.cmake \
             -DVCPKG_TARGET_TRIPLET=x64-linux-dynamic && \
    make -j4

# Runtime Stage
FROM ubuntu:22.04

WORKDIR /app

# We need to copy the compiled binary AND the shared libraries from vcpkg
COPY --from=build /app/build/worker_node .
COPY --from=build /app/build/resnet50-v2-7.onnx . 
# NOTE: In a real prod env, you'd verify exact library paths. 
# For now, we rely on vcpkg's local install folder in the build stage
COPY --from=build /app/build/vcpkg_installed/x64-linux-dynamic/lib /usr/local/lib

# Ensure the OS finds the libraries
RUN ldconfig

EXPOSE 50051

CMD ["./worker_node"]
