# GitHub MCP system test

In the web UI:

1. Create an AI integration for an MCP-capable provider.
2. Go to **System settings** and create an MCP configuration similar to:

   ```json
   [
     {
       "name": "github",
       "type": "url",
       "url": "https://api.githubcopilot.com/mcp/",
       "authorization_token": "<your github token>"
     }
   ]
   ```

3. Assign that MCP configuration to a bot.
4. Ask the bot to use code from a GitHub repository that is not otherwise present in the prompt or webhook payload.

No local MCP server needs to be started for this scenario. The test succeeds only when the MCP configuration is assigned and the remote GitHub MCP server is reachable.
