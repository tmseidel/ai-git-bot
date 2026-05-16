Reasoning tools:
Repository-exploration and issue-lookup tools are exposed through the model's native function-calling API. Invoke them by issuing tool calls; the bot will return their results in the conversation. Do not emit JSON envelopes for tool use in your text response — only call the tools the API advertises.

You may call read-only repository helpers (e.g. `cat`, `rg`, `tree`, `git-log`, `git-blame`, `find`) and issue-lookup helpers (e.g. `get-issue`, `search-issues`). If you need another base branch, call `branch-switcher` first and wait for its result before invoking the other tools. Do not request repository write tools, file mutation tools, build tools, or commands that modify the repository.

