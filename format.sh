#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

echo "Starting repository formatting..."

# 1. Format Java Backend
if [ -f "google-java-format.jar" ]; then
    echo "Formatting Java (Manager)..."
    # Finds all .java files in the manager directory and formats them in place
    find manager/src/main/java -name "*.java" -exec java -jar google-java-format.jar --replace {} +
else
    echo "[WARNING] google-java-format.jar not found at root. Skipping Java formatting."
fi

# 2. Format C++ Worker
if command -v clang-format &> /dev/null; then
    echo "Formatting C++ (Worker)..."
    # Finds all .cpp and .h files and formats them according to Google style
    find worker/src -name "*.cpp" -o -name "*.h" -exec clang-format -i -style=Google {} +
else
    echo "[WARNING] clang-format is not installed. Skipping C++ formatting."
fi

# 3. Format TypeScript/React Dashboard
echo "Formatting TypeScript (Dashboard)..."
cd dashboard
# Assuming you have prettier installed in your node_modules
if [ -d "node_modules" ]; then
    npx prettier --write "src/**/*.{ts,tsx,css}"
else
    echo "Running npm install in dashboard to get formatting tools..."
    npm install
    npx prettier --write "src/**/*.{ts,tsx,css}"
fi
cd ..

echo "Formatting complete."