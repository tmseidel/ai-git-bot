# Azure DevOps Setup Guide

This guide explains how to configure AI-Git-Bot to work with Azure DevOps Services
(`dev.azure.com`).

## Prerequisites

- An Azure DevOps organization on Azure DevOps Services, or Azure DevOps Server 2020 or
  newer (the bot uses REST API version 6.0)
- A project and Git repository where you want to enable the bot
- Permission to create Personal Access Tokens and Service Hook subscriptions
- Outbound HTTPS access from the bot host to the repository's Git URL
  (`…/_git/<repository>`), not just to the REST API: pull request diffs are produced by
  fetching the two commits with `git` and running `git diff`. On a firewalled Azure
  DevOps Server, allow Git traffic from the bot host as well.

## Step 1: Create a Personal Access Token

1. In Azure DevOps, open **User settings** (top-right avatar) → **Personal access tokens**.
2. Click **New Token**.
3. Give it a name (e.g. "AI Code Review Bot") and an expiration.
4. Under **Scopes**, select **Custom defined** and grant:
   - **Code** — Read & write
   - **Pull Request Threads** — Read & write
5. Click **Create**.
6. **Important**: Copy the generated token immediately — you won't be able to see it again!

## Step 2: Configure the Git Integration

In the bot's admin UI, create a new Git Integration:

1. Select **Provider Type**: `AZURE_DEVOPS`
2. Enter the **URL** — see the table below for your deployment
3. Enter the **Personal Access Token** you created in Step 1

There is no username field. Azure DevOps PATs authenticate as HTTP Basic with an empty
username, so the bot never collects one for this provider.

### Which URL to enter

| Deployment | URL to enter | Organizations per integration |
|---|---|---|
| Azure DevOps Services | `https://dev.azure.com` | Many |
| Azure DevOps Services, legacy host | `https://contoso.visualstudio.com` | One (`contoso`) |
| Azure DevOps Server (on-premises), without collection | `https://tfs.example.com/tfs` | Many (every collection on the server) |
| Azure DevOps Server (on-premises), with collection | `https://tfs.example.com/tfs/DefaultCollection` | One (`DefaultCollection`) |

**On Azure DevOps Server the collection in the URL is optional.** Both forms above work.
Leave it out to serve every collection on the server with one integration; include it to
pin the integration to a single collection. `/tfs` is not a collection but the server's
virtual directory — keep whatever prefix your server uses (some installations have none).

**On `https://dev.azure.com`, one integration serves many organizations.** The URL is the
instance root and must **not** include your organization name. Do not enter
`https://dev.azure.com/contoso` — the bot appends the organization itself, so you would
end up addressing `dev.azure.com/contoso/contoso/...`. The organization is resolved
per-event from the incoming webhook payload, so a single integration can serve pull
requests from any number of organizations, each with its own PAT-holding bot as needed.
An Azure DevOps Server URL without a collection behaves the same way, with the collection
resolved from the payload.

**The legacy host and a Server URL with a collection pin the organization into the URL**,
so they need one integration each. The bot detects this and omits the organization from
its request paths rather than addressing it twice — you do not have to configure anything
for that.

> **Note:** on Azure DevOps Server the "organization" is the **collection**. Wherever this
> document says `owner` = organization, read `owner` = collection name.

## Step 3: Create a Bot

Create a new Bot in the admin UI and link it to:
- Your Azure DevOps Git Integration
- Your AI Integration (e.g. Anthropic)

Save the bot, then copy the **Webhook URL** shown at the top of its edit form (e.g.
`/api/webhook/abc123-def456-...`). It already contains the bot's generated path secret —
you paste this whole path into every subscription in the next step. This is not the
optional signing secret of Step 5; that one you choose yourself.

## Step 4: Create Service Hook Subscriptions

Azure DevOps has no single "webhooks" list like GitHub or Gitea — instead, each event type
is its own **Service Hook subscription**. Create **one subscription per event**:

1. In your Azure DevOps project, go to **Project settings** → **Service hooks**.
2. Click **Create subscription** and choose **Web Hooks** as the service.
3. Repeat this for each of the following trigger events:
   - **Pull request created**
   - **Pull request updated**
   - **Pull request commented on**
4. For each subscription, in the **Action** step, set the **URL** to your server's base URL
   followed by the webhook path from Step 3:

   ```
   https://your-bot-server.com/api/webhook/abc123-def456-...
   ```

> **Do not subscribe to "Pull request merge attempted"** (`git.pullrequest.merged`). It
> looks like the obvious way to detect a merge, but it is not: Azure DevOps fires this
> event whenever it builds the *preview* merge commit, which happens at PR creation and
> on every subsequent push to the source branch — not just when a PR actually merges. The
> bot deliberately ignores it. PR completion and abandonment instead arrive through
> **Pull request updated** (a status change to `completed` or `abandoned`), which you have
> already subscribed to above.

### Resource details to send = All

On each subscription's **Action** step, find **Resource details to send** and set it to
**All**. This defaults to a reduced setting in the Azure DevOps UI, and the reduced
settings omit fields the bot needs — notably `reviewers`, `lastMergeSourceCommit` and
`description`. If you leave the default in place, the bot will receive incomplete PR data
(e.g. it may fail to recognize itself as a requested reviewer, be unable to build the
diff, or re-review the pull request on every reviewer vote — see below). Double-check
this setting on every subscription you create.

