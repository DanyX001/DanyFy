#!/usr/bin/env bash
# ==============================================================================
# ORPHEON CONTINUOUS DEVELOPMENT & AUTOMATED BUILD PIPELINE
# ==============================================================================
# Workflow:
#   SOURCE CODE CHANGE
#     -> CODE ANALYSIS
#     -> OPTIMIZATION VERIFICATION
#     -> UNIT TESTS
#     -> ANDROID BUILD
#     -> BUILD VERIFICATION (aapt2 + apksigner)
#     -> APK GENERATION (ARM64, ARMv7, Universal, Android TV)
#     -> CHECKSUM GENERATION (SHA-256)
#     -> CHANGELOG GENERATION
#     -> DOWNLOAD WEBSITE UPDATE (ORPHEON BUILDS)
# ==============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="/usr/lib/jvm/java-1.21.0-openjdk-amd64"
export PATH="/root/.cargo/bin:${PATH}"

CHANNEL="${1:-stable}"
EXTRA_ARGS=("${@:2}")

echo "=================================================================="
echo " ORPHEON AUTOMATED PIPELINE — CHANNEL: ${CHANNEL^^}"
echo "=================================================================="

echo "[Stage 0] Compiling Native Rust Audio & SIMD DSP Engine (liborpheon_rust.so)..."
(
  cd "${ROOT_DIR}/base/rust-audio"
  cargo build --release
  cargo build --release --target aarch64-linux-android
  cargo build --release --target armv7-linux-androideabi
  cargo build --release --target x86_64-linux-android
  cargo build --release --target i686-linux-android
  cp target/aarch64-linux-android/release/liborpheon_rust.so "${ROOT_DIR}/base/app/src/main/jniLibs/arm64-v8a/"
  cp target/armv7-linux-androideabi/release/liborpheon_rust.so "${ROOT_DIR}/base/app/src/main/jniLibs/armeabi-v7a/"
  cp target/x86_64-linux-android/release/liborpheon_rust.so "${ROOT_DIR}/base/app/src/main/jniLibs/x86_64/"
  cp target/i686-linux-android/release/liborpheon_rust.so "${ROOT_DIR}/base/app/src/main/jniLibs/x86/"
)

echo "[Stage 1] Running Benchmark & Static Verification Suite..."
python3 "${ROOT_DIR}/tools/benchmark_suite.py"

echo "[Stage 2] Executing Build, Verification, Checksum & Website Update..."
python3 "${ROOT_DIR}/tools/build_pipeline.py" --channel "${CHANNEL}" "${EXTRA_ARGS[@]}"

echo "=================================================================="
echo " PIPELINE COMPLETED SUCCESSFULLY"
echo " Website Root: ${ROOT_DIR}/website/index.html"
echo "=================================================================="
