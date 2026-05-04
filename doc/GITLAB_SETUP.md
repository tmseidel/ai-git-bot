# GitLab Setup

This guide walks you through preparing your GitLab instance (gitlab.com or self-managed) to work with AI-Git-Bot.

## Limitations

> **⚠️ Reactions are not supported**: The bot cannot add 👀 reaction acknowledgements on GitLab because the GitLab award emoji API requires the Merge Request IID, which is not available in the generic `RepositoryApiClient` interface. This is cosmetic only — all review functionality works normally.

## 1. Create the Bot User (Recommended)

For production use, create a dedicated GitLab user account for the bot. This keeps bot activity separate from human users and allows fine-grained access control.

### For gitlab.com

1. Create a new GitLab account for the bot (e.g., `ai-code-reviewer`)
2. Use a dedicated email address for the bot account
3. Complete account setup and email verification

### For Self-Managed GitLab

1. As an admin, navigate to **Admin Area → Users → New user**
2. Create a service account (e.g., `ai_bot`)
3. Set a password and optionally skip email confirmation

> **Alternative:** You can use a personal account for testing, but a dedicated bot account is recommended for production.

## 2. Add the Bot to Projects and Groups

The bot needs access to repositories where it should perform code reviews.

### Option A: Group Member

1. Navigate to your group's **Members** page
2. Click **Invite members**
3. Search for the bot's username and add it
4. Assign the **Developer** role (minimum required for posting comments and creating merge requests)

### Option B: Project Member

1. Navigate to the project's **Settings → Members**
2. Click **Invite members**
3. Search for the bot's username
4. Assign the **Developer** role

The bot needs at minimum:
- **Read** access to merge requests and code (repository)
- **Write** access to merge request comments (for posting reviews)
- **Write** access to merge requests (for creating MRs via the agent feature)

## 3. Create a Personal Access Token (PAT)

The bot authenticates against the GitLab API using a Personal Access Token with the `PRIVATE-TOKEN` header.

### Personal Access Token

1. Log in as the bot user
2. Go to **User Settings → Access Tokens** (or navigate to `/-/user_settings/personal_access_tokens`)
3. Click **Add new token**
4. Configure:
   - **Token name:** `ai-code-review-bot`
   - **Expiration date:** Set an appropriate expiration (or leave blank for no expiry on self-managed)
5. Select the following **scopes**:
   - ✅ `api` — Full API access (required for reading diffs, posting comments, managing merge requests)
   - ✅ `read_repository` — Read repository content (required for fetching file contents)
6. Click **Create personal access token**
7. **Copy the token immediately** — it will not be shown again

### Project or Group Access Token (Alternative)

For more restricted access, you can create a project or group access token:

1. Navigate to the project or group's **Settings → Access Tokens**
2. Click **Add new token**
3. Configure:
   - **Token name:** `ai-code-review-bot`
   - **Role:** Developer
   - **Scopes:** `api`, `read_repository`
4. Click **Create project access token** / **Create group access token**
5. **Copy the token immediately**

You'll enter this token when creating a **Git Integration** in the bot's web UI.

## 4. Configure Webhooks

Webhooks tell GitLab to notify the bot when merge request events occur. Each bot has a unique webhook URL.

Code reviews are explicit-request only: pushes to an existing merge request do not trigger another review by themselves.

### Getting the Webhook URL

