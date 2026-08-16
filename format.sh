#!/bin/bash

# ==============================================================================
# Inferenciate Code Repository Formatter Script
# ==============================================================================
# Automates multi-language code formatting across components:
# 1. Java Manager: google-java-format tool
# 2. C++ Worker: clang-format (Google style guideline)
# 3. TypeScript/CSS Dashboard: Prettier formatting tool
# ==============================================================================

# Exit immediately if any command returns a non-zero exit status
set -e

echo "Starting repository formatting..."

# Format Java Manager component if Google Java Format JAR is present at root
if [ -f "google-java-format.jar" ]; then
    echo "Formatting Java (Manager)..."
    find manager/src/main/java -name "*.java" -exec java -jar google-java-format.jar --replace {} +
else
    echo "[WARNING] google-java-format.jar not found at root. Skipping Java formatting."
fi

# Format C++ Worker component if clang-format utility is installed on host system
if command -v clang-format &> /dev/null; then
    echo "Formatting C++ (Worker)..."
    find worker/src -name "*.cpp" -o -name "*.h" -exec clang-format -i -style=Google {} +
else
    echo "[WARNING] clang-format is not installed. Skipping C++ formatting."
fi

# Format TypeScript and CSS assets in Dashboard component using Prettier
echo "Formatting TypeScript (Dashboard)..."
cd dashboard
if [ -d "node_modules" ]; then
    npx prettier --write "src/**/*.{ts,tsx,css}"
else
    echo "Installing dashboard formatting dependencies..."
    npm install
    npx prettier --write "src/**/*.{ts,tsx,css}"
fi
cd ..

echo "Formatting complete."