# Coding Agent: Issue → Pull Request

> Category: **ISSUE** · Issue-assigned workflow: **`issue-coding`** (Coding Agent) · Agent must be **Enabled**.

## The problem it solves

A well-described issue sits in the backlog — someone triaged it, wrote acceptance
criteria, and tagged it for implementation. But nobody has the time to pick it
up, or the fix is straightforward enough that hand-writing it feels like
toil. **Coding Agent** turns those issues into pull requests: it reads the
issue, clones the repository, implements the change, validates it with your
project's own toolchain, and opens a PR for human review.

## What it does

1. Reads the assigned issue and its discussion thread.
2. Clones the repository into an isolated workspace.
3. Explores the codebase to understand the affected areas — using ctags,
   ripgrep, tree inspection, and targeted file reads.
4. Implements the change by editing files, creating new ones, and deleting
   obsolete code as needed.
5. Runs your project's build and test toolchain to validate the change, retrying
   on failure within the configured budget.
6. Pushes a feature branch and opens a pull request.
7. Posts progress, validation output, and completion status as issue comments.

The agent is **read-write within its own workspace**: it can create, edit, and
delete files, run build commands, and push branches. It never auto-merges — a
human must still review and merge the resulting PR.

## Choosing the coding agent

Use the **coding agent** when the issue already describes:

- A clear implementation path,
- Acceptance criteria,
- Expected tests.

Unfocused, ambiguous, or contradictory issues should go to a
[Writer Agent](WRITER_AGENT.md) first.

## Settings

Common agent settings apply to both coding and writer agents. Coding-agent
specific knobs are listed below.

| Setting | Property | Default | What it controls |
|---|---|---|---|
| `agent.enabled` | `AGENT_ENABLED` | `true` | Global coding-agent feature toggle |
| `agent.branch-prefix` | `AGENT_BRANCH_PREFIX` | `ai-agent/` | Prefix for created branches (e.g. `ai-agent/issue-42`) |
| `agent.allowed-repos` | `AGENT_ALLOWED_REPOS` | empty = all | Comma-separated `owner/repo` allow-list |
| `agent.max-files` | `AGENT_MAX_FILES` | `20` | Maximum files the agent may modify |
| `agent.validation.enabled` | `AGENT_VALIDATION_ENABLED` | `true` | Require build/test validation before finishing |
| `agent.validation.tool-timeout-seconds` | `AGENT_VALIDATION_TOOL_TIMEOUT_SECONDS` | `300` | Timeout for each build/test/validation command, including `execute` scripts |
| `agent.validation.allow-yarn-install` | `AGENT_VALIDATION_ALLOW_YARN_INSTALL` | `false` | Let the `yarn` tool install Yarn Classic through npm when the image has no Corepack or Yarn |
| `agent.budget.max-rounds` | `AGENT_BUDGET_MAX_ROUNDS` | `20` | Maximum agent loop rounds |
| `agent.budget.max-validation-retries` | `AGENT_BUDGET_MAX_VALIDATION_RETRIES` | `10` | AI correction attempts after validation failure |
| `agent.context.max-tree-files` | `AGENT_CONTEXT_MAX_TREE_FILES` | `500` | Repository tree entries included in context |
| `agent.context.max-issue-comments` | `AGENT_CONTEXT_MAX_ISSUE_COMMENTS` | `50` | Issue comments included in context |

