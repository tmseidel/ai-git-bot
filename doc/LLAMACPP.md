# Using llama.cpp

This guide covers running AI-Git-Bot with [llama.cpp](https://github.com/ggerganov/llama.cpp) for local, private AI-powered code reviews using GGUF model files — no external API keys required. This is ideal for self-hosters with compliance requirements who cannot send code to external services.

## Overview

llama.cpp is a high-performance C++ inference engine for LLMs that supports a wide range of quantized model formats (GGUF). The bot connects to llama.cpp's OpenAI-compatible `/v1/completions` endpoint, which supports model routing and GBNF grammar constraints.

### Key Advantages over Ollama

| Feature | llama.cpp | Ollama |
|---------|-----------|--------|
| **GBNF Grammar** | ✅ Supported through the OpenAI-compatible endpoint | ❌ JSON mode only |
| **Model Format** | GGUF files directly | Ollama-specific format |
| **Agent Reliability** | ✅ Grammar-constrained JSON | ⚠️ Often fails |
| **Memory Efficiency** | Highly optimized | Good |
| **Setup Complexity** | Model download required | `ollama pull` |

The **GBNF grammar support** is the main advantage for this bot — it allows constraining the model's output to valid JSON matching the agent's expected schema, significantly improving reliability for issue implementation.

The bot requests streaming usage counters with `stream_options.include_usage`. Use a current llama.cpp server build that supports this OpenAI-compatible option if token usage reporting is required; completions remain supported when an older build omits those counters.

## Quick Start

A ready-to-use Docker Compose environment is provided:

```bash
# Start llama.cpp server with Qwen2.5-Coder 7B Instruct
docker compose -f systemtest/docker-compose-llamacpp.yml up -d

# Wait for model download (~5GB) and server startup
docker compose -f systemtest/docker-compose-llamacpp.yml logs -f model-downloader
```

Then start the bot and configure the AI Integration in the **web UI**:

1. Go to **AI Integrations → New Integration**
2. Select **llama.cpp** as the provider type
3. Set the **API URL** to `http://localhost:8081`
4. Enter the **Model** identifier (e.g., `qwen2.5-coder-7b-instruct`)
5. Click **Save**

**For CPU-only systems**, the default docker-compose uses the CPU-only image. For GPU acceleration, change the image to `ghcr.io/ggml-org/llama.cpp:server-cuda13` and uncomment the `deploy.resources` section.

## Configuration

All AI provider settings are configured through the **web UI** under **AI Integrations**:

1. Go to **AI Integrations → New Integration**
2. Select **llama.cpp** as the provider type
3. Set the **API URL** to your llama.cpp server (e.g., `http://localhost:8081` or `http://llamacpp:8081` if using Docker networking)
4. Enter the model name configured in the server. With a single model launched using `--model`, llama.cpp ignores this value; in router mode, enter the exact model ID returned by `GET /v1/models` because it selects the model for each request.
5. Adjust settings as needed:

| Setting | Recommended Value | Why |
|---------|-------------------|-----|
| **Max Tokens** | `4096` | Default for llama.cpp, increase for longer responses |
| **Context Window (tokens)** | Your server's `--ctx-size` | Lets the bot compact history before it overflows the context |

6. Click **Save**

> **Tip:** With a small context (e.g. 16k), also lower **Max diff chars per chunk** (e.g. `20000`) and **Max diff chunks** (e.g. `4`) on the review workflow to leave room for issue descriptions and file contents.

## Recommended Models for Coding

### For Code Reviews (PR Comments)

| Model | Size | Quantization | Quality | Notes |
|-------|------|--------------|---------|-------|
| `qwen2.5-coder-7b-instruct` | ~5 GB | Q4_K_M | ⭐⭐⭐⭐ | **Default** - Best quality for size |
| `qwen2.5-coder-14b-instruct` | ~9 GB | Q4_K_M | ⭐⭐⭐⭐⭐ | Excellent quality |
| `deepseek-coder-6.7b-instruct` | ~4 GB | Q4_K_M | ⭐⭐⭐ | Good code understanding |
| `codellama-13b-instruct` | ~8 GB | Q4_K_M | ⭐⭐⭐ | Decent quality |
| `codellama-7b-instruct` | ~4 GB | Q4_K_M | ⭐⭐ | For testing only |

### For Agent (Issue Implementation)

The bot uses **GBNF grammar constraints** to ensure valid JSON output from llama.cpp, making the agent feature more reliable than with Ollama:

| Model | Size | Agent Compatibility |
|-------|------|---------------------|
| `qwen2.5-coder-7b-instruct` | ~5 GB | ✅ **Recommended** |
| `qwen2.5-coder-14b-instruct` | ~9 GB | ✅ Excellent |
| `deepseek-coder-6.7b-instruct` | ~4 GB | ✅ Good with grammar |
| `codellama-13b-instruct` | ~8 GB | ✅ Decent quality |

**Note:** While grammar constraints significantly improve JSON reliability, smaller models may still struggle with complex multi-file implementations. For production agent usage, larger models (13B+) or cloud providers are recommended.

### Router Mode

When starting llama.cpp without a model argument (router mode), configure the integration's **Model** field with the exact model ID returned by `GET /v1/models`. The bot sends this identifier in each `/v1/completions` request so llama.cpp can route and load the selected model. With a server started using `--model`, llama.cpp serves that model and ignores the request's model identifier.

## Model Download

### Using Hugging Face

Download GGUF models from Hugging Face:

```bash
# Qwen2.5-Coder 7B Instruct (default, recommended)
curl -L -o qwen2.5-coder-7b-instruct-q4_k_m.gguf \
  "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf"

# Qwen2.5-Coder 14B Instruct (better quality, needs more RAM)
curl -L -o qwen2.5-coder-14b-instruct-q4_k_m.gguf \
  "https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct-GGUF/resolve/main/qwen2.5-coder-14b-instruct-q4_k_m.gguf"

# DeepSeek Coder 6.7B Instruct (alternative)
curl -L -o deepseek-coder-6.7b-instruct.Q4_K_M.gguf \
  "https://huggingface.co/TheBloke/deepseek-coder-6.7B-instruct-GGUF/resolve/main/deepseek-coder-6.7b-instruct.Q4_K_M.gguf"
```

### Quantization Levels

| Quantization | Size Reduction | Quality | Use Case |
|--------------|----------------|---------|----------|
| Q8_0 | ~50% | Highest | Best quality, more RAM |
| Q5_K_M | ~65% | Very Good | Good balance |
| Q4_K_M | ~70% | Good | **Recommended** |
| Q3_K_M | ~75% | Acceptable | Limited RAM |
| Q2_K | ~80% | Lower | Very limited RAM |

## Running llama.cpp Server

### With Docker (Recommended)

```bash
# Using the provided compose file
docker compose -f systemtest/docker-compose-llamacpp.yml up -d
```

### Manual Docker Run

```bash
docker run -d \
  --name llamacpp \
  -p 8081:8081 \
  -v /path/to/models:/models \
  ghcr.io/ggml-org/llama.cpp:server \
  --host 0.0.0.0 \
  --port 8081 \
  --model /models/qwen2.5-coder-7b-instruct-q4_k_m.gguf \
  --ctx-size 4096

# For GPU acceleration, add --gpus all and use server-cuda13 image:
docker run -d \
  --name llamacpp \
  -p 8081:8081 \
  -v /path/to/models:/models \
  --gpus all \
  ghcr.io/ggml-org/llama.cpp:server-cuda13 \
  --host 0.0.0.0 \
  --port 8081 \
  --model /models/qwen2.5-coder-7b-instruct-q4_k_m.gguf \
  --ctx-size 4096 \
  --n-gpu-layers -1
```

### Native Installation

```bash
# Clone and build llama.cpp
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
make -j

# Run the server
./llama-server \
  --host 0.0.0.0 \
  --port 8081 \
  --model /path/to/qwen2.5-coder-7b-instruct-q4_k_m.gguf \
  --ctx-size 4096
```

## GPU Acceleration

### NVIDIA CUDA

```bash
# Build with CUDA support
make clean
make GGML_CUDA=1 -j

# Or use the CUDA Docker image
docker run -d \
  --gpus all \
  ghcr.io/ggml-org/llama.cpp:server-cuda13 \
  ...
```

### Apple Metal (macOS)

Metal support is enabled by default on macOS:

```bash
make -j
./llama-server --model model.gguf --n-gpu-layers -1
```

### AMD ROCm

```bash
make GGML_HIPBLAS=1 -j
```

## GBNF Grammar for Agent

The bot automatically uses a GBNF grammar when it detects the agent prompt, constraining the model output to valid JSON:

```
root ::= "{" ws root-content "}" ws
root-content ::= (file-changes-field ws)? (run-tool-field ws)? (message-field ws)? done-field
...
```

This grammar ensures:
- Valid JSON structure
- Reliable structured output for the agent prompts' expected JSON contracts
- Proper nesting and types
- No malformed output

You can verify grammar is active by checking the logs:
```
INFO: llama.cpp chat request: GBNF grammar enabled for structured JSON output
```

## Server Configuration

### Context Size

Adjust `--ctx-size` based on your needs and available memory:

| Context Size | RAM Required (7B Q4) | Use Case |
|--------------|----------------------|----------|
| 4096 | ~8 GB | Small diffs only |
| 8192 | ~10 GB | Medium diffs |
| 16384 | ~14 GB | **Recommended for agent** |
| 32768 | ~22 GB | Very large context |

**Note:** The agent feature requires larger context sizes (16384+) to handle issue descriptions, file contents, and structured JSON output.

### Batch Size

For better throughput with larger prompts:

```bash
./llama-server --batch-size 512 --ubatch-size 256 ...
```

### Parallel Requests

Enable parallel request handling:

```bash
./llama-server --parallel 2 ...
```

## Integration with Docker Compose

Add llama.cpp to your existing `docker-compose.yml`. The bot will connect to it via the API URL configured in the web UI:

```yaml
services:
  llamacpp:
    image: ghcr.io/ggml-org/llama.cpp:server  # or server-cuda13 for GPU
    ports:
      - "8081:8081"
    volumes:
      - ./models:/models
    command: >
      --host 0.0.0.0
      --port 8081
      --model /models/qwen2.5-coder-7b-instruct-q4_k_m.gguf
      --ctx-size 4096
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8081/health || exit 1"]
      interval: 30s
      timeout: 10s
      start_period: 60s
      retries: 5
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
```

Then configure the AI Integration in the web UI with API URL `http://llamacpp:8081` (or `http://localhost:8081` if the bot runs outside Docker).

## Troubleshooting

### Short or Generic Reviews

Local models may produce shorter responses than cloud providers. To get more detailed reviews:

1. **Use the `local-llm` prompt content** — Create or clone a system prompt entry in **System settings → System prompts** and use the bundled `local-llm` prompt text for review-oriented local-model bots. This prompt is designed for local models with more explicit instructions.

2. **Use a larger model** — 14B+ models produce more detailed output

3. **Increase Max Tokens** — Set **Max Tokens** to `8192` in the AI Integration settings via the web UI

### Server Not Starting

Check if the model file exists and is valid:
```bash
docker compose -f systemtest/docker-compose-llamacpp.yml logs llamacpp
```

### Out of Memory

- Use a smaller quantization (Q3_K_M instead of Q4_K_M)
- Reduce context size (`--ctx-size 2048`)
- Use CPU-only mode (`--n-gpu-layers 0`)

### Slow Responses

- Enable GPU acceleration (`--n-gpu-layers -1`)
- Use a smaller model (7B instead of 13B)
- Reduce **Max diff chars per chunk** in the review workflow settings via the web UI

### Connection Refused

Ensure the server is running and healthy:
```bash
curl http://localhost:8081/health
```

### Invalid JSON from Agent

If the agent still produces invalid JSON despite grammar constraints:
- The model may be too small for the task complexity
- Try a larger model (13B+)
- Check that the grammar is being applied (see logs)

## Comparison with Other Providers

| Feature | llama.cpp | Ollama | Anthropic | OpenAI |
|---------|-----------|--------|-----------|--------|
| **Local/Private** | ✅ | ✅ | ❌ | ❌ |
| **No API Key** | ✅ | ✅ | ❌ | ❌ |
| **Grammar Constraints** | ✅ | ❌ | ❌ | ❌ |
| **Agent Reliability** | Good | Poor | Excellent | Excellent |
| **Setup Effort** | Medium | Easy | Easy | Easy |
| **Cost** | Hardware only | Hardware only | Per-token | Per-token |

## See Also

- [llama.cpp GitHub](https://github.com/ggerganov/llama.cpp)
- [llama.cpp Server Documentation](https://github.com/ggerganov/llama.cpp/blob/master/examples/server/README.md)
- [GBNF Grammar Documentation](https://github.com/ggerganov/llama.cpp/blob/master/grammars/README.md)
- [TheBloke's GGUF Models](https://huggingface.co/TheBloke)
- [Using Ollama](OLLAMA.md) — Alternative local LLM option
