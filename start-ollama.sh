#!/bin/bash
# Start Ollama for the DescribeImage / DescribeVideo tools.
# - Installs Ollama via Homebrew if missing
# - Pulls the vision model if missing
# - Starts `ollama serve` in the foreground

set -e

# Note: llama3.2-vision uses the 'mllama' architecture which recent Ollama
# versions no longer support. qwen2.5vl:7b is a well-supported alternative.
MODEL="${OLLAMA_VISION_MODEL:-qwen2.5vl:7b}"
HOST="${OLLAMA_HOST:-http://localhost:11434}"

echo "Using model: $MODEL"
echo "Using host:  $HOST"
echo ""

if ! command -v ollama >/dev/null 2>&1; then
    if command -v brew >/dev/null 2>&1; then
        echo "Ollama is not installed. Installing via Homebrew..."
        brew install ollama
    else
        echo "Error: ollama is not installed and Homebrew is unavailable."
        echo "Install manually from https://ollama.com/download and re-run this script."
        exit 1
    fi
fi

if curl -sf "$HOST/api/tags" >/dev/null 2>&1; then
    echo "Ollama already running at $HOST."
else
    echo "Starting 'ollama serve' in the background..."
    OLLAMA_LOG="${TMPDIR:-/tmp}/ollama-serve.log"
    nohup ollama serve >"$OLLAMA_LOG" 2>&1 &
    OLLAMA_PID=$!
    echo "  pid: $OLLAMA_PID"
    echo "  log: $OLLAMA_LOG"

    for i in {1..30}; do
        if curl -sf "$HOST/api/tags" >/dev/null 2>&1; then
            echo "Ollama is ready."
            break
        fi
        sleep 1
    done

    if ! curl -sf "$HOST/api/tags" >/dev/null 2>&1; then
        echo "Error: Ollama did not become ready within 30 seconds. See $OLLAMA_LOG."
        exit 1
    fi
fi

if ollama list 2>/dev/null | awk 'NR>1 {print $1}' | grep -qx "$MODEL"; then
    echo "Model '$MODEL' already pulled."
else
    echo "Pulling model '$MODEL' (this can take a while the first time)..."
    ollama pull "$MODEL"
fi

echo ""
echo "Ready. You can now run:"
echo "  java --enable-preview src/main/java/net/lckx/video/DescribeImage.java <image-file>"
