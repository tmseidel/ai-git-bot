# GitHub Setup

This guide walks you through preparing your GitHub account or GitHub Enterprise instance to work with AI-Git-Bot.

## 1. Create the Bot User (Recommended)

For production use, create a dedicated GitHub user account for the bot. This keeps bot activity separate from human users and allows fine-grained access control.

### For github.com

1. Create a new GitHub account for the bot (e.g., `ai-code-reviewer`)
2. Use a dedicated email address for the bot account
3. Complete account setup and email verification

### For GitHub Enterprise

1. As an admin, create a new user account (e.g., `ai_bot`)
2. Or request an admin to create a service account for the bot

> **Alternative:** You can use a personal account for testing, but a dedicated bot account is recommended for production.

## 2. Add the Bot to Organizations and Repositories

The bot needs read/write access to repositories where it should perform code reviews.

### Option A: Organization Member

1. Navigate to your organization's **People** tab
2. Click **Invite member**
3. Search for the bot's username and add it
4. Assign the appropriate role:
   - **Member** with repository-level access, or
   - Create a team with write access to specific repositories

### Option B: Repository Collaborator

1. Navigate to the repository's **Settings → Collaborators**
2. Click **Add people**
3. Search for the bot's username
4. Select **Write** access level

The bot needs at minimum:
- **Read** access to pull requests and code
- **Write** access to issues/comments (for posting reviews and reactions)
- **Write** access to pull requests (for posting reviews)

## 3. Create a Personal Access Token (PAT)

The bot authenticates against the GitHub API using a Personal Access Token.

### Classic Token (Simpler)

1. Log in as the bot user
2. Go to **Settings → Developer settings → Personal access tokens → Tokens (classic)**
3. Click **Generate new token (classic)**
4. Give it a descriptive name (e.g., `ai-code-review-bot`)
5. Select the following **scopes**:
   - ✅ `repo` — Full control of private repositories
   - ✅ `write:discussion` — Write access to discussions (optional)
6. Click **Generate token**
7. **Copy the token immediately** — it will not be shown again

### Fine-Grained Token (More Secure)

1. Log in as the bot user
2. Go to **Settings → Developer settings → Personal access tokens → Fine-grained tokens**
3. Click **Generate new token**
4. Configure:
   - **Token name:** `ai-code-review-bot`
   - **Expiration:** Set an appropriate expiration
   - **Repository access:** Select specific repositories or "All repositories"
5. Under **Permissions**, set:
   - **Repository permissions:**
     - ✅ `Contents`: Read and write
     - ✅ `Issues`: Read and write
     - ✅ `Pull requests`: Read and write
     - ✅ `Metadata`: Read-only (required)
6. Click **Generate token**
7. **Copy the token immediately** — it will not be shown again

You'll enter this token when creating a **Git Integration** in the bot's web UI.

## 4. Configure Webhooks

Webhooks tell GitHub to notify the bot when pull request events occur. Each bot has a unique webhook URL.

Code reviews are explicit-request only: pushes to an existing pull request do not trigger another review by themselves.

### Getting the Webhook URL

