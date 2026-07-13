# Unit Tests

> Workflow key: **`unit-test-author`** · Opt-in per bot.

## The problem it solves

New code lands without tests all the time — the change works today, but nothing
guards it against tomorrow's regression. **Unit Tests** automatically writes
focused unit tests for the code a pull request changes, runs them with your
project's own test runner, and commits the passing tests onto the PR branch. You
get a regression safety net on the change while it's still fresh, without anyone
stopping to write the tests by hand.

## What it does

1. Detects the changed code in the pull request.
2. Figures out your project's build/test toolchain (or uses the one you pin).
3. Writes focused unit tests for the change — happy paths, edge cases, error
   handling — placing them next to your existing tests.
4. Commits the new tests onto the PR branch (or just reports them — your
   choice).
5. Runs the suite with your project's own runner and posts a result + coverage
   comment on the PR.

The bot **only adds test files** — it never touches production code. Every file
it writes must live in a legitimate test location for your toolchain; anything
else is rejected before it can be committed.

## Supported toolchains

The toolchain is auto-detected from your repository by default, or you can pin
it explicitly:

| Toolchain | Detected from | Runs |
|---|---|---|
| `maven` | `pom.xml` | `mvn test` |
| `gradle` | `build.gradle(.kts)` | `gradle test` |
| `npm` | `package.json` | `npm test` |
| `pytest` | `pyproject.toml` / `pytest.ini` / `setup.py` / `tox.ini` | `pytest` |
| `go` | `go.mod` | `go test ./...` |
| `cargo` | `Cargo.toml` | `cargo test` |
| `dotnet` | `*.csproj` / `*.sln` | `dotnet test` |
| `bundle` | `Gemfile` | `bundle exec rake test` |
| `make` | `Makefile` | `make test` |
| `gcc` / `g++` | `Makefile` + `*.c` / `*.cpp` | `make test` |

Coverage is best-effort: if a coverage report can't be found or parsed, the
comment simply omits the coverage line — it never fails the run.

## Settings

Set these on **System settings → Workflow configurations → Workflows → AI Unit
Tests**.

| Setting | Type | Default | What it controls |
|---|---|---|---|
| `framework` | enum | `auto` | Toolchain to use. `auto` detects it; otherwise pick one from the table above. |
| `maxRetries` | integer | `1` | How many times to re-run a failing suite before reporting it failed (0–5). |
| `maxTestCases` | integer | `10` | Cost guard on how many test files to generate. Hard-capped at 50. |
| `suiteLifecycle` | enum | `commit-to-pr` | `commit-to-pr` commits the generated tests onto the PR branch; `ephemeral` runs and reports them without committing. |

## Running it

- **Automatically** on PR open / update, when enabled on the bot.
- **On command** in a PR comment:
  - `@bot generate-tests` — generate and run the suite for the PR.
  - `@bot rerun-unit-tests` — regenerate and re-run on the current head.

## The author prompt

The bot's role description is the operator-editable **Unit-Test Author
System-Prompt** under **System settings → System prompts**. Edit it to steer how
tests are written; the technical details of writing and running are handled by
the software and are not part of this prompt.

## Enabling it

1. Open **System settings → Workflow configurations → Workflows**.
2. Tick **AI Unit Tests** and adjust its settings.
3. Assign the configuration to the bot.

It only runs for bots that have it explicitly enabled.

## See also

- [PR Workflows overview](PR_WORKFLOWS.md)
- [Full-Stack QA](PR_WORKFLOWS_E2E.md) — the end-to-end (browser) testing sibling.
