#!/bin/bash

# Exit immediately if a command fails
set -e

echo "========================================="
echo "   Dashboard Comment Sanitizer  "
echo "========================================="

cd dashboard

# Find all TS and TSX files in the src directory and process them safely
find src -type f \( -name "*.ts" -o -name "*.tsx" \) | while read file; do
  echo "Stripping $file..."
  # Use npx to dynamically pull the decomment AST tool, process the file, and overwrite it
  npx -y decomment-cli "$file" > "$file.tmp" && mv "$file.tmp" "$file"
done

# Run Prettier one last time to fix any weird blank lines left behind by deleted comments
npx prettier --write "src/**/*.{ts,tsx}"

echo "========================================="
echo " Dashboard is now pristine and comment-free!"
echo "========================================="
