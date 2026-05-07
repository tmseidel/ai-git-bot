# GitHub MCP system test

In the web UI:

1. Create an AI integration for any supported provider.
2. Go to **System settings** and create an MCP configuration similar to:

   ```json
   [
     {
       "name": "github",
       "type": "url",
       "url": "https://api.githubcopilot.com/mcp/",
       "authorization_token": "<your GitHub Copilot MCP/OAuth access token>"
     }
   ]
   ```

3. Assign that MCP configuration to a bot.
4. Ask the bot to use code from a GitHub repository that is not otherwise present in the prompt or webhook payload.

No local MCP server needs to be started for this scenario. MCP discovery and tool calls are orchestrated by the application, not by the AI provider. The test succeeds only when the MCP configuration is assigned, the remote GitHub MCP server is reachable, and the bot can use one of the discovered `mcp:<server>:<tool>` tools.

The GitHub Copilot MCP endpoint validates the `Authorization` header strictly. A placeholder value, malformed bearer token, or unsuitable token type can be rejected with HTTP 400 and a response body such as `bad request: Authorization header is badly formatted`. Use a valid token for this protected MCP resource, not the literal placeholder value.

