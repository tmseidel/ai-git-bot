# Gitea Setup

This guide walks you through preparing your Gitea instance to work with AI-Git-Bot.

> **Note:** For other Git providers, see [GitHub Setup](GITHUB_SETUP.md), [GitLab Setup](GITLAB_SETUP.md), or [Bitbucket Setup](BITBUCKET_SETUP.md).

## 1. Create the Bot User

The bot needs its own Gitea user account to post reviews and comments.

1. Log in to your Gitea instance as an **administrator**
2. Navigate to **Site Administration → User Accounts → Create User Account**
3. Fill in the details:
   - **Username:** `ai_bot` (or any name you prefer — this will be configured in the bot settings)
   - **Email:** `ai_bot@noreply.localhost`
   - **Password:** Choose a strong password
4. Click **Create User Account**

> **Tip:** If self-registration is enabled, you can also register the bot user through the normal registration flow.

## 2. Add the Bot to Organizations and Repositories

The bot needs read/write access to repositories where it should perform code reviews.

### Option A: Organization-wide Access

1. Navigate to the organization's **Settings → Members**
2. Click **Invite Member**
3. Search for `ai_bot` and add it
4. Assign the **Write** role (or create a team with appropriate permissions)

### Option B: Per-Repository Access

1. Navigate to the repository's **Settings → Collaborators**
2. Search for `ai_bot`
3. Add with **Write** access

The bot needs at minimum:
- **Read** access to pull requests and code
- **Write** access to issues/comments (for posting reviews and reactions)

## 3. Create an API Token

The bot authenticates against the Gitea API using a personal access token.

1. Log in as the `ai_bot` user
2. Go to **Settings → Applications**
3. Under **Manage Access Tokens**, click **Generate New Token**
4. Give it a descriptive name, e.g., `ai-gitea-bot`
5. Select the following **permissions**:
   - ✅ `write:issue` — Post review comments, add reactions
   - ✅ `write:repository` — Read diffs, post pull request reviews
   - ✅ `write:user` — Needed for reading user context
6. Click **Generate Token**
7. **Copy the token immediately** — it will not be shown again

You'll enter this token when creating a **Git Integration** in the bot's web UI.

## 4. Configure Webhooks

Webhooks tell Gitea to notify the bot when pull request events occur. Each bot has a unique webhook URL.

Code reviews are explicit-request only: pushes to an existing pull request do not trigger another review by themselves.

### Getting the Webhook URL

1. In the bot's web UI, go to **Bots**
2. Click on your bot (or create one if you haven't)
3. The **Webhook URL** is displayed at the top of the edit form
4. Copy this URL (e.g., `http://your-bot-server:8080/api/webhook/abc123-def456-...`)

### Repository-level Webhook

1. In the repository, go to **Settings → Webhooks → Add Webhook → Gitea**
2. Configure:
   - **Target URL:** Paste the bot's webhook URL
   - **HTTP Method:** POST
   - **Content Type:** application/json
   - **Secret:** (leave empty — authentication is via the URL path)
3. Under **Trigger On**, select **Custom Events**, then enable:
   - ✅ **Pull Request** — handles PR open/close events and reviewer requests/re-requests
   - ✅ **Pull Request Comment** — allows the bot to respond to inline code review comments
   - ✅ **Pull Request Review Requested** — allows the bot to respond when added as a reviewer
   - ✅ **Pull Request Assigned** — allows the bot to respond when assigned as a reviewer
   - ✅ **Issue Comment** — allows the bot to respond to `@bot` mentions in PR and issue comments
   - ✅ **Issue Assigned** — allows the bot to start either as coding or writer bot (if using the agent feature)
   - ✅ **Issues** (only if using the agent feature)

4. Click **Add Webhook**

### Organization-level Webhook

To cover all repositories in an organization at once:

1. Go to the organization's **Settings → Webhooks**
2. Follow the same configuration as above

### Multiple Bots

You can create multiple bots with different configurations (different AI providers, different prompts) and point different repositories or organizations to different bot webhook URLs.

## 5. Configure the Bot

In the bot's web UI:

1. **Create a Git Integration:**
   - Go to **Git Integrations → New Integration**
   - Select **Gitea** as the provider type
   - Enter your Gitea URL (e.g., `https://gitea.example.com`)
   - Enter the API token you created above
   - Click **Save**

2. **Create or Edit a Bot:**
   - Set the **Username** to match the bot's Gitea username (e.g., `ai_bot`)
   - This is used to detect and ignore the bot's own actions
    - The mention alias is derived as `@ai_bot`

## Review Workflow

- First review: create the pull request with the bot already listed as a reviewer, or add the bot as a reviewer after opening the PR.
- Re-review: re-request the bot as reviewer using Gitea's reviewer workflow.
- New commits: pushing to the PR does not run another review. Re-request the bot when you want a fresh review.
- PR and inline comments that mention the bot are handled only when they are written by the pull request author.

## Verification

After setup, create a test pull request. The bot should:

1. Post an AI-generated code review when the PR is opened with the bot reviewer or the bot is added/re-requested as reviewer
2. Ignore pushes to the PR until the bot is explicitly re-requested
3. Respond when mentioned in PR comments (e.g., `@ai_bot explain this`)
4. Respond to inline review comments mentioning the bot
5. React with 👀 to acknowledge commands

Check the bot's application logs for troubleshooting if reviews don't appear.

## Screenshots

### Requested Code Review

When the bot is requested as reviewer, it posts an AI-generated review:

<img src="screenshots/gitea/screenshot_initial_code_review.png" alt="Gitea — Automatic Code Review" width="700"/>

### Interactive Bot Commands

Mention the bot in a PR comment to ask follow-up questions:

<img src="screenshots/gitea/screenshot_code_review_with_comment.png" alt="Gitea — Bot Command Response" width="700"/>

### Inline Review Comments

Mention the bot in an inline code comment for context-aware answers:

<img src="screenshots/gitea/screenshot_code_review_with_inline_comment.png" alt="Gitea — Inline Review Comment" width="700"/>
