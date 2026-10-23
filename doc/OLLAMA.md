# Using Ollama

This guide covers running AI-Git-Bot with [Ollama](https://ollama.com) for fully local, private AI-powered code reviews — no external API keys required. This is ideal for self-hosters with compliance requirements who cannot send code to external services.

## ⚠️ Important: Agent Compatibility

**The coding and writer agents have limited support with Ollama.**

Issue-based workflows require the AI to produce reliable structured JSON for context requests, tool requests, clarifying questions, and final outputs.

### Automatic JSON Mode

The bot **automatically enables Ollama's JSON mode** when it detects that the system prompt requests JSON output (e.g., the agent prompt). This significantly improves reliability compared to earlier versions.

However, local LLMs may still:
- Produce incomplete or malformed JSON responses
- Struggle with complex multi-file implementations
- Return simpler JSON than requested

### Recommendations

| Model Size | Agent Compatibility |
|------------|---------------------|
| 1-7B parameters | ❌ **Not recommended** — often fails even with JSON mode |
| 14-16B parameters | ⚠️ **May work** — better results with JSON mode |
| 32B+ parameters | ✅ **Best chance** — JSON mode helps significantly |

For reliable agent usage, use **Anthropic Claude or OpenAI GPT-4/5-class models**. Ollama works well for **code reviews** (PR comments), which only require natural language responses.

If you use smaller Ollama models, prefer review-only bots (no Coding Agent issue workflow). Avoid production writer workflows unless the selected model has proven reliable with structured JSON.

See [Agent Documentation](AGENT.md#ollama-limitations) for more details.

## Overview

Ollama lets you run open-source LLMs locally. The bot connects to Ollama's `/api/chat` endpoint, sending diffs and conversation history just like it would to Anthropic or OpenAI, but everything stays on your machine.

## Quick Start

A ready-to-use Docker Compose environment is provided in `systemtest/`:

```bash
docker compose -f systemtest/docker-compose-ollama.yml up --build -d
```

This starts:
- **Ollama** on port `11434` with a small LLM (`llama3.2:1b` by default)

The `ollama-pull` service automatically downloads the configured model on first start. You can then run the bot separately with the main `docker-compose.yml`.

> **Note:** All AI provider settings (provider type, model, API URL) are configured through the **web UI** under **AI Integrations**. No environment variables are needed for AI configuration.

## Configuration

All AI provider settings are configured through the **web UI**:

1. Go to **AI Integrations → New Integration**
2. Select **Ollama** as the provider type
3. Set the **API URL** to your Ollama instance (e.g., `http://localhost:11434` or `http://ollama:11434` if using Docker networking)
4. Enter the **Model** name (must match a model you've pulled, e.g., `llama3.2:1b`)
5. Adjust **Max Tokens**, **Max Diff Chars Per Chunk**, and other settings as needed for local models
6. Click **Save**

> **Tip:** For local models, consider reducing **Max Diff Chars Per Chunk** to `30000` and **Max Diff Chunks** to `4` to fit within smaller context windows.

### Using with an Existing Docker Compose

Add an Ollama service to your existing `docker-compose.yml`. The bot will connect to it via the API URL configured in the web UI:

```yaml
services:
  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    healthcheck:
      test: ["CMD-SHELL", "ollama list || exit 1"]
      interval: 10s
      timeout: 5s
      start_period: 30s
      retries: 5
    restart: unless-stopped

  ollama-pull:
    image: ollama/ollama:latest
    entrypoint: ["sh", "-c", "sleep 5 && ollama pull llama3.2:1b"]
    environment:
      OLLAMA_HOST: http://ollama:11434
    depends_on:
      ollama:
        condition: service_healthy

volumes:
  ollama_data:
```

Then configure the AI Integration in the web UI with API URL `http://ollama:11434` (or `http://localhost:11434` if the bot runs outside Docker).

### Using with a Host-Installed Ollama

If Ollama is already installed on your host machine:

```bash
# Pull a model
ollama pull llama3.2:1b

# Start the bot
mvn spring-boot:run
```

Then configure the AI Integration in the web UI:
- **Provider type:** Ollama
- **API URL:** `http://localhost:11434`
- **Model:** `llama3.2:1b`

## Recommended Models

### For Code Reviews (PR Comments)

Any model works for code reviews since they only require natural language output:

| Model | Size | Notes |
|---|---|---|
| `llama3.2:1b` | ~1.3 GB | Smallest, fastest — good for testing |
| `llama3.2:3b` | ~2.0 GB | Better quality, still fast |
| `codellama:7b` | ~3.8 GB | Code-focused, better for reviews |
| `deepseek-coder-v2:16b` | ~8.9 GB | High quality code reviews |
| `qwen2.5-coder:7b` | ~4.7 GB | Strong code understanding |

### For Issue-Based Agents (Coding + Writer) — Experimental

Issue-based agents require structured JSON output. **Most local models fail at this**, but larger models have better success rates:

| Model | Size | Agent Compatibility |
|---|---|---|
| `llama3.2:1b` - `llama3.2:3b` | 1-2 GB | ❌ **Not recommended** — fails to produce JSON |
| `codellama:7b` - `codellama:13b` | 4-7 GB | ⚠️ **Unreliable** — sometimes works, often fails |
| `deepseek-coder-v2:16b` | ~8.9 GB | ⚠️ **May work** — better instruction following |
| `qwen2.5-coder:14b` | ~9 GB | ⚠️ **May work** — good at structured output |
| `qwen2.5-coder:32b` | ~20 GB | ✅ **Best chance** — strong instruction following |
| `codellama:70b` | ~40 GB | ✅ **Best chance** — largest, most capable |
| `deepseek-coder:33b` | ~19 GB | ✅ **Best chance** — good JSON compliance |

**Important notes:**
- Even "Best chance" models may occasionally fail to produce valid JSON
- Larger models require significantly more RAM/VRAM (32b needs ~24GB+, 70b needs ~48GB+)
- For production agent usage, **Anthropic Claude or OpenAI GPT-4/5-class models are strongly recommended**
- If you want to experiment with coding or writer agents on Ollama, start with `qwen2.5-coder:32b` or `deepseek-coder:33b`

### Trying Agent with Larger Models

If you want to test the agent with a larger Ollama model:

```bash
# Pull a larger model (requires significant RAM/VRAM)
ollama pull qwen2.5-coder:32b
```

Then update your AI Integration in the web UI to use the new model. For writer bots, test carefully before relying on issue-drafting workflows in production.

Monitor the logs for JSON parsing errors. If you see frequent failures, keep coding bots review-only (no Coding Agent issue workflow) and avoid writer-bot issue workflows with that model.

Choose a model based on your available memory and quality requirements. Smaller models are faster but may produce lower-quality reviews.

### Pulling a Model

```bash
# Via CLI
ollama pull llama3.2:1b

# Or via API
curl http://localhost:11434/api/pull -d '{"name": "llama3.2:1b"}'
```

## Tuning for Local Models

Local models typically have smaller context windows and are slower than cloud APIs. Adjust these settings in the **AI Integration** form in the web UI for best results:

| Setting | Recommended Value | Why |
|---------|-------------------|-----|
| **Max Tokens** | `2048` | Faster responses with local models |
| **Max Diff Chars Per Chunk** | `30000` | Fits in smaller context windows |
| **Max Diff Chunks** | `4` | Reduces processing time |
| **Retry Truncated Chunk Chars** | `15000` | Smaller retry size for limited context |

## GPU Acceleration

For significantly better performance, use Ollama with GPU support:

```yaml
ollama:
  image: ollama/ollama:latest
  deploy:
    resources:
      reservations:
        devices:
          - driver: nvidia
            count: 1
            capabilities: [gpu]
```

This requires the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html).

## Troubleshooting

### Agent JSON Parsing Errors

If you see errors like:
```
ERROR: Failed to parse AI response as JSON: Unexpected character ('@' (code 64))
```

This means the AI returned raw text or code instead of valid JSON. The bot automatically enables Ollama's JSON mode for issue-based agent requests, but smaller models may still fail to produce valid structured output.

**Solutions:**
1. **Use a larger model** (32B+ parameters work best):
   ```bash
   ollama pull qwen2.5-coder:32b
   ```
   Then update the model in your AI Integration via the web UI.
2. **Keep coding bots review-only** (no Coding Agent issue workflow) until the model proves reliable
3. **Avoid writer-bot issue workflows** with that model until it consistently produces valid JSON
4. **Use a cloud provider** (Anthropic Claude, OpenAI GPT-4/5-class models) for reliable agent functionality

You can verify JSON mode is active by checking the logs for:
```
INFO: Ollama chat request: JSON mode enabled for structured output
```

### Model Not Found

If you see errors about the model not being found, pull it first:

```bash
docker compose exec ollama ollama pull llama3.2:1b
```

### Slow Responses

- Use a smaller model (e.g., `llama3.2:1b`)
- Reduce **Max Diff Chars Per Chunk** and **Max Diff Chunks** in the AI Integration settings
- Enable GPU acceleration
- Increase container memory limits

### Connection Refused

Ensure the Ollama service is healthy before the bot starts. The provided Docker Compose uses health checks to handle this automatically.
