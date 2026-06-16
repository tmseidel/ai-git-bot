package org.remus.giteabot.agent.session;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.session.ConversationMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing agent coding sessions.
 */
@Slf4j
@Service
public class AgentSessionService {

    /**
     * Threshold for total content size (in characters) that triggers compaction
     * of persisted agent session history. Set higher than the classic
     * {@link org.remus.giteabot.session.SessionService} threshold (50k) because
     * agentic workflows produce longer conversations.
     */
    private static final int COMPACT_THRESHOLD_CHARS = 80_000;

    /**
     * Maximum number of messages to retain in persisted context after compaction.
     * Set higher than classic (4) because agentic follow-up runs need more
     * continuity from the previous conversation.
     */
    private static final int MAX_MESSAGES_AFTER_COMPACT = 8;

    private final AgentSessionRepository repository;

    public AgentSessionService(AgentSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AgentSession createSession(String owner, String repo, Long issueNumber, String issueTitle) {
        return createSession(owner, repo, issueNumber, issueTitle, AgentSession.AgentSessionType.CODING, null);
    }

    @Transactional
    public AgentSession createSession(String owner, String repo, Long issueNumber, String issueTitle,
                                      AgentSession.AgentSessionType sessionType, String issueAuthorUsername) {
        log.info("Creating new {} agent session for issue #{} in {}/{}",
                sessionType, issueNumber, owner, repo);
        AgentSession session = new AgentSession(owner, repo, issueNumber, issueTitle);
        session.setSessionType(sessionType);
        session.setIssueAuthorUsername(issueAuthorUsername);
        return repository.save(session);
    }

    @Transactional(readOnly = true)
    public Optional<AgentSession> getSessionByIssue(String owner, String repo, Long issueNumber) {
        return repository.findByRepoOwnerAndRepoNameAndIssueNumber(owner, repo, issueNumber);
    }

    @Transactional
    public Optional<AgentSession> claimSessionForUpdate(String owner, String repo, Long issueNumber,
                                                        AgentSession.AgentSessionType sessionType) {
        Optional<AgentSession> sessionOpt = repository.findByRepoOwnerAndRepoNameAndIssueNumberForUpdate(
                owner, repo, issueNumber);
        if (sessionOpt.isEmpty()) {
            return Optional.empty();
        }
        AgentSession session = sessionOpt.get();
        if (session.getSessionType() != sessionType
                || session.getStatus() == AgentSession.AgentSessionStatus.UPDATING
                || session.getStatus() == AgentSession.AgentSessionStatus.ISSUE_CREATED
                || session.getStatus() == AgentSession.AgentSessionStatus.FAILED) {
            return Optional.empty();
        }
        session.setStatus(AgentSession.AgentSessionStatus.UPDATING);
        AgentSession savedSession = repository.save(session);
        savedSession.getMessages().size();
        return Optional.of(savedSession);
    }

    @Transactional(readOnly = true)
    public Optional<AgentSession> getSessionByPr(String owner, String repo, Long prNumber) {
        return repository.findByRepoOwnerAndRepoNameAndPrNumber(owner, repo, prNumber);
    }

    @Transactional
    public AgentSession addMessage(AgentSession session, String role, String content) {
        session.addMessage(role, content);
        return repository.save(session);
    }

    /**
     * Step 7.1 — persist the most recently parsed implementation plan on the
     * session row so that PR-body and follow-up comment generation can read
     * the latest plan in O(1) instead of re-parsing the entire conversation
     * history.
     *
     * @param session the session to update (mutated in place and saved)
     * @param summary short human-readable summary (typically {@code plan.summary()}); may be {@code null}
     * @param rawJson raw JSON representation of the plan; may be {@code null}
     * @return the saved entity
     */
    @Transactional
    public AgentSession recordPlan(AgentSession session, String summary, String rawJson) {
        session.setLastPlanSummary(summary);
        session.setLastPlanJson(rawJson);
        session.setLastPlanAt(Instant.now());
        return repository.save(session);
    }

    @Transactional
    public AgentSession setBranchName(AgentSession session, String branchName) {
        session.setBranchName(branchName);
        return repository.save(session);
    }

    @Transactional
    public AgentSession setPrNumber(AgentSession session, Long prNumber) {
        session.setPrNumber(prNumber);
        session.setStatus(AgentSession.AgentSessionStatus.PR_CREATED);
        return repository.save(session);
    }

    @Transactional
    public AgentSession setGeneratedIssueNumber(AgentSession session, Long generatedIssueNumber) {
        session.setGeneratedIssueNumber(generatedIssueNumber);
        session.setStatus(AgentSession.AgentSessionStatus.ISSUE_CREATED);
        return repository.save(session);
    }

    @Transactional
    public AgentSession setStatus(AgentSession session, AgentSession.AgentSessionStatus status) {
        session.setStatus(status);
        return repository.save(session);
    }

    /**
     * Persists cumulative token usage for an agent session.
     *
     * @param session the agent session
     * @param totalInputTokens cumulative input tokens
     * @param totalOutputTokens cumulative output tokens
     */
    @Transactional
    public void recordTokenUsage(AgentSession session, long totalInputTokens, long totalOutputTokens) {
        session.setTotalInputTokens(totalInputTokens);
        session.setTotalOutputTokens(totalOutputTokens);
        repository.save(session);
    }

    /**
     * Compacts the persisted agent session history when total content size
     * exceeds {@value #COMPACT_THRESHOLD_CHARS} characters. Keeps the
     * {@value #MAX_MESSAGES_AFTER_COMPACT} most recent messages and replaces
     * the removed portion with a summary placeholder.
     *
     * <p>This is the agentic equivalent of
     * {@link org.remus.giteabot.session.SessionService#compactContextWindow}.
     * It should be called between agent runs (e.g., after a follow-up comment
     * triggers a new coding round) to prevent the DB-persisted history from
     * growing without bound across sessions.</p>
     *
     * <p>Tool-role messages and blank-content assistant messages are already
     * dropped by {@link #toAiMessages} during replay, so this method only
     * operates on the meaningful user/assistant messages.</p>
     *
     * @param session the session to compact (mutated in place and saved)
     * @return the saved session
     */
    @Transactional
    public AgentSession compactContextWindow(AgentSession session) {
        List<ConversationMessage> sorted = new ArrayList<>(session.getMessages());
        sorted.sort(Comparator.comparing(ConversationMessage::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        if (sorted.size() <= MAX_MESSAGES_AFTER_COMPACT) {
            log.debug("Agent session {} has {} messages, no compaction needed",
                    session.getId(), sorted.size());
            return session;
        }

        int totalChars = sorted.stream()
                .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum();

        if (totalChars < COMPACT_THRESHOLD_CHARS) {
            log.debug("Agent session {} has {} chars, below threshold {}, no compaction needed",
                    session.getId(), totalChars, COMPACT_THRESHOLD_CHARS);
            return session;
        }

        log.info("Compacting agent session {} context window: {} messages, {} chars -> keeping last {}",
                session.getId(), sorted.size(), totalChars, MAX_MESSAGES_AFTER_COMPACT);

        // Identify messages to remove (all but the most recent N)
        int removeCount = sorted.size() - MAX_MESSAGES_AFTER_COMPACT;
        List<ConversationMessage> toRemove = sorted.subList(0, removeCount);

        // Build a summary of what was removed
        String summary = buildAgentContextSummary(toRemove);

        // Remove old messages from the Set (orphanRemoval = true will DELETE from DB)
        session.getMessages().removeAll(toRemove);

        // Add a summary message as the first message in the surviving history
        if (!summary.isBlank()) {
            session.addMessage("user", summary);
        }

        int newTotalChars = session.getMessages().stream()
                .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum();

        log.info("Agent session {} compacted: {} messages, {} chars remaining",
                session.getId(), session.getMessages().size(), newTotalChars);

        return repository.save(session);
    }

    /**
     * Builds a brief summary of removed conversation context for the agentic
     * workflow. Tailored to coding sessions (references files, PRs, tool calls).
     */
    private String buildAgentContextSummary(List<ConversationMessage> removedMessages) {
        if (removedMessages.isEmpty()) {
            return "";
        }

        long userMessages = removedMessages.stream()
                .filter(m -> "user".equals(m.getRole())).count();
        long assistantMessages = removedMessages.stream()
                .filter(m -> "assistant".equals(m.getRole())).count();

        return String.format(
                "[Previous agent session context was compacted to save space. "
                + "This is a coding agent session. "
                + "%d previous exchanges were summarized. "
                + "You can re-read files or re-run tools if you need to recover earlier context.]",
                Math.min(userMessages, assistantMessages));
    }


    /**
     * Converts stored conversation messages to provider-agnostic AI message format.
     * Messages are sorted by creation time to maintain conversation order.
     *
     * <p><strong>Tool-flow messages are intentionally dropped:</strong> the
     * persisted {@link ConversationMessage} entity only stores {@code role}
     * and {@code content} — it does <em>not</em> preserve the native
     * {@code tool_calls} payload of assistant turns nor the
     * {@code tool_call_id} of {@code role:"tool"} turns. Replaying such
     * orphaned messages to OpenAI/Anthropic in a follow-up run would fail
     * with errors like
     * <em>"messages with role 'tool' must be a response to a preceeding
     * message with 'tool_calls'"</em>. Since prior tool executions have
     * already been committed/pushed and the agent can re-discover state via
     * tools, we strip:
     * <ul>
     *   <li>every {@code role:"tool"} message,</li>
     *   <li>assistant messages whose content is blank (these were
     *       tool-call-only turns whose {@code tool_calls} payload is lost).</li>
     * </ul>
     */
    public List<AiMessage> toAiMessages(AgentSession session) {
        return session.getMessages().stream()
                .sorted(Comparator.comparing(ConversationMessage::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .filter(m -> !"tool".equalsIgnoreCase(m.getRole()))
                .filter(m -> !("assistant".equalsIgnoreCase(m.getRole())
                        && (m.getContent() == null || m.getContent().isBlank())))
                .map(m -> AiMessage.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .build())
                .toList();
    }
}
