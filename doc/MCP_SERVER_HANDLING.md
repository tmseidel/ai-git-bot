# MCP Server Handling

This document describes how MCP servers are configured, how tool whitelisting works, where selected tools are shown in bot configuration, and how MCP calls are made transparent during issue implementation.

## Scope

- MCP servers are configured in **System settings → MCP configurations**.
- Only remote transports are supported (HTTP/HTTPS/WS/WSS/SSE).
- Local `stdio` MCP transport is intentionally rejected.

<img src="doc/screenshots/mcp/screenshot_system_settings.png" alt="System Settings with MCP-Configuration" width="600"/>

## 1) Create or Edit an MCP Configuration

1. Open **System settings → MCP configurations**.
2. Click **Add** or **Edit**.
3. Fill in:
   - **Name**
   - **MCP JSON** (remote server definitions)
4. Click **Save and select tools**.

   <img src="doc/screenshots/mcp/screenshot_mcp_tools.png" alt="MCP-Configuration" width="600"/>

After save, the application validates the JSON and discovers tools from all configured MCP servers.

### Example JSON

```json
[
  {
    "name": "github",
    "type": "url",
    "url": "https://api.githubcopilot.com/mcp/",
    "authorization_token": "<token>"
  }
]
```

You can also use object-style server definitions (for example with `mcpServers`) as long as remote endpoints are configured.

## 2) Select MCP Tools (Whitelist)

After saving an MCP configuration, the tool-selection page opens automatically.

You can also open it any time without changing JSON:

- **System settings → MCP configurations → Tools**
- **Edit MCP configuration → Select tools**

The table supports:

- filtering by text
- server filter
- sorting by server/tool/title
- paging and configurable page size
- per-row checkbox selection
- **select all visible** in the table header

Only selected tools are persisted and appended to agent system prompts.

Unselected tools remain hidden from the AI agent.

<img src="doc/screenshots/mcp/screenshot_mcp_tool_selection.png" alt="MCP-Tools selection" width="600"/>

## 3) View Selected Tools in Bot Configuration

In **Bots → New/Edit**, next to the MCP configuration dropdown, use **Details**.

The details dialog shows a read-only list of the selected tools for that MCP configuration:

- server
- tool name
- title
- description

This is based on the persisted whitelist selection.

<img src="doc/screenshots/mcp/screenshot-bot-configuration-selected-tools.png" alt="Selected MCP-Tools for the bot" width="600"/>


## 4) How Prompt Exposure Works

At runtime, the application discovers MCP tools and then applies the persisted whitelist before rendering MCP tool entries into prompts.

Result: the agent only sees and can call whitelisted MCP tools.

## 5) Transparency of MCP Calls During Issue Work

### Current behavior

- MCP tool calls are executed by the issue/writer agent loop.
- MCP tools are handled as silent tools (same category as internal context/file tools).
- Issue comments are not spammed with per-call MCP request/response payloads.

### What is visible in issue comments

- high-level agent progress/comments
- validation command feedback (where applicable)

### Where to inspect exact MCP call behavior

Use application logs for detailed MCP transparency:

- transport/connection attempts
- initialization/discovery diagnostics
- MCP tool execution failures

This keeps issue threads readable while still allowing technical traceability in logs.

<details>
<summary>📸 Screenshots: MCP-Tool selection transparency in Issue-comments</summary>

**Gitea:**
<img src="doc/screenshots/mcp/screenshot-gitea-mcp-tools.png" alt="Gitea Implementation Agent with MCP-Calls" width="600"/>

</details>

