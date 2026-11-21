# Binary Assets: download-file, upload-file, file-info

The coding agent can move **binary files** (images, PDFs, audio, fonts, …) between the
repository workspace and HTTPS endpoints without the bytes ever passing through the model.
The server fetches or sends the file; the model only sees the path, size, MIME type and
SHA-256.

Typical use: an MCP server generates an asset and returns its URL. The agent stores it with
`download-file`, runs validation, and the bot opens a pull request — the same reviewable,
auditable flow as for code.

| Tool | Kind | Roles | What it does |
|---|---|---|---|
| `download-file(url, path, overwrite?)` | `FILE` | coding | Fetches an HTTPS URL into the workspace. Parent directories are created; an existing file is replaced only with `overwrite=true`. |
| `upload-file(path, url, method?, contentType?)` | `FILE` | coding | Sends a workspace file to an HTTPS upload URL (typically pre-signed). `method` is `PUT` (default) or `POST`; `contentType` defaults to the detected type. |
| `file-info(path)` | `CONTEXT` | coding, writer, review | Returns size, MIME type (detected from the bytes) and SHA-256 of a workspace file. |

`download-file` and `upload-file` are **opt-in**: they are never part of the Default tool
configuration (see [Bot Tool Configurations](BOT_TOOL_CONFIGURATIONS.md)). Select them in a
tool configuration and attach it to the bot. Neither is callable from an `execute-code`
program; `file-info` is, since it is read-only.

## Configuration

Global only — there are no per-bot overrides. Whether a bot may use the tools at all is
decided by its tool configuration.

| Property | Environment variable | Default |
|---|---|---|
| `agent.file-transfer.download.allowed-hosts` | `AGENT_FILE_TRANSFER_DOWNLOAD_ALLOWED_HOSTS` | *(empty — tool disabled)* |
| `agent.file-transfer.download.allowed-paths` | `AGENT_FILE_TRANSFER_DOWNLOAD_ALLOWED_PATHS` | `**` |
| `agent.file-transfer.download.max-file-size` | `AGENT_FILE_TRANSFER_DOWNLOAD_MAX_FILE_SIZE` | `25MB` |
| `agent.file-transfer.download.allowed-content-types` | `AGENT_FILE_TRANSFER_DOWNLOAD_ALLOWED_CONTENT_TYPES` | *(empty — any type)* |
| `agent.file-transfer.upload.allowed-hosts` | `AGENT_FILE_TRANSFER_UPLOAD_ALLOWED_HOSTS` | *(empty — tool disabled)* |
| `agent.file-transfer.upload.allowed-paths` | `AGENT_FILE_TRANSFER_UPLOAD_ALLOWED_PATHS` | *(empty — tool disabled)* |
| `agent.file-transfer.upload.max-file-size` | `AGENT_FILE_TRANSFER_UPLOAD_MAX_FILE_SIZE` | `25MB` |
| `agent.file-transfer.max-redirects` | `AGENT_FILE_TRANSFER_MAX_REDIRECTS` | `3` |
| `agent.file-transfer.allow-private-addresses` | `AGENT_FILE_TRANSFER_ALLOW_PRIVATE_ADDRESSES` | `false` |

- **Host patterns:** an exact host (`files.example.com`) or a suffix wildcard
  (`*.example.com`, which matches any subdomain but not `example.com` itself).
- **Path patterns:** globs relative to the repository root (`assets/**`, `inputs/*.jpg`).
- **Timeouts** reuse `agent.validation.tool-timeout-seconds`.
- A tool whose allowlist is empty refuses every call with a message naming the property, and
  the tool-selection page marks it as *disabled until … is configured*.

Example for an asset repository:

```env
AGENT_FILE_TRANSFER_DOWNLOAD_ALLOWED_HOSTS=*.media-provider.example
AGENT_FILE_TRANSFER_DOWNLOAD_ALLOWED_PATHS=assets/**
AGENT_FILE_TRANSFER_DOWNLOAD_ALLOWED_CONTENT_TYPES=image/png,image/jpeg,image/webp
AGENT_FILE_TRANSFER_UPLOAD_ALLOWED_HOSTS=upload.media-provider.example
AGENT_FILE_TRANSFER_UPLOAD_ALLOWED_PATHS=inputs/**
```

## Provenance trailers

Every successful download is recorded by the server for the current run. When the bot
commits, it appends one trailer per downloaded file that is still present and unchanged:

```text
agent: implement #123 - Add hero images

Asset-Source: assets/request-123/01.png https://files.media-provider.example/a1b2c3.png sha256:3f2a…
```

- The URL is recorded **without query string and fragment** (pre-signed URLs carry credentials).
- A file downloaded and later deleted or changed by another tool gets no trailer; a file
  downloaded twice gets the trailer of the last download.
- The trailer comes from the server's own record, so the model can neither forge nor omit it.
  Read it back with `git log --format='%(trailers:key=Asset-Source)'`.

## Security

Issue and pull-request content can steer the agent, so both transfer tools assume hostile
arguments.

**Both tools**

- HTTPS only; no user info in the URL; the host must match the allowlist.
- DNS answers containing a loopback, private, link-local (including cloud metadata such as
  `169.254.169.254`), CGNAT, multicast, unique-local or reserved address are refused unless
  `allow-private-addresses=true`. The check runs inside the HTTP client's DNS resolver, so the
  connection goes to exactly the address that was validated — there is no second lookup a
  DNS-rebinding attack could change.
- No cookies, credentials, proxy or automatic retries are used.
- Query strings are never logged, posted in plan comments or returned to the model.
- Each transfer is logged at INFO with host, repository path, size and SHA-256.

**download-file**

- At most `max-redirects` redirects; every hop is checked like the first URL.
- `max-file-size` is enforced while streaming (`Content-Length` is not trusted). The body is
  written to a temporary file next to the target and moved into place atomically; a failed or
  oversized download leaves nothing behind.
- The target must be inside the workspace, outside `.git/`, not reached through a symlink and
  must match `download.allowed-paths`.
- With `allowed-content-types` set, the type detected from the file's leading bytes — not the
  response header — must be listed.

**upload-file** — the more dangerous tool: a manipulated issue could try to send repository
content to an attacker.

- Both `upload.allowed-hosts` and `upload.allowed-paths` must be set; there is no default.
- Files under `.git/` and files ignored by `.gitignore` (a common place for secrets) are never
  uploaded.
- Only `307`/`308` redirects to the **same** host are followed; any other redirect fails.
- `upload.max-file-size` is enforced.

## Interaction with the agent loop

- `download-file` changes the workspace, so it counts as an implementation attempt and
  validation is still required when enabled. For asset repositories a trivial committed
  validation script (`exit 0`) or a real check (types, dimensions, metadata) run via `execute`
  satisfies that.
- `upload-file` does not change the workspace and is not counted as an implementation attempt.
