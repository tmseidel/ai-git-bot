-- An agentic review is now published from the structured draft the model records through the
-- review tools, not from Markdown the model writes. The seeded role description still ended with
-- "reply with your final review as plain Markdown and stop calling tools", which contradicts that
-- protocol (a text reply is never published).
--
-- Only rows that still carry that seeded sentence are touched: an operator-customised role
-- description is their decision, so this migration replaces the sentence and leaves everything
-- else — including any customised criteria around it — as it is. Re-running is a no-op because the
-- guarded text no longer exists afterwards.
--
-- Idempotent: the WHERE guard makes a second application impossible.

UPDATE system_prompts
SET review_agent_system_prompt = REPLACE(
        REPLACE(
            review_agent_system_prompt,
            'reply with your final review as',
            'record the review with the review tools:'),
        'plain Markdown and stop calling tools.',
        'submit each finding, set the overall assessment, then complete the review. '
        || 'The application renders the published comment from those records, so do not write '
        || 'the review yourself.')
WHERE review_agent_system_prompt LIKE '%plain Markdown and stop calling tools.%';
