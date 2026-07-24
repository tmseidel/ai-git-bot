-- V35: Migrate existing agentic-review formal decision prompts to the new
-- severity-classification contract. The old prompt asked the model to emit
-- APPROVE / REQUEST_CHANGES / NONE directly; the new prompt asks only for a
-- classification count by BLOCKER, MEDIUM and LOW severity, which the
-- application then evaluates against configured thresholds.
--
-- Only rows that already have a non-empty custom prompt are updated. Rows
-- using the Java-side default (NULL or empty param_value) will automatically
-- pick up the new default text from AgentReviewWorkflow on the next run.

UPDATE workflow_selection_params p
SET param_value = '# Formal Review Decision

After writing your review findings, classify them by severity:

- **BLOCKER** — Critical issues that must be fixed before merging: bugs, security vulnerabilities, broken tests, missing error handling, or any problem that could cause a production defect.

- **MEDIUM** — Notable issues that should be addressed but may not block merging on their own: significant code-quality concerns, unclear naming, missing tests for edge cases, or maintainability risks.

- **LOW** — Minor issues, style nits, optional suggestions, or small observations that should not block merging.

The application will decide whether to approve or request changes based on the configured thresholds for each severity class. Do not emit APPROVE, REQUEST_CHANGES, or NONE yourself; only provide the classification counts below.'
WHERE p.name = 'formalReviewDecisionPrompt'
  AND p.param_value IS NOT NULL
  AND length(trim(p.param_value)) > 0
  AND EXISTS (
      SELECT 1 FROM workflow_selections s
      WHERE s.id = p.workflow_selection_id
        AND s.workflow_key = 'agentic-review'
  );
