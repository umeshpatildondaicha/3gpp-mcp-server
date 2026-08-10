#!/usr/bin/env bash
# download_reranker.sh — Download bge-reranker-v2-m3 ONNX files to ~/.cache/3gpp-mcp/
#
# Usage:
#   1. Get a free HuggingFace token at https://huggingface.co/settings/tokens
#   2. Run: HF_TOKEN=hf_xxx bash download_reranker.sh
#      Or:  bash download_reranker.sh hf_xxx
#
# Files are cached and reused on subsequent server restarts.

set -euo pipefail

TOKEN="${HF_TOKEN:-${1:-}}"
CACHE_DIR="$HOME/.cache/3gpp-mcp"
mkdir -p "$CACHE_DIR"

if [ -z "$TOKEN" ]; then
    echo "ERROR: HuggingFace token required."
    echo "  Get a free read token at: https://huggingface.co/settings/tokens"
    echo "  Then run: HF_TOKEN=hf_xxx bash download_reranker.sh"
    exit 1
fi

download() {
    local url="$1"
    local dest="$2"
    local name
    name=$(basename "$dest")
    if [ -f "$dest" ] && [ -s "$dest" ]; then
        echo "  ✓ $name already cached at $dest"
        return 0
    fi
    echo "  ↓ Downloading $name ..."
    local tmp="${dest}.tmp"
    if curl -fL --retry 3 --retry-delay 5 -H "Authorization: Bearer $TOKEN" \
            -o "$tmp" "$url"; then
        mv "$tmp" "$dest"
        local size
        size=$(du -sh "$dest" | cut -f1)
        echo "  ✓ $name saved ($size) → $dest"
    else
        rm -f "$tmp"
        echo "  ✗ Failed to download $url"
        exit 1
    fi
}

echo "Downloading bge-reranker-v2-m3 ONNX files to $CACHE_DIR ..."
download \
    "https://huggingface.co/BAAI/bge-reranker-v2-m3/resolve/main/tokenizer.json" \
    "$CACHE_DIR/reranker-tokenizer.json"

download \
    "https://huggingface.co/Xenova/bge-reranker-v2-m3/resolve/main/onnx/model_quantized.onnx" \
    "$CACHE_DIR/reranker-model-quantized.onnx"

echo ""
echo "Done! Restart the MCP server and the reranker will activate automatically."
echo "No token needed after this — files are cached locally."
