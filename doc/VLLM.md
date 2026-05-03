# Using vLLM

This guide covers running AI-Git-Bot with [vLLM](https://docs.vllm.ai/) through vLLM's OpenAI-compatible Chat Completions API. vLLM is useful when you want self-hosted or local inference with an API shape similar to OpenAI while keeping AI-Git-Bot's provider configuration separate from OpenAI.

## Overview

AI-Git-Bot's `vllm` provider sends review and chat requests to:

```text
POST <API URL>/v1/chat/completions
```

The provider works without an API key by default. If your vLLM server is configured to require an API key, enter it in the AI Integration form and AI-Git-Bot sends it as:

```text
Authorization: Bearer <key>
```

Streaming responses, embeddings, model discovery, and non-chat endpoints are not used.

## Configuration in AI-Git-Bot

All AI provider settings are configured through the **web UI** under **AI Integrations**:

1. Go to **AI Integrations → New Integration**
2. Select **vllm** as the provider type
3. Set the **API URL** to your vLLM server, for example:
   - `http://localhost:8000` when AI-Git-Bot runs on the same host
   - `http://vllm:8000` when both services share a Docker network
4. Enter the **Model** name exactly as served by vLLM
5. Leave **API Key** blank for unsecured local servers, or enter the bearer token required by your vLLM server
6. Adjust **Max Tokens**, **Max Diff Chars Per Chunk**, and retry settings for your model's context window
7. Click **Save**

Default values:

| Setting | Default |
|---------|---------|
| Provider type | `vllm` |
| API URL | `http://localhost:8000` |
| API key | Optional |

## Environment Variable Examples

AI-Git-Bot stores AI integrations in the database and normally does not require AI-provider environment variables. If you externalize configuration for deployment templates, use names that match the application defaults:

```bash
AI_VLLM_API_URL=http://localhost:8000
AI_VLLM_API_KEY=
```

For vLLM itself, start the server with the model you want to serve. If you enable API-key enforcement in vLLM, set the same token in AI-Git-Bot's **API Key** field.

## Starting a Compatible vLLM Server

A minimal host-based example:

```bash
python -m vllm.entrypoints.openai.api_server \
  --host 0.0.0.0 \
  --port 8000 \
  --model <your-chat-model>
```

Then configure AI-Git-Bot:

| Field | Value |
|-------|-------|
| Provider Type | `vllm` |
| API URL | `http://localhost:8000` |
| Model | `<your-chat-model>` |
| API Key | blank unless your vLLM server requires one |

With bearer-token authentication enabled on the vLLM side, configure the same key in AI-Git-Bot:

```bash
# Example only; use your deployment's supported vLLM auth option
AI_VLLM_API_KEY=test-token
```

AI-Git-Bot will send `Authorization: Bearer test-token` only when the key is configured.

## Docker Notes

This repository does not include a `docker-compose-vllm.yml` because vLLM deployments vary significantly by GPU runtime, model size, storage, and hardware assumptions. A portable compose file would likely be misleading.

If you run vLLM in Docker, ensure:

- The vLLM container exposes port `8000`
- AI-Git-Bot can reach the container over the configured network
- The configured model name in AI-Git-Bot matches the model served by vLLM
- GPU drivers/runtime are installed when required by your model

Example AI-Git-Bot API URL when services share a Docker Compose network:

```text
http://vllm:8000
```

## Model Guidance

vLLM can serve many chat-capable models, but compatibility depends on the model, tokenizer, chat template, context length, and vLLM version. Use a model that supports chat/instruction-following and verify that `/v1/chat/completions` works with your served model before connecting AI-Git-Bot.

For code reviews, smaller coding or instruction models may be sufficient. For issue implementation and writer-agent workflows, prefer larger models with strong instruction following and reliable structured JSON output. If a local model returns malformed JSON in agent workflows, reduce prompt sizes or use a stronger model.

## Troubleshooting

### 401 or 403 responses

- Leave the AI-Git-Bot API Key blank when the vLLM server is unsecured
- Enter the vLLM bearer token in the AI-Git-Bot API Key field when the server requires authentication
- Confirm proxies do not strip the `Authorization` header

### Model not found

- Confirm the AI-Git-Bot **Model** field exactly matches the model served by vLLM
- Test the vLLM endpoint directly with a small `/v1/chat/completions` request

### Prompt too long / context length errors

- Reduce **Max Diff Chars Per Chunk**
- Reduce **Max Diff Chunks**
- Increase the vLLM server/model context length if your deployment supports it
