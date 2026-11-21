# Bitbucket Cloud Setup Guide

This guide explains how to configure AI-Git-Bot to work with Bitbucket Cloud.

## Limitations

> **⚠️ Agent feature not available**: The code-creation agent (automatic issue implementation) is **not available** for Bitbucket Cloud due to Atlassian's end-of-life of Bitbucket Pipelines' internal tasks. Only code review on pull requests and bot commands in PR comments are supported.

## Prerequisites

- A **dedicated** Atlassian account for the bot, with access to the repository. Do not use a personal account: the bot ignores every pull request event and comment created by the account behind its API token (so it never reacts to its own comments), which would include all of that person's own pull requests and comments.
- A repository where you want to enable the bot

## Step 1: Create an API Token

> **Note**: Only **Atlassian account API tokens** (created at id.atlassian.com) are supported for now. Workspace, project and repository **access tokens** and the deprecated **App Passwords** are not supported.

1. Sign in as the bot's Atlassian account and open https://id.atlassian.com/manage-profile/security/api-tokens
2. Click **Create API token with scopes**
3. Give it a name (e.g., "AI Code Review Bot") and an expiry date
4. Select **Bitbucket** as the app
5. Select the scopes listed under [Required Scopes](#required-scopes)
6. Click **Create**
7. **Important**: Copy the generated token immediately — you won't be able to see it again!

## Step 2: Configure the Git Integration

In the bot's admin UI, create a new Git Integration:

1. Select **Provider Type**: `BITBUCKET`
2. Enter the **API Token** you created in Step 1

How the credentials are used:

| Operation | Authentication |
|-----------|----------------|
| Bitbucket REST API | Bearer with the API token |
| Git over HTTPS (clone, fetch, push) | HTTP Basic with the fixed username `x-bitbucket-api-token-auth` and the API token |

The token also defines the bot's **identity** on Bitbucket: the bot resolves the account behind the token and uses it to recognise when it is requested as a reviewer, when it is mentioned, and to ignore its own comments.

> **Note**: The URL is set automatically to `https://bitbucket.org` — you don't need to configure it.

## Step 3: Create a Bot

Create a new Bot in the admin UI and link it to:
- Your Bitbucket Git Integration
- Your AI Integration (e.g., Anthropic)

> **Note**: For Bitbucket, the bot's identity is the Atlassian account the API token belongs to (resolved via the API). Entries in the bot's **User whitelist** must be Bitbucket nicknames.

Note the **Webhook Secret** that is generated — you'll need this for the next step.

## Step 4: Configure the Webhook in Bitbucket

1. Go to your Bitbucket repository
2. Navigate to **Repository settings** → **Workflow** → **Webhooks**
3. Click **Add webhook**
4. Configure the webhook:
   - **Title**: AI Code Review Bot
   - **URL**: `https://your-bot-server.com/api/webhook/{webhook_secret}`
     - Replace `{webhook_secret}` with your bot's webhook secret
   - **Triggers**: Select the following:
     - Pull request: Created
     - Pull request: Updated (the bot being added as a reviewer; new commits, when **Run workflow when PR head is updated** is enabled)
     - Pull request: Merged and Declined (ends the bot's session for the pull request)
     - Pull request: Comment created (for bot commands and comment-based re-review)
5. Click **Save**

## Review Workflow

See [Trigger conditions](PR_WORKFLOWS.md#trigger-conditions) for every trigger per provider.

- First review: create the pull request with the bot already listed as a reviewer, or add the bot as a reviewer after opening the PR.
- Re-review: Bitbucket Cloud does not provide the same reviewer re-request workflow as GitHub/Gitea. Anyone can request another review (with a **User Whitelist**, only listed users) by adding a PR comment that mentions the bot and asks for another review, for example:

  ```text
  @AI Bot - Review the Pull-Request again
  ```

- Mentions must be created with Bitbucket's `@` autocomplete, so that Bitbucket stores them as an account mention (`@{account_id}` in the comment's raw markup). Plain text such as `@ai_bot` that was not picked from the autocomplete is not recognised.

- New commits: pushing to the PR runs another review only when **Run workflow when PR head is updated** is enabled on the bot. Otherwise add the comment above when you want a fresh review.
- Comments: a PR comment or inline code comment that mentions the bot is answered whoever wrote it, a "review again" request included. With a **User Whitelist** on the bot, the commenter must be listed.
- **Public repositories:** without a **User Whitelist**, anyone who can comment can make the bot run its PR workflows (a "review again" request, slash commands) and spend AI tokens. Set a whitelist on bots that serve repositories where people you don't trust can comment. See [User Whitelist](USER_GUIDE.md#per-bot-user-whitelist-token-spend-guard).
- Reviewer added by someone outside the **User Whitelist**: the request is ignored, and it is not picked up later when someone who is listed edits the PR. To ask for a review, remove the bot as reviewer and add it again, or comment `@AI Bot review again`.

## Step 5: Test the Integration

1. Create a new Pull Request with the bot selected as reviewer
2. The bot should post a code review comment
3. If it doesn't work, check the bot's logs for error messages

## Screenshots

### Pull Request Code Review

The bot reviews pull requests when explicitly requested and posts AI-generated feedback:

<img src="screenshots/bitbucket/bitbucket-code-review.png" alt="Bitbucket — Pull Request Code Review" width="700"/>

## Troubleshooting

### Error: "No diff found for PR"
- The Bitbucket diff endpoint returns a redirect. Make sure your bot deployment can follow HTTP redirects.
- Verify the token and email are correct.

### Error: "401 Unauthorized"
- Verify the email is the one of the Atlassian account that created the API token
- Make sure the API token has not expired
- Regenerate the API token and update the Git Integration

### Error: "403 Forbidden"
- The API token is missing a scope; compare it with [Required Scopes](#required-scopes)

### Bot ignores every event / Last Error shows "Could not resolve the Bitbucket account"
- The bot calls `GET /2.0/user` to resolve its own account. Check that the token has the `read:user:bitbucket` scope and is valid. Events are ignored until the account can be resolved, so the bot never reacts to its own comments. The failure is shown as the bot's **Last Error** in the admin UI.
- Integrations created before API-token support may still hold an App Password. Replace it with an Atlassian account API token.

### Error: "Webhook ignored"
- Check that the webhook URL includes the correct webhook secret
- Verify the bot is enabled in the admin UI
- Check that the Git Integration's provider type is set to BITBUCKET

### Error: "No bot found for webhook secret"
- The webhook secret in the URL doesn't match any configured bot
- Verify the webhook URL in Bitbucket matches your bot's secret

## Required Scopes

The minimum required scopes for the API token:

| Scope | Required For |
|-------|--------------|
| `read:user:bitbucket` | Resolving the bot's own account and e-mail address (reviewer/mention detection, ignoring own comments, linking commits to its profile; without access to the e-mail address, the bot commits as `ai-agent@bot.local`) |
| `read:repository:bitbucket` | Fetching PR diffs, reading file contents, cloning |
| `write:repository:bitbucket` | Pushing commits from PR workflows (e.g. generated tests) |
| `read:pullrequest:bitbucket` | Reading PR information and comments |
| `write:pullrequest:bitbucket` | Posting review comments |

