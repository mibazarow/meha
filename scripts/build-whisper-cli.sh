#!/usr/bin/env bash
# Builds the on-device whisper.cpp transcriber (static, arm64-v8a) used by ANDharness for local
# speech-to-text. Output is copied to app/src/main/jniLibs/arm64-v8a/libwhispercli.so and packaged
# exactly like libllamaserver.so (a native lib that AGP extracts and we spawn as an executable).
#
# Requirements: Android SDK with NDK 27 and CMake 3.22 installed, git, network.
# Usage: scripts/build-whisper-cli.sh
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
WHISPER_REPO="${WHISPER_REPO:-https://github.com/ggml-org/whisper.cpp}"
WHISPER_DIR="${WHISPER_DIR:-/tmp/whisper.cpp}"
BUILD_DIR="${BUILD_DIR:-/tmp/whisper-build}"

# Pinned whisper.cpp revision. Pinning makes builds reproducible and documents the provenance of
# the shipped app/src/main/jniLibs/arm64-v8a/libwhispercli.so, which was built from this exact
# commit (16 KB page-aligned). This is the commit behind the stable release tag v1.9.4.
# Without the pin a clean clone would build whatever master happens to be and silently change
# transcription behaviour. Upstream: https://github.com/ggml-org/whisper.cpp/releases/tag/v1.9.4
# To bump: rebuild with WHISPER_REV_OVERRIDE=<new-sha> scripts/build-whisper-cli.sh, verify the
# resulting .so on device, then update the default below together with the shipped binary.
WHISPER_REV="927cfce34f31707e17f2bff35c349632fb9e2c3a"
WHISPER_REV="${WHISPER_REV_OVERRIDE:-$WHISPER_REV}"

CMAKE="$ANDROID_HOME/cmake/3.22.1/bin/cmake"
NINJA="$ANDROID_HOME/cmake/3.22.1/bin/ninja"
TOOLCHAIN="$ANDROID_HOME/ndk/$NDK_VERSION/build/cmake/android.toolchain.cmake"
STRIP="$(ls "$ANDROID_HOME"/ndk/"$NDK_VERSION"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a/libwhispercli.so"

if [ ! -d "$WHISPER_DIR" ]; then
  git clone --depth 1 "$WHISPER_REPO" "$WHISPER_DIR"
fi

# Deterministic checkout of the pinned revision: the shallow clone above tracks master, so fetch
# the exact commit and detach HEAD at it before building.
if ! git -C "$WHISPER_DIR" fetch --depth 1 origin "$WHISPER_REV"; then
  echo "ERROR: failed to fetch pinned whisper.cpp revision $WHISPER_REV (check the SHA and network)." >&2
  exit 1
fi
if ! git -C "$WHISPER_DIR" checkout --detach "$WHISPER_REV"; then
  echo "ERROR: failed to check out pinned whisper.cpp revision $WHISPER_REV." >&2
  exit 1
fi

# 16 KB page-size support (Android 15+/16 KB devices).
# NDK r27 does NOT align ELF segments to 16 KB by default; r28+ does.
# ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON is the NDK r27 CMake option, but for
# EXECUTABLES (libwhispercli.so is really a PIE executable) the NDK does not
# apply the page-size linker flags automatically, so we pass them explicitly.
# Reference: https://developer.android.com/guide/practices/page-sizes
#   "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
"$CMAKE" -S "$WHISPER_DIR" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON \
  -DWHISPER_SDL2=OFF \
  -DGGML_OPENMP=OFF -DGGML_VULKAN=OFF -DGGML_OPENCL=OFF

"$CMAKE" --build "$BUILD_DIR" --target whisper-cli -j 8
"$STRIP" "$BUILD_DIR/bin/whisper-cli"

mkdir -p "$(dirname "$DEST")"
cp "$BUILD_DIR/bin/whisper-cli" "$DEST"
chmod 644 "$DEST"
echo "Wrote $DEST"
