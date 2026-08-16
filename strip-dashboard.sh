#!/bin/bash

# ==============================================================================
# Inferenciate Dashboard Comment Stripper Script
# ==============================================================================
# Strips comments from TypeScript (.ts, .tsx) source files in the dashboard
# directory using decomment-cli, followed by Prettier formatting. Useful for
# minification testing and clean asset builds.
# ==============================================================================

# Exit immediately on failure
set -e

echo "Starting dashboard comment removal..."

cd dashboard

# Strip comments from TypeScript source files in src directory
find src -type f \( -name "*.ts" -o -name "*.tsx" \) | while read -r file; do
  echo "Processing $file..."
  npx -y decomment-cli "$file" > "$file.tmp" && mv "$file.tmp" "$file"
done

# Format modified files with Prettier
npx prettier --write "src/**/*.{ts,tsx}"

echo "Dashboard comments stripped and files formatted successfully."
