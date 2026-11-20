#!/usr/bin/env bash
# Downloads the bundled on-device model (Qwen3.5-2B, Q4_K_M) into app/src/main/assets/models/
# so it is packaged inside the APK. The .gguf is git-ignored (too large for the repo).
#
# Usage: scripts/fetch-model.sh
set -euo pipefail

DEST_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/models"
DEST="$DEST_DIR/qwen3.5-2b-q4_k_m.gguf"
URL="https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf"

mkdir -p "$DEST_DIR"
if [ -f "$DEST" ]; then
  echo "Model already present: $DEST"
  exit 0
fi
echo "Downloading model (~1.3 GB) to $DEST ..."
curl -L --fail -o "$DEST" "$URL"
echo "Done: $DEST"