### "Pull request updated" is not a push notification

Azure DevOps fires **Pull request updated** for a reviewer vote, a title or description
edit, and a merge-status change, not only for a push to the source branch. To keep a bot
with **Run on PR update** enabled from re-reviewing the whole pull request every time a
human clicks Approve, the bot compares `lastMergeSourceCommit` against the commit it last
acted on and ignores events where the source branch has not moved.

This relies on `lastMergeSourceCommit` being present, which is another reason to set
**Resource details to send** to **All**. When the commit is missing the bot cannot tell a
push from a vote, and errs on the side of reviewing: a reduced subscription runs a review
on every **Pull request updated** event, including reviewer votes and title edits, rather
than risking a missed push.
## Step 5: Configure the Signing Secret

1. In the bot's admin UI, set the bot's **webhook signing secret** to a strong random value
   of your choosing. The field is optional but recommended.
2. In each Service Hook subscription's **Action** step, add an **HTTP header**:

   ```
   X-AiGitBot-Token: <secret>
   ```

   Replace `<secret>` with the same signing secret configured on the bot.

Unlike GitHub, Gitea, GitLab and Bitbucket, Azure DevOps Service Hooks do not sign the
request body with an HMAC digest — there is no equivalent of `X-Hub-Signature-256`. The
bot therefore verifies Azure DevOps requests with a plain shared-secret header instead of
a body signature. Anyone who can read the header value can forge a request, so treat the
Service Hook subscription's configuration (and the URL/secret combination) as sensitive.

## Step 6: Test the Integration

1. Create a new Pull Request with the bot added as a reviewer.
2. The bot should post a code review comment.
3. If it doesn't work, check the bot's logs and the Service Hook subscription's recent
   deliveries (**Project settings** → **Service hooks** → subscription → **History**) for
   error messages.

## Review Workflow

- First review: create the pull request with the bot already listed as a reviewer, or add
  the bot as a reviewer after opening the PR. Both work without **Run on PR creation** or
  **Run on PR update**; adding the bot as a reviewer is an explicit request for a review,
  and the bot reacts to being added, not to still being on the list — later votes and
  title edits do not start a new review.
- Reactivating an abandoned pull request is treated like reopening one: it is reviewed
  again when the bot is a listed reviewer, or when **Run on PR creation** is enabled.
- Re-review: mention the bot in a PR comment asking for another look, for example:

  ```text
  @ai_bot - Review the Pull-Request again
  ```

- **Only the pull request author's comments trigger a re-review.** A comment from anyone
  else that mentions the bot and asks for a re-review is silently ignored — the bot still
  responds to other bot commands and @-mentions from any user, but the "review again"
  request specifically is author-restricted.

## How Azure DevOps Repositories Are Addressed

Nothing to configure here — the bot derives all of this itself. Read this section only if
a field asks you for a repository owner/name pair, or if you are making sense of a
repository name in the logs.

Every other provider addresses a repository with a simple `owner/repo` pair. Azure DevOps
has an extra level — organization → project → repository — so the bot folds the project
into the repository half of the pair. For the repository at
`https://dev.azure.com/contoso/MyProject/_git/my-service`:

- `owner` = `contoso` (the organization; the **collection** on Azure DevOps Server)
- `repo` = `MyProject/my-service` (project name, a slash, then the Git repository name)

The `owner` is resolved per event: from the webhook payload on `https://dev.azure.com`,
from the hostname on the legacy host (`contoso.visualstudio.com` → `contoso`), and from
the collection on Azure DevOps Server (`.../tfs/DefaultCollection/_apis/...` →
`DefaultCollection`) — whether or not the collection is part of the configured URL. Azure DevOps repository names cannot contain a `/`, so splitting the
`repo` half on the first slash is unambiguous.

Where you do type it yourself: an **Event Hook**'s optional **Repository owner scope** and
**Repository name scope** fields, and anywhere else the application asks for an owner/name
pair for an Azure DevOps repository (deployment target scopes, CI/CD documentation) — use
the `Project/Repository` form for the name.

## Not Supported

- **Work Items** — Azure DevOps's issue equivalent. The issue-triage, issue-coding
  (coding agent) and issue-writer workflows all operate on Git-provider issues and do not
  apply to Azure DevOps Work Items.
- **Azure Pipelines** — and therefore the `CI_ACTION` deployment target strategy, which
  dispatches provider-native CI pipelines (GitHub Actions, Gitea/GitLab CI, Bitbucket
  Pipelines). Use the `WEBHOOK`, `STATIC` or `MCP` deployment target strategies instead if
  you need preview-aware workflows (E2E tests, smoke checks) against an Azure DevOps
  repository.
- **SSH transport** — only HTTP(S) with a Personal Access Token is supported; there is no
  SSH credential option for this provider.

## Required Permissions

The minimum required scopes for the Personal Access Token:

| Scope | Required For |
|---|---|
| Code (Read & write) | Fetching PR diffs, reading file contents, pushing commits (e.g. generated tests) |
| Pull Request Threads (Read & write) | Reading PR/thread information, posting review comments and inline comments |