See the full table in [AGENT.md](AGENT.md#configuration-reference) for the
complete configuration reference including shared agent-budget, context, and
validation knobs.

## Running it

- **Automatically** when a coding bot is assigned to an issue.
- The bot posts a confirmation comment, then begins work. You'll see progress
  updates as it explores, edits, validates, and finally opens the PR or reports
  failure.
- A stuck session can often be resumed by commenting `try again`, `please
  retry`, or `redo` on the issue.

## Tool-calling mode

The coding agent uses the model's native tool-calling API when the AI
Integration has **Enable native tool calling** turned on. Turn it off only when
a provider/model behaves poorly in agentic workflows; this forces the legacy
JSON-in-prompt fallback. See [TOOL_CALLING.md](TOOL_CALLING.md).

## Custom validation scripts (`execute`)

Documentation-only, CI/CD and infrastructure repositories often have no
conventional build/test command, so the agent has nothing to prove a change
with. For those repositories the operator can enable the **`execute`**
validation tool (kind **VALIDATION**) in **System settings → Tool
configurations**, exactly like `mvn` or `npm`. The agent then calls it with the
script's repository-relative path:

```text
execute scripts/validate.sh
```

Contract:

- The script must be committed inside the checked-out repository and must carry
  the **executable bit** (`git update-index --chmod=+x scripts/validate.sh`);
  there is no fallback to a shell interpreter.
- It must also still match that committed version. `execute` refuses a script
  the workspace has modified, staged, added or ignored: the agent has
  `write-file` / `patch-file`, and could otherwise overwrite a failing validation
  script with `exit 0`. Editing the script therefore cannot influence the result —
  fix the code, not the checker.
- Exit code `0` means validation passed. Any non-zero exit code fails the round
  and returns the captured stdout/stderr to the model, exactly like a failing
  `mvn test`; the output is also posted as an issue comment.
- The path is resolved against the workspace with the same guard the file tools
  use: absolute paths, `..` traversal, `.git` internals and symlinked
  directories are rejected, so only a script inside the checkout can run.
- Arguments after the path are forwarded verbatim to the script, blank ones
  included. The script is executed directly — never through a shell — so no
  free-form shell text can be configured.
- The script runs with the workspace as its working directory, with the same
  scrubbed environment and process-group isolation as the other validation
  tools, and it is bounded by `agent.validation.tool-timeout-seconds`.

`execute` is additive: it never replaces the built-in language tooling, and
repositories with a conventional build command should keep using it.

Sandbox limits, stated plainly. The guard decides *which* script may run, that it
is the committed version, and what environment it inherits. It does not confine a
script that itself reaches outside the checkout — the same is true of the Maven
plugins a normal `mvn test` runs. The pre-flight checks (path, committed version,
executable bit) are also not atomic with the launch: the file could be swapped in
between, which is accepted here because only something already inside the
workspace could do it. And the contract assumes a POSIX host — shebangs and an
executable bit have no Windows equivalent.

## Yarn projects (`yarn`)

The coding agent ships **`npm`** and **`yarn`** as separate validation tools
(kind **VALIDATION**). Both appear in **System settings → Tool configurations**,
so a bot for Yarn repositories can enable `yarn` and leave `npm` off — or keep
both and let the tool layer decide, which is what happens by default.

**Detection.** Before every `npm` or `yarn` call the tool inspects the checkout,
most authoritative marker first:

1. the `packageManager` field in `package.json` (`yarn@4.1.1`, `npm@10.5.0`);
2. `yarn.lock` — its `# yarn lockfile v1` header identifies Yarn Classic;
3. a `yarnPath` release committed in `.yarnrc.yml` (Yarn 2+ projects that predate
   Corepack pin their version this way);
4. `package-lock.json` / `npm-shrinkwrap.json`;
5. nothing — an unmarked JavaScript project keeps the previous behaviour, so
   `npm` still works there.

A repository carrying both `yarn.lock` and a stray `package-lock.json` counts as
Yarn.

**Enforcement.** In a Yarn-managed checkout the `npm` tool refuses the call and
tells the model why and what to use instead; the refusal is in the tool layer,
not just in the prompt. That matters because an `npm install` would ignore
`yarn.lock` and write a `package-lock.json`, which the generated pull request
would then carry. npm projects are untouched.

**Version activation.** The model never chooses the Yarn version: it calls
`yarn install`, `yarn add <pkg>`, `yarn add -D <pkg>`, `yarn remove <pkg>`,
`yarn run <script>`, `yarn test`, `yarn audit`. When `packageManager` pins the
version, the tool runs a bare `corepack yarn …`: Corepack resolves the version from
`package.json` and verifies the `+sha512…` integrity hash the repository opted into,
which an explicit `yarn@<version>` would bypass. An explicit `yarn@1` is passed only
for the Classic major inferred from a v1 lockfile. A checkout that vendors its
release through `yarnPath` runs that committed file with `node`, which is what Yarn's
own shim does — this is how many Yarn 2+ projects that predate Corepack pin their
version. Corepack is bundled with Node.js 16+ and enabled in the application image;
downloads happen on demand with the interactive prompt disabled. Yarn Classic and
modern Yarn (2.x–4.x) are supported this way.

Corepack's auto-pin is disabled (`COREPACK_ENABLE_AUTO_PIN=0`), so a Yarn run never
adds a `packageManager` field to `package.json` on its own. A Yarn-managed checkout
with neither a `packageManager` pin nor a `yarnPath` release is refused instead of
being run with Corepack's default: that default is Yarn Classic, which rewrites a
Berry lockfile in v1 format.

Without Corepack the `yarn` on `PATH` is used. As a fallback, Yarn Classic may be
installed through npm (`npm install -g yarn@<version>`). That mutates the image,
so it is off unless the operator sets
`agent.validation.allow-yarn-install=true` (`AGENT_VALIDATION_ALLOW_YARN_INSTALL`);
with the flag off the tool reports what is missing instead of changing anything.
A modern Yarn release is never installed through npm — it is only available
through Corepack.

**Lockfile changes.** `yarn install`, `yarn add` and friends write
`package.json` and `yarn.lock` into the checkout like any other file change, so
`git add -A` and the pull request carry them together.

## Branch naming

Coding-agent branches use the pattern:

```text
{agent.branch-prefix}issue-{issue-number}
```

For example: `ai-agent/issue-42`.

## Enabling it

1. Create an **AI Integration** and a **Git Integration** in the admin UI.
2. Create a bot with **Issue-Assigned Workflow Configuration: Issue: Coding Agent**.
3. Configure your repository webhook to send **Issues** events to the bot URL.
4. Grant the bot repository write access (create branches, push commits, open
   pull requests) and issue write access (progress/error comments).

The bot begins work the next time someone assigns it to an issue.

## Limitations

- Coding works best for focused issues. Broad refactors and multi-system changes
  may be incomplete.
- Large repositories or long issue discussions may exceed the model context
  window; the bot sends bounded context.
- Validation can retry only within configured budgets. If retries are exhausted,
  the bot reports the failure instead of silently continuing.
- The agent can edit dependency files, but cannot guarantee external package
  availability or credentials.
- Small or heavily quantized local/ollama models may produce malformed plans,
  skip validation, or fabricate paths. Production use is recommended with
  Anthropic Claude or OpenAI GPT-4/5 class models.

## See also

- [Issue Agents overview](AGENT.md) — configuration reference and shared settings
- [Writer Agent](WRITER_AGENT.md) — the issue-refinement sibling
- [PR Workflows overview](PR_WORKFLOWS.md)
