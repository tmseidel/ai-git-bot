# Migration Guide: AI-Git-Bot 1.13 → 1.14

> **Target audience:** operators upgrading an existing 1.13.x deployment to
> the 1.14.0 release, which adds two new CONTEXT tools for code-base structure
> extraction and upgrades the Docker runtime image to Ubuntu Noble.

This release adds two agent-invokable tools —
`ctags-signatures` and `ctags-deps` — that let the AI agent extract
function/class/method signatures and dependency graphs from source files using
Universal Ctags. A new `universal-ctags` package is required in the runtime
image. The tools are automatically enabled in the **default** bot tool
configuration but must be activated manually for any **custom** tool
configurations.

If you are upgrading from 1.12, also read
[`MIGRATION_1.12_TO_1.13.md`](./MIGRATION_1.12_TO_1.13.md) first.

---

## TL;DR — for impatient operators

1. **Replace the Docker image.** The new image includes `universal-ctags`
   (Ubuntu Noble package). No other runtime dependency changes are needed.
2. **Flyway applies `V29` on first boot.** It adds `ctags-signatures` and
   `ctags-deps` to the **default** tool configuration. Bots using the default
   configuration gain access to the new tools immediately.
3. **Custom tool configurations are NOT touched.** If you created your own
   tool configuration (anything other than the system-provided "Default"),
   you must manually enable the two new tools via **System settings →
   Tool configurations**. See § 2 below.

---

## 1. What changed

| Area | 1.13.x | 1.14.0 |
|---|---|---|
| Context tools available | `rg`, `find`, `cat`, `git-log`, `git-blame`, `tree`, `branch-switcher` | Same as 1.13 + `ctags-signatures` and `ctags-deps` |
| Default tool configuration | V12 seed (15 built-in tools) | V29 extends default with 2 additional CONTEXT tools |
| Custom tool configurations | Not affected by V12 or V29 (admins manage them) | **Not affected by V29** — must be updated manually |
| Docker runtime base | `eclipse-temurin:21-jre-noble` | Same base image (no change), but `universal-ctags` package installed |
| Application config | No new properties | No new properties required |

### New tools

| Tool | Kind | Roles | Description |
|---|---|---|---|
| `ctags-signatures` | CONTEXT | CODING, WRITER | Extract function, class, method, and interface signatures from a source file. Returns a compact structural map — no implementation details. |
| `ctags-deps` | CONTEXT | CODING, WRITER | Extract imports, includes, and namespace/package declarations from a source file. Returns JSON with declared namespace and all external dependencies. |

Both tools are **silent** (output is never posted as a public comment) and
require a single argument: the repository-relative file path.

---

## 2. Enabling the new tools on custom configurations

### 2.1 Default configuration — no action needed

Flyway `V29` extends the **default** tool configuration automatically.
Bots that use the system-provided "Default" configuration (every bot that
was never explicitly assigned a different configuration) gain access to
`ctags-signatures` and `ctags-deps` on first boot after upgrade.

You can verify this in **System settings → Tool configurations**:
the "Default" row should list both `ctags-signatures` and `ctags-deps`
under the CONTEXT section.

### 2.2 Custom configurations — manual activation required

Flyway `V29` does **not** touch non-default tool configurations. This is
intentional: operators who maintain custom configurations should control
exactly which tools their bots can invoke.

For each custom tool configuration you maintain:

1. Open **System settings → Tool configurations**.
2. Click the configuration name to open its editor.
3. In the **CONTEXT tools** section, check the boxes for:
   - `ctags-signatures`
   - `ctags-deps`
4. Save.

Bots assigned to that configuration will gain access to the new tools
immediately (no restart required).

### 2.3 Checking which configurations need updating

Run this query against your database to list all non-default
configurations and whether they include the new tools:

```sql
-- PostgreSQL
SELECT c.name,
       c.default_entry,
       COUNT(s.tool_name) FILTER (WHERE s.tool_name IN ('ctags-signatures', 'ctags-deps')) AS new_tools_present
FROM bot_tool_configurations c
LEFT JOIN bot_tool_selections s ON s.configuration_id = c.id
WHERE NOT c.default_entry
GROUP BY c.id, c.name, c.default_entry
ORDER BY c.name;
```

