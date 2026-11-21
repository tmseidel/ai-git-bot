# Migration Guide: AI-Git-Bot 1.31 → 1.32

> **Target audience:** operators upgrading an existing 1.31.x deployment to the 1.32.0
> release, in which the bot learns who it is from its Git integration's token instead of a
> configured username.

Up to 1.31 every Git integration carried a **Username**. The bot used it to recognise
itself in webhooks (its own comments, a review requested from it, an issue assigned to it)
and as its mention alias (`@<username>`). From 1.32 on, the bot asks the Git provider which
account the integration's token authenticates as, and recognises that account by its
immutable account id. The Username field and its database column are gone.

---

## TL;DR — for impatient operators

1. **Before upgrading, check that every integration's token can read its own account**
   (§ 2). A token that cannot makes the bot ignore every webhook after the upgrade.
2. **On Azure DevOps Server, tell your users the new mention alias** (§ 3): `@user`
   instead of `@DOMAIN\user`.
3. **Replace the image.** Flyway applies `V2026_11_16_1200` on first boot and drops
   `git_integrations.username`. The configured usernames are deleted.
4. **After the first start, open Git Integrations** (§ 4). Every integration should
   show its account's username; a dash means it could not be read yet.

---

## 1. What changed

| Area | 1.31.x | 1.32.0 |
|---|---|---|
| Bot identity | The integration's configured Username | The account the integration's token authenticates as, read from the provider and cached for 5 minutes (`giteabot.git-user.cache-ttl`) |
| Recognising the bot in webhooks | By login, compared with the Username | By the account id (Gitea, GitHub, GitLab: numeric id; Bitbucket: `account_id`; Azure DevOps: identity GUID) |
| Mention alias | `@<Username>` | `@<the account's username>`; Bitbucket still uses its `@{account_id}` mention markup |
| Account cannot be determined | Did not happen: the Username was always there | Webhook events are ignored and the failure is shown as the bot's **Last Error** |
| Git integration form | Username field (an e-mail field on Bitbucket) | No Username field. The lists and the dashboard show the account's username |
| Azure DevOps Git over HTTPS | Sent the Username with the token | Sends the token alone. Azure DevOps ignores the username of a PAT |
| Config exchange | `gitIntegrations[].username` exported and required | Not exported. A `username` in an older document is ignored on import |

Renaming the bot's account on the provider is picked up without editing the integration,
and a user who renames themselves to the bot's login is no longer taken for the bot.

---

## 2. Before upgrading: can each token read its own account?

The bot reads its account with one call per provider. Each integration's token needs
permission for it:

| Provider | Call | Permission needed | Typical token that fails |
|---|---|---|---|
| Gitea / Forgejo | `GET /api/v1/user` | User read (`read:user`; the documented `write:user` includes it) | A scoped token with only `write:repository` and `write:issue`. Such a token ran reviews in 1.31 |
| GitHub | `GET /user` | None beyond a valid token | None in practice: classic and fine-grained personal tokens can always read their own user |
| GitLab | `GET /api/v4/user` | `api` or `read_user` | A token without `api`, which could not call the rest of the API either |
| Bitbucket Cloud | `GET /2.0/user` | `read:user:bitbucket` | Already required in 1.31; nothing changes |
| Azure DevOps | `_apis/connectionData`, asked in the event's organization | Access to every organization that sends events | A PAT scoped to fewer organizations than send webhooks |

Two cases are not failures:

- **The e-mail lookups** (`/user/emails` on GitHub and Bitbucket, the identity lookup on
  Azure DevOps) are optional. Without them the bot commits as `ai-agent@bot.local`.
- **On bare `https://dev.azure.com`** the bot cannot ask before it knows an organization.
  It reads the account with the first webhook event, so the lists show a dash until then.

To check a token before upgrading, call the provider with it, for example:

```bash
curl -s -H "Authorization: token $TOKEN" https://gitea.example.com/api/v1/user   # Gitea
curl -s -H "PRIVATE-TOKEN: $TOKEN" https://gitlab.example.com/api/v4/user       # GitLab
```

A JSON object with the account's `id` and `login` / `username` means the token is fine. A
`401` or `403` means it needs the permission above: create a new token and enter it on the
integration.

---

## 3. The mention alias

The alias is now `@` followed by the username the provider reports for the account:

- **Gitea, GitHub, GitLab:** the account's login. If the Username you configured was the
  account's login, as the setup guides asked, nothing changes. If it differed (another
  spelling, another account), users must mention the real login. Mentions are matched
  case-sensitively, as before.
- **Bitbucket Cloud:** unchanged. Mentions are recognised by Bitbucket's
  `@{account_id}` markup.
- **Azure DevOps Services:** the account's `Account` property, its sign-in name (e.g.
  `@ai-bot@yourcompany.com`). This is normally the `uniqueName` you configured before.
- **Azure DevOps Server:** the account name **without** domain. A comment addressed to
  `@DOMAIN\ai-bot` no longer reaches the bot; it must say `@ai-bot`. Tell your users,
  and update saved comment templates or scripts.

---

## 4. After upgrading

1. Open **Git Integrations**. Each integration shows the username of its account. The
   bot reads every account in the background right after startup, so this can take a few
   seconds after the first start.
2. A dash means the account has not been read. Check the **Last Error** of the bots using
   that integration, fix the token as in § 2, and save the integration: saving reads the
   account again right away.
3. On bare `https://dev.azure.com`, a dash is expected until the first webhook event
   arrives (§ 2).

---

## 5. Database migration details

**Files:**

- `src/main/resources/db/migration/h2/V2026_11_16_1200__drop_git_integration_username.sql`
- `src/main/resources/db/migration/postgresql/V2026_11_16_1200__drop_git_integration_username.sql`

Both run `ALTER TABLE git_integrations DROP COLUMN username;`. No data is moved; the
configured usernames are deleted.

### Rollback

A 1.31.x binary validates the schema at startup (`spring.jpa.hibernate.ddl-auto=validate`)
and expects `git_integrations.username` (`varchar(255) not null`). After the column has been
dropped, 1.31 refuses to start. To roll back, recreate the column and fill in each
integration's bot username before starting 1.31:

```sql
ALTER TABLE git_integrations ADD COLUMN username varchar(255);
UPDATE git_integrations SET username = 'ai_bot' WHERE id = 1;   -- once per integration
ALTER TABLE git_integrations ALTER COLUMN username SET NOT NULL;
```

Use the login shown on the integration list before the rollback (on Bitbucket, the
Atlassian account e-mail; on Azure DevOps, the account's `uniqueName`). The 1.32 entry in
`flyway_schema_history` can stay: 1.31 ignores migrations newer than its own.

---

## 6. See also

- [`USER_GUIDE.md`](./USER_GUIDE.md) — Git integrations and how the bot finds its account.
- [`AZURE_DEVOPS_SETUP.md`](./AZURE_DEVOPS_SETUP.md) — the account and mention alias on
  Azure DevOps.
- [`GITEA_SETUP.md`](./GITEA_SETUP.md#3-create-an-api-token),
  [`GITLAB_SETUP.md`](./GITLAB_SETUP.md), [`BITBUCKET_SETUP.md`](./BITBUCKET_SETUP.md#required-scopes) —
  the token permissions per provider.