1. In the bot's web UI, go to **Bots**
2. Click on your bot (or create one if you haven't)
3. The **Webhook URL** is displayed at the top of the edit form
4. Copy this URL (e.g., `http://your-bot-server:8080/api/github-webhook/abc123-def456-...`)

### Repository-Level Webhook

1. In the repository, go to **Settings → Webhooks → Add webhook**
2. Configure:
   - **Payload URL:** Paste the bot's webhook URL
   - **Content type:** `application/json`
   - **Secret:** (leave empty — authentication is via the URL path)
3. Under **Which events would you like to trigger this webhook?**, select **Let me select individual events**, then enable:
   - ✅ **Pull requests** (PR opened, reviewer added, reviewer re-requested)
   - ✅ **Pull request reviews**
   - ✅ **Pull request review comments**
   - ✅ **Issue comments**
   - ✅ **Issues** (only if using the agent feature)
4. Ensure **Active** is checked
5. Click **Add webhook**

### Organization-Level Webhook

To cover all repositories in an organization at once:

1. Go to the organization's **Settings → Webhooks**
2. Click **Add webhook**
3. Follow the same configuration as above

> **Note:** Organization webhooks require admin permissions.

### Multiple Bots

You can create multiple bots with different configurations (different AI providers, different prompts) and point different repositories or organizations to different bot webhook URLs.

## 5. Configure the Bot

In the bot's web UI:

1. **Create a Git Integration:**
   - Go to **Git Integrations → New Integration**
   - Select **GitHub** as the provider type
   - Enter your GitHub URL:
     - For github.com: `https://github.com`
     - For GitHub Enterprise: `https://github.yourdomain.com`
   - Enter the Personal Access Token you created above
   - Click **Save**

2. **Create or Edit a Bot:**
   - Set the **Username** to match the bot's GitHub username (e.g., `ai-code-reviewer`)
   - This is used to detect and ignore the bot's own actions
    - The mention alias is derived as `@ai-code-reviewer`

## Review Workflow

- First review: create the pull request with the bot already listed under **Reviewers**, or add the bot as a reviewer after opening the PR.
- Re-review: use GitHub's **Re-request review** action for the bot reviewer.
- New commits: pushing to the PR does not run another review. Re-request the bot when you want a fresh review.
- PR and inline comments that mention the bot are handled only when they are written by the pull request author.

## GitHub Enterprise Server

For GitHub Enterprise Server installations:

### URL Configuration

Enter your GitHub Enterprise URL in the Git Integration form:

```
https://github.yourdomain.com
```

The bot automatically converts this to the API URL (`https://github.yourdomain.com/api/v3`).

### Self-Signed Certificates

If your GitHub Enterprise uses self-signed certificates, you may need to configure the JVM to trust them. See your deployment documentation for details.

### Rate Limits

GitHub Enterprise has rate limits that may differ from github.com. Monitor your API usage if you have many repositories or high PR volume.

## Agent Feature: Targeting a Specific Branch
### Why a Workaround is Needed
Unlike Gitea or GitLab, **GitHub's issue webhook payload does not include a branch reference**. When you assign an issue to the bot or mention it in an issue comment, the webhook event carries no information about which branch the implementation should target. The bot therefore always defaults to the repository's **default branch** (usually `main` or `master`).
Other platforms (Gitea, GitLab) let you set an issue's target branch directly in the UI, which is then forwarded in the webhook payload. GitHub offers no such field.
### The Workaround: `branch-switcher`
To work on a different branch, you can instruct the AI to switch branches during its first context-discovery round by mentioning the target branch in the issue title, body, or a comment. The AI will then emit a `branch-switcher` tool request before reading any files, and the bot will check out the requested branch in the workspace.
**How it works internally:**
1. The bot clones the repository on the default branch.
2. In the first AI context-request round the AI sees that a specific branch is required and includes a `branch-switcher` entry in `requestTools`.
3. The bot fetches the target branch from `origin`, switches the workspace to it, and refreshes the file tree for that branch.
4. All subsequent file reads, writes, and commits happen on the switched branch.
> **Note:** Only a single branch switch per issue step is supported. Additional `branch-switcher` requests in the same round are ignored.
### How to Trigger a Branch Switch
Tell the bot which branch should be used directly in your issue text or in the comment that assigns/mentions the bot. A few examples:
```
Please implement this on the `develop` branch.
```
```
@ai-bot Implement this feature on branch `feature/my-feature`.
```
```
Target branch: release/1.2
```
The AI will automatically extract the branch name and produce a `branch-switcher` tool request in its first response round.
### Example Tool Request (What the AI Sends)
When the AI recognises a target branch, its first JSON response will include a `branch-switcher` in `requestTools`:
```json
{
  "summary": "Implement feature X on the develop branch",
  "requestTools": [
    {
      "id": "a1b2c3d4-0000-0000-0000-000000000001",
      "tool": "branch-switcher",
      "args": ["develop"]
    },
    {
      "id": "a1b2c3d4-0000-0000-0000-000000000002",
      "tool": "cat",
      "args": ["src/main/java/com/example/MyService.java"]
    }
  ],
  "requestFiles": []
}
```
The bot executes the `branch-switcher` first, then proceeds with the remaining context tools on the switched branch. On success, the bot logs:
```
Switched workspace/context branch to 'develop' for issue #42
```
### Tips
- **Be explicit**: If your workflow always targets a non-default branch, state the branch name clearly in the issue description template.
- **Verify via PR**: After the agent creates the PR, confirm the base branch shown in the pull request header matches your intended target.
- **Default fallback**: If the AI does not detect a branch name, it will work on the repository's default branch. You can always add a comment like `@ai-bot please redo this on branch develop` to trigger a fresh run on the correct branch.
---
## Verification

After setup, create a test pull request. The bot should:

1. Post an AI-generated code review when the bot is requested as reviewer
2. Respond when mentioned in PR comments (e.g., `@ai-code-reviewer explain this`)
3. Respond to inline review comments mentioning the bot
4. React with 👀 to acknowledge commands

Check the bot's application logs for troubleshooting if reviews don't appear.

## Screenshots

### Code Review with Bot Command

The bot responds to mentions in PR comments with context-aware answers:

<img src="screenshots/github/github_code_review_with_comment.png" alt="GitHub — Code Review with Bot Command" width="700"/>

### Autonomous Issue Implementation (Agent)

Assign the bot to an issue and it will autonomously create a PR with the implementation:

<img src="screenshots/github/github_issue_agent_code_implementation.png" alt="GitHub — Agent Issue Implementation" width="700"/>

## Troubleshooting

### "Bad credentials" Error

- Ensure the Personal Access Token is correct and not expired
- Verify the token has the required permissions/scopes
- For fine-grained tokens, ensure the token has access to the specific repository

### "Not Found" Error

- Ensure the bot user has access to the repository
- Check that the repository URL is correct
- Verify organization membership if using an org-level webhook

### Webhook Not Received

- Check the webhook delivery logs in GitHub (Settings → Webhooks → Recent Deliveries)
- Ensure the bot server is accessible from GitHub's servers
- For github.com, ensure your server allows incoming connections from GitHub's IP ranges

### Reviews Not Posted

- Check the bot's application logs for error messages
- Verify the AI integration is configured correctly
- Ensure the bot is enabled in the bot settings
