# Secret References

Some configuration fields accept a **secret reference** instead of a literal credential. A reference names *where* the value comes from; the value itself is fetched only at the moment it is needed and is never stored in the configuration, the database, or the log.

```
${env:GITHUB_MCP_TOKEN}
  │    └── key — what to look up in that source
  └─────── source type — where to look it up
```

Nothing in the configuration changes shape: a reference is written where the literal credential used to stand, and the field stays a plain string.

## Where references work

| Feature | Fields | Guide |
|---|---|---|
| MCP servers | `authorization_token` (and its `authorizationToken` / `token` aliases), every `headers` value | [MCP Server Handling](MCP_SERVER_HANDLING.md#secret-references-in-the-mcp-json) |

Fields not listed here treat `${env:NAME}` as ordinary text. Endpoint URLs in particular do **not** support references, because they are written to the log for diagnostics.

## Syntax

| Form | Meaning |
|---|---|
| `${type:key}` | A reference. Whitespace around `type` and `key` is ignored, so `${ env : MY_TOKEN }` is the same reference. |
| `$${type:key}` | An escape. The literal text `${type:key}` is used, and nothing is resolved. |
| `Bearer ${env:TOKEN}` | References may be mixed freely with literal text and with other references. |

The source type is lower case. `${ENV:TOKEN}` is not a reference and is used as literal text.

## How a reference is resolved

A reference is resolved **when the value is actually used** — for MCP servers, while the outgoing HTTP request is being built — and never earlier. Consequences worth knowing:

- The plain secret exists only for the duration of that one request. It is not persisted with the configuration, not held in any cache, and not written to any log.
- Changing the variable in the environment changes what the next request sends; the configuration does not have to be touched. (Restart the application to pick up a value that was missing when a remote server's capabilities were first discovered.)
- A reference is resolved on every use, so rotating a credential does not require re-saving anything.

### When a reference cannot be resolved

| Situation | Result |
|---|---|
| The key is unknown to the source, or the source type does not exist | The reference is used **as written** (`Bearer ${env:TOKEN}`), so the remote side answers with its usual authentication error rather than the bot silently sending nothing |
| The key is not a valid name for that source | The operation fails with a `KeyResolveException`, visible in the log and in the result the agent sees |
| The key names a value the application reserves for itself | The operation fails the same way — see [Reserved names](#reserved-names) |

Failing open on an unknown key is deliberate: an unresolved reference produces a clear `401` from the remote service, which is easier to diagnose than a request that quietly went out without credentials.

## Source type: `env`

The `env` source reads the environment of the running application.

```json
"authorization_token": "${env:GITHUB_MCP_TOKEN}"
```

**Only whitelisted variables are readable.** `GITEABOT_SECRET_ENV_WHITELIST` (property `giteabot.secret.env.whitelist`) is a comma-separated list of the variable names that `${env:NAME}` may read. It is **empty by default**, which means no environment variable is readable at all — the feature is opt-in per variable:

```yaml
environment:
  GITEABOT_SECRET_ENV_WHITELIST: GITHUB_MCP_TOKEN,MY_API_KEY
  # whitelisting only grants permission to read - the variables themselves
  # must also be passed into the container
  GITHUB_MCP_TOKEN: ${GITHUB_MCP_TOKEN:-}
  MY_API_KEY: ${MY_API_KEY:-}
```

Details:

- Names are matched **verbatim and case-sensitively**, in both the whitelist and the reference, because environment variables are case-sensitive on Linux. Surrounding whitespace in the whitelist is trimmed, so `A, B` whitelists `B`.
- A name must start with a letter or underscore and may contain letters, digits and underscores. Anything else (`${env:MY-VAR}`) fails the operation rather than being sent.
- A whitelisted variable that is not set resolves to nothing, and the reference is used as written.

### Reserved names

The variables the application reads for its own configuration can never be referenced, so an MCP configuration — or any other configuration accepting references — cannot be used to send the application's own credentials to a third party.

| Pattern | Covers |
|---|---|
| `GITEABOT_*` | `GITEABOT_SECURITY_OAUTH_CLIENT_SECRET`, `GITEABOT_SECURITY_AUTO_LOGIN_PASSWORD`, `GITEABOT_SECRET_ENV_WHITELIST`, … |
| `SPRING_*` | Spring's relaxed binding of any application property, e.g. `SPRING_DATASOURCE_PASSWORD` |
| `DATABASE_*` | `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` |
| `APP_ENCRYPTION_KEY` | The key that encrypts stored API keys and tokens |

- Listing a reserved name in `GITEABOT_SECRET_ENV_WHITELIST` **prevents the application from starting**, with an error naming the offending entries. Move the credential into a differently named variable.
- Patterns match the whole name, so `MY_DATABASE_PASSWORD` and `APP_ENCRYPTION_KEYS` are ordinary names you may whitelist.
- A `${env:NAME}` reference naming a reserved variable fails the operation instead of being sent.

### Operator guidance

The whitelist is the security boundary. Anyone who can edit a configuration that accepts references can also choose the endpoint the resolved value is sent to, so **whitelist only variables that are meant to travel to that endpoint**. Reserved names cover the application's own secrets; anything else you add is your decision.

An unresolved reference is transmitted as written, which discloses the *name* of the variable — never its value — to the remote side.

## Adding a source type

The `${type:key}` grammar is not tied to the environment. A new backend (Vault, a file, a cloud secret manager) is added by implementing `SecretSource` in `org.remus.giteabot.secret` and publishing it as a Spring bean:

```java
@Component
public class VaultSecretSource implements SecretSource {

    @Override
    public String type() {
        return "vault";   // makes ${vault:path/to/secret} resolvable
    }

    @Override
    public Optional<SecretValue> resolve(String key) {
        // Optional.empty() -> unresolved, the reference is used as written
        // KeyResolveException -> the key is invalid or refused, the operation fails
    }
}
```

`SecretSourceRegistry` picks the bean up automatically and routes references by `type()`. No consumer of a reference — MCP or otherwise — needs to change: fields already hold a `SecretTemplate`, which resolves whatever source types are registered at the time it is used.

The contract a source must honour:

| Return | Meaning |
|---|---|
| `Optional.of(value)` | Resolved; the value replaces the reference |
| `Optional.empty()` | Not found, or not permitted — the reference is used as written |
| throw `KeyResolveException` | The key is malformed or refused — the whole operation fails, and the message is shown to the operator |

## See also

- [Deployment](DEPLOYMENT.md#secret-references-optional) — the environment variables involved
- [MCP Server Handling](MCP_SERVER_HANDLING.md#secret-references-in-the-mcp-json) — the first consumer, with a full example