Any configuration with `new_tools_present < 2` needs manual updating.

```sql
-- H2
SELECT c.name,
       c.default_entry,
       COUNT(CASE WHEN s.tool_name IN ('ctags-signatures', 'ctags-deps') THEN 1 END) AS new_tools_present
FROM bot_tool_configurations c
LEFT JOIN bot_tool_selections s ON s.configuration_id = c.id
WHERE NOT c.default_entry
GROUP BY c.id, c.name, c.default_entry
ORDER BY c.name;
```

---

## 3. Docker image changes

### 3.1 New runtime dependency: `universal-ctags`

The `universal-ctags` package is installed in the runtime image
(`apt-get install universal-ctags`). It provides 135+ language parsers
and is invoked by the new tools via `ProcessBuilder`.

If you build your own Docker image, add the package to your `apt-get`
step:

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends \
        universal-ctags \
        ...
```

### 3.2 No base image change

The runtime base image remains `eclipse-temurin:21-jre-noble`. No
additional `FROM` changes are required.

### 3.3 Image size impact

The `universal-ctags` package adds approximately 2 MB to the runtime
image. Total image size increase is negligible.

---

## 4. Database migration

Flyway migration **`V29`** adds two rows to the default tool
configuration's selection table. It is idempotent — running the
migration multiple times (e.g., after a `flyway repair`) is safe.

```sql
INSERT INTO bot_tool_selections (configuration_id, tool_name, tool_kind)
SELECT c.id, v.tool_name, v.tool_kind
FROM bot_tool_configurations c
CROSS JOIN (VALUES
    ('ctags-signatures', 'CONTEXT'),
    ('ctags-deps',       'CONTEXT')
) AS v(tool_name, tool_kind)
WHERE c.default_entry = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM bot_tool_selections s
      WHERE s.configuration_id = c.id AND s.tool_name = v.tool_name
  );
```

**No other tables, columns, or constraints are modified.** The migration
only inserts rows; it never deletes or alters existing data.

**Rollback.** If you roll back the application binary after V29 has
executed, the two new rows will remain in `bot_tool_selections`. The
1.13.x application's `ToolCatalog` does not register these tool names,
so they will appear as `ToolKind.UNKNOWN` and be rejected by the
whitelist enforcement in `AgentToolRouter`. No runtime errors occur,
but the rows are harmless dead data. You can delete them manually if
desired:

```sql
DELETE FROM bot_tool_selections WHERE tool_name IN ('ctags-signatures', 'ctags-deps');
```

---

## 5. Environment variable and config changes

**No new application properties are required.** The new tools use the
existing `agent.validation.tool-timeout-seconds` timeout when invoking
ctags. No new `application.properties` or `application.yml` keys are
introduced.

---

## 6. Behaviour notes

- **Tools are opt-in per bot.** A bot only has access to the new tools
  if its assigned tool configuration includes them. Bots using the
  default configuration get them automatically; bots with custom
  configurations need manual activation.
- **ctags must be on PATH.** The runtime image includes `universal-ctags`
  at `/usr/bin/ctags`. If ctags is missing, the tools return a non-zero
  exit code error to the AI agent (same pattern as `git-log` and
  `git-blame` when `git` is unavailable).
- **Silent tools.** Like all CONTEXT tools, `ctags-signatures` and
  `ctags-deps` never appear in public PR/issue comments. Their output is
  visible only to the AI agent.
- **No UI changes.** The tools appear in the System Settings → Tool
  Configurations editor under the CONTEXT section, alongside the existing
  `rg`, `find`, `cat`, `tree`, etc. No new UI pages or forms are added.

---

## 7. See also

- [`MIGRATION_1.12_TO_1.13.md`](./MIGRATION_1.12_TO_1.13.md) — previous
  migration guide (diff chunking settings).
- [`MIGRATION_1.6_TO_1.7.md`](./MIGRATION_1.6_TO_1.7.md) — tool
  configurations introduction (V11/V12 migrations).
- [`doc/development-archive/code-base-truncation.md`](./development-archive/code-base-truncation.md) —
  implementation plan for the code-base structure extraction tools.
