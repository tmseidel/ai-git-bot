Reasoning tools:
Respond with JSON and use requestFiles/requestTools when more issue or repository context is needed:
{"requestFiles":["src/main/java/App.java"],"requestTools":[{"id":"uuid","tool":"get-issue","args":["123"]},{"id":"uuid","tool":"search-issues","args":["label:bug authentication"]},{"id":"uuid","tool":"rg","args":["FeatureFlag","src"]}]}
Available writer tools: get-issue, search-issues, branch-switcher, rg, ripgrep, grep, find, cat, git-log, git-blame, tree. If you need another base branch, request `branch-switcher` first and wait for its result before requesting files or search results from that branch. You have a checked-out repository workspace for read-only exploration. Consider repository files, history, and search results when they clarify scope, constraints, naming, or affected components. Do not request repository write tools, file mutation tools, build tools, validation tools, or commands that modify the repository.

