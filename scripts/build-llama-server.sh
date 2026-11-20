#!/usr/bin/env bash
# Builds the on-device llama.cpp server (static, arm64-v8a) used by ANDharness for the local model.
# Output is copied to app/src/main/jniLibs/arm64-v8a/libllamaserver.so (packaged as an executable).
#
# Requirements: Android SDK with NDK 27 and CMake 3.22 installed, git, network.
# Usage: scripts/build-llama-server.sh
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
LLAMA_REPO="${LLAMA_REPO:-https://github.com/ggml-org/llama.cpp}"
LLAMA_DIR="${LLAMA_DIR:-/tmp/llama.cpp}"
BUILD_DIR="${BUILD_DIR:-/tmp/llama-build}"

# Pinned llama.cpp revision. Pinning makes local-server builds reproducible and documents the
# provenance of the shipped app/src/main/jniLibs/arm64-v8a/libllamaserver.so, which was built
# from this exact commit (16 KB page-aligned). Without the pin a clean clone would build whatever
# master happens to be and silently change server behaviour.
# To bump: rebuild with LLAMA_REV_OVERRIDE=<new-sha> scripts/build-llama-server.sh, verify the
# resulting .so on device, then update the default below together with the shipped binary.
LLAMA_REV="56381e407c0ccfb3a6f71e668a27a901001d22ce"
LLAMA_REV="${LLAMA_REV_OVERRIDE:-$LLAMA_REV}"

CMAKE="$ANDROID_HOME/cmake/3.22.1/bin/cmake"
NINJA="$ANDROID_HOME/cmake/3.22.1/bin/ninja"
TOOLCHAIN="$ANDROID_HOME/ndk/$NDK_VERSION/build/cmake/android.toolchain.cmake"
STRIP="$(ls "$ANDROID_HOME"/ndk/"$NDK_VERSION"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a/libllamaserver.so"

if [ ! -d "$LLAMA_DIR" ]; then
  git clone --depth 1 "$LLAMA_REPO" "$LLAMA_DIR"
fi

# Deterministic checkout of the pinned revision: the shallow clone above tracks master, so fetch
# the exact commit and detach HEAD at it before building.
if ! git -C "$LLAMA_DIR" fetch --depth 1 origin "$LLAMA_REV"; then
  echo "ERROR: failed to fetch pinned llama.cpp revision $LLAMA_REV (check the SHA and network)." >&2
  exit 1
fi
if ! git -C "$LLAMA_DIR" checkout --detach "$LLAMA_REV"; then
  echo "ERROR: failed to check out pinned llama.cpp revision $LLAMA_REV." >&2
  exit 1
fi

# 16 KB page-size support (Android 15+/16 KB devices).
# NDK r27 does NOT align ELF segments to 16 KB by default; r28+ does.
# ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON is the NDK r27 CMake option, but for
# EXECUTABLES (libllamaserver.so is really a PIE executable) the NDK does not
# apply the page-size linker flags automatically, so we pass them explicitly.
# Reference: https://developer.android.com/guide/practices/page-sizes
#   "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
"$CMAKE" -S "$LLAMA_DIR" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON \
  -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF

"$CMAKE" --build "$BUILD_DIR" --target llama-server -j 8
"$STRIP" "$BUILD_DIR/bin/llama-server"

mkdir -p "$(dirname "$DEST")"
cp "$BUILD_DIR/bin/llama-server" "$DEST"
chmod 644 "$DEST"
echo "Wrote $DEST"
