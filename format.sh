#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

echo "========================================="
echo "   Inferenciate: Multi-Stack Formatter   "
echo "========================================="

# 1. Frontend & Configs (Requires Node/npm)
echo -e "\n[1/3] Formatting Web & YAML files with Prettier..."
# Run prettier on the dashboard
cd dashboard
npx prettier --write "src/**/*.{ts,tsx,css}" "index.html" "*.json"
cd ..
# Run prettier on root YAML/JSON files
npx prettier --write "*.yaml" "*.yml" "worker/*.json"

# 2. C++ and Protobuf (Requires clang-format)
echo -e "\n[2/3] Formatting C++ & Protos with clang-format..."
if command -v clang-format &> /dev/null; then
    find worker/src proto -iname "*.cpp" -o -iname "*.h" -o -iname "*.proto" | xargs clang-format -i -style=Google
    echo "C++ & Protos formatted."
else
    echo "⚠️ clang-format not installed. Skipping. (Run: sudo apt install clang-format)"
fi

# 3. Java (Requires wget & java)
echo -e "\n[3/3] Formatting Java with google-java-format..."
GJF_VERSION="1.17.0"
GJF_JAR="google-java-format.jar"

if [ ! -f "$GJF_JAR" ]; then
    echo "Downloading google-java-format..."
    wget -q "https://github.com/google/google-java-format/releases/download/v${GJF_VERSION}/google-java-format-${GJF_VERSION}-all-deps.jar" -O "$GJF_JAR"
fi

find manager/src -name "*.java" | xargs java -jar "$GJF_JAR" --replace
echo "Java formatted."

echo -e "\n========================================="
echo " ✨ All files successfully formatted! ✨ "
echo "========================================="