1. In the bot's web UI, go to **Bots**
2. Click on your bot (or create one if you haven't)
3. The **Webhook URL** is displayed at the top of the edit form as a path (e.g., `/api/webhook/abc123-def456-...`)
4. Combine your server's base URL with this path to get the full webhook URL (e.g., `http://your-bot-server:8080/api/webhook/abc123-def456-...`)

### Project-Level Webhook

1. In the project, go to **Settings → Webhooks**
2. Click **Add new webhook**
3. Configure:
   - **URL:** Paste the bot's webhook URL
   - **Secret token:** (leave empty — authentication is via the URL path)
4. Under **Trigger**, enable:
   - ✅ **Merge request events** (MR opened, reviewer added/re-requested, MR closed/merged)
   - ✅ **Comments** (for bot commands in MR comments and review-request fallback comments)
   - ✅ **Issues events** (only if using the agent feature)
5. Ensure **Enable SSL verification** is checked (if applicable)
6. Click **Add webhook**

### Group-Level Webhook

To cover all projects in a group at once:

1. Go to the group's **Settings → Webhooks**
2. Click **Add new webhook**
3. Follow the same configuration as above

> **Note:** Group webhooks require at least a **Premium** plan on gitlab.com, or are available on all self-managed instances.

### Multiple Bots

You can create multiple bots with different configurations (different AI providers, different prompts) and point different projects or groups to different bot webhook URLs.

## 5. Configure the Bot

In the bot's web UI:

1. **Create a Git Integration:**
   - Go to **Git Integrations → New Integration**
   - Select **GitLab** as the provider type
   - Enter your GitLab URL:
     - For gitlab.com: `https://gitlab.com`
     - For self-managed: `https://gitlab.yourdomain.com`
    - Enter the Personal Access Token you created above
    - Leave **Post-review Action** as **None** unless you explicitly want the bot to approve the MR or post a request-changes note after each review
    - Click **Save**

2. **Create or Edit a Bot:**
   - Set the **Username** to match the bot's GitLab username (e.g., `ai-code-reviewer`)
   - This is used to detect and ignore the bot's own actions
    - The mention alias is derived as `@ai-code-reviewer`

## Review Workflow

- First review: create the merge request with the bot already listed as a reviewer, or add the bot as a reviewer after opening the MR.
- Re-review: re-request the bot as reviewer. If your GitLab version or workflow cannot re-request a reviewer, the MR author can comment with the bot mention and review intent, for example `@ai-code-reviewer please review this again`.
- New commits: pushing to the MR does not run another review. Re-request the bot when you want a fresh review.
- MR and inline comments that mention the bot are handled only when they are written by the merge request author.

### Post-review Action

GitLab integrations support the optional **Post-review Action** setting:

- **None** (default): post the AI review only.
- **Approve merge request**: call GitLab's approve endpoint after the AI review is posted.
- **Request changes**: post a request-changes note after the AI review is posted.

Existing integrations default to **None** after migration.

## Self-Managed GitLab

For self-managed GitLab installations:

### URL Configuration

Enter your GitLab instance URL in the Git Integration form:

```
https://gitlab.yourdomain.com
```

The bot uses the GitLab REST API v4, accessing endpoints under `/api/v4/` at the configured base URL.

### Self-Signed Certificates

If your GitLab instance uses self-signed certificates, you may need to configure the JVM to trust them. See your deployment documentation for details.

### Rate Limits

Self-managed GitLab instances may have different rate limits than gitlab.com. Monitor your API usage if you have many projects or high MR volume. The default rate limit on gitlab.com is 2,000 requests per minute for authenticated users.

## Verification

After setup, create a test merge request. The bot should:

1. Post an AI-generated code review when the bot is requested as reviewer
2. Ignore pushes to the MR until the bot is explicitly re-requested
3. Respond when the MR author mentions the bot in MR comments (e.g., `@ai-code-reviewer explain this`)
4. Respond to inline discussion comments from the MR author that mention the bot

> **Note:** The bot will **not** add 👀 reaction acknowledgements due to the GitLab API limitation mentioned above.

Check the bot's application logs for troubleshooting if reviews don't appear.

## Screenshots

### Merge Request Code Review

The bot reviews merge requests when explicitly requested and posts AI-generated feedback:

<img src="screenshots/gitlab/gitlab-pull-request-with-code-review.png" alt="GitLab — Merge Request Code Review" width="700"/>

### Autonomous Issue Implementation (Agent)

Assign the bot to an issue and it will autonomously create a merge request with the implementation:

<img src="screenshots/gitlab/gitlab_issue_agent_code_implementation.png" alt="GitLab — Agent Issue Implementation" width="700"/>

## Troubleshooting

### "401 Unauthorized" Error

- Ensure the Personal Access Token is correct and not expired
- Verify the token has the required scopes (`api`, `read_repository`)
- For project/group access tokens, ensure the token's role is at least Developer

### "404 Not Found" Error

- Ensure the bot user has access to the project
- Check that the GitLab URL is correct in the Git Integration
- Verify group/project membership

### Webhook Not Received

- Check the webhook delivery logs in GitLab (**Settings → Webhooks → Edit → Recent events**)
- Ensure the bot server is accessible from your GitLab instance
- For gitlab.com, ensure your server allows incoming connections from GitLab's IP ranges
- Verify SSL certificates if using HTTPS

### Reviews Not Posted

- Check the bot's application logs for error messages
- Verify the AI integration is configured correctly
- Ensure the bot is enabled in the bot settings
- Check that the merge request event triggers are configured correctly in the webhook

### "Forbidden" Error on Comments

- Ensure the bot user has at least Developer role in the project
- For project/group access tokens, verify the role is sufficient
