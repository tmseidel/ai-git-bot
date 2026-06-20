package org.remus.giteabot.agent.session;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.session.ConversationMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /**
     * Persists a batch of messages and the latest cumulative token counts for a
     * single agent-loop round in one transaction. This replaces the per-message
     * {@link #addMessage} calls (and the separate token-usage write) that the
     * loop previously issued, collapsing N micro-transactions per round into one.
     *
     * <p>The messages are appended to a freshly-fetched managed entity, so a
     * (possibly detached) caller object whose {@code messages} collection may
     * reference rows deleted by a prior compaction is never traversed by
     * {@code merge()}. Callers must rebind to the returned managed entity.</p>
     *
     * @param sessionId         id of the session to update
     * @param messages          the round's pending messages, in order
     * @param totalInputTokens  cumulative input tokens to persist
     * @param totalOutputTokens cumulative output tokens to persist
     * @return the managed entity with the appended messages and updated token counts
     */
    @Transactional
    public AgentSession flushMessages(Long sessionId, List<PendingMessage> messages,
                                      long totalInputTokens, long totalOutputTokens) {
        AgentSession managed = repository.getReferenceById(sessionId);
        // Stamp call-ordered, strictly-increasing timestamps (1µs apart, which
        // survives microsecond-resolution timestamp columns). The messages are an
        // unordered Set replayed by createdAt, so relying on the @PrePersist
        // default would scramble a batch into hash-iteration order. Subsequent
        // rounds flush seconds later, so cross-round order follows wall-clock.
        Instant base = Instant.now();
        for (int i = 0; i < messages.size(); i++) {
            PendingMessage msg = messages.get(i);
            managed.addMessage(msg.role(), msg.content(), base.plus(i, ChronoUnit.MICROS));
        }
        managed.setTotalInputTokens(totalInputTokens);
        managed.setTotalOutputTokens(totalOutputTokens);
        return managed; // dirty checking flushes inserts + token counts on commit
    }

    /**
     * Step 7.1 — persist the most recently parsed implementation plan on the
     * session row so that PR-body and follow-up comment generation can read
     * the latest plan in O(1) instead of re-parsing the entire conversation
     * history.
     *
     * @param session the session to update (identified by its id)
     * @param summary short human-readable summary (typically {@code plan.summary()}); may be {@code null}
     * @param rawJson raw JSON representation of the plan; may be {@code null}
     * @return the managed entity with the updated plan
     */
    @Transactional
    public AgentSession recordPlan(AgentSession session, String summary, String rawJson) {
        // Mutate only a managed proxy and return it. Persisting via the managed
        // entity avoids merge() on a (possibly detached) caller object whose
        // messages collection may reference rows deleted by a prior compaction.
        // Callers must rebind to the returned entity to observe the new values.
        AgentSession managed = repository.getReferenceById(session.getId());
        managed.setLastPlanSummary(summary);
        managed.setLastPlanJson(rawJson);
        managed.setLastPlanAt(Instant.now());
        return managed;
    }

    @Transactional
    public AgentSession setBranchName(AgentSession session, String branchName) {
        AgentSession managed = repository.getReferenceById(session.getId());
        managed.setBranchName(branchName);
        return managed;
    }

    @Transactional
    public AgentSession setPrNumber(AgentSession session, Long prNumber) {
        AgentSession managed = repository.getReferenceById(session.getId());
        managed.setPrNumber(prNumber);
        managed.setStatus(AgentSession.AgentSessionStatus.PR_CREATED);
        return managed;
    }

    @Transactional
    public AgentSession setGeneratedIssueNumber(AgentSession session, Long generatedIssueNumber) {
        AgentSession managed = repository.getReferenceById(session.getId());
        managed.setGeneratedIssueNumber(generatedIssueNumber);
        managed.setStatus(AgentSession.AgentSessionStatus.ISSUE_CREATED);
        return managed;
    }

    /**
     * Truncates the content of all persisted tool-role messages for a session,
     * shortening each one with head+tail truncation. Called when the context
     * window token budget is exceeded — this keeps tool results compact in
     * the persisted history rather than deleting them entirely.
     *
     * @param sessionId id of the session whose tool messages should be truncated
     * @param maxResultChars max characters to keep per tool result after truncation
     * @return the managed entity
     */
    @Transactional
    public AgentSession truncateToolMessages(Long sessionId, int maxResultChars) {
        AgentSession managed = repository.findById(sessionId).orElseThrow();
        int truncated = 0;
        for (ConversationMessage msg : managed.getMessages()) {
            if ("tool".equalsIgnoreCase(msg.getRole())
                    && msg.getContent() != null
                    && msg.getContent().length() > maxResultChars) {
                msg.setContent(truncateToolResult(msg.getContent(), maxResultChars));
                truncated++;
            }
        }
        if (truncated > 0) {
            log.debug("Truncated {} tool message(s) to max {} chars in session {}",
                    truncated, maxResultChars, sessionId);
        }
        return managed;
    }

    /**
     * Head+tail truncation for a tool result string. Keeps the first half of
     * {@code maxChars} from the head, the second half from the tail, with a
     * marker showing how many characters were removed.
     */
    public static String truncateToolResult(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        int headSize = maxChars / 2;
        int markerOverhead = 60; // "… [N chars truncated] …"
        int tailSize = maxChars - headSize - markerOverhead;
        if (tailSize <= 0) {
            return text.substring(0, maxChars) + "\n… [truncated]";
        }
        int truncatedChars = text.length() - headSize - tailSize;
        return text.substring(0, headSize)
                + "\n… [" + truncatedChars + " chars truncated] …\n"
                + text.substring(text.length() - tailSize);
    }

    @Transactional
    public AgentSession setStatus(AgentSession session, AgentSession.AgentSessionStatus status) {
        AgentSession managed = repository.getReferenceById(session.getId());
        managed.setStatus(status);
        return managed;
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
     * @param sessionId id of the session to compact
     * @return the managed (compacted) entity; callers must rebind to it because
     *         their detached object's {@code messages} collection still
     *         references the rows this method deleted
     */
    @Transactional
    public AgentSession compactContextWindow(Long sessionId) {
        // Operate on a freshly-fetched managed entity so the caller's detached
        // object — whose messages collection may reference rows a prior compaction
        // deleted — is never traversed (a merge() of it would resolve each element
        // by id and fail with ObjectNotFoundException on the deleted rows).
        AgentSession managed = repository.findById(sessionId).orElseThrow();

        List<ConversationMessage> sorted = new ArrayList<>(managed.getMessages());
        sorted.sort(Comparator.comparing(ConversationMessage::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        if (sorted.size() <= MAX_MESSAGES_AFTER_COMPACT) {
            log.debug("Agent session {} has {} messages, no compaction needed",
                    managed.getId(), sorted.size());
            return managed;
        }

        int totalChars = sorted.stream()
                .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum();

        if (totalChars < COMPACT_THRESHOLD_CHARS) {
            log.debug("Agent session {} has {} chars, below threshold {}, no compaction needed",
                    managed.getId(), totalChars, COMPACT_THRESHOLD_CHARS);
            return managed;
        }

        log.info("Compacting agent session {} context window: {} messages, {} chars -> keeping last {}",
                managed.getId(), sorted.size(), totalChars, MAX_MESSAGES_AFTER_COMPACT);

        // Identify messages to remove (all but the most recent N)
        int removeCount = sorted.size() - MAX_MESSAGES_AFTER_COMPACT;
        List<ConversationMessage> toRemove = sorted.subList(0, removeCount);

        // Build a summary of what was removed
        String summary = buildAgentContextSummary(toRemove);

        // Remove old messages from the managed Set (orphanRemoval = true will DELETE from DB)
        toRemove.forEach(managed.getMessages()::remove);

        // Add a summary message as the first message in the surviving history
        if (!summary.isBlank()) {
            managed.addMessage("user", summary);
        }

        int newTotalChars = managed.getMessages().stream()
                .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum();

        log.info("Agent session {} compacted: {} messages, {} chars remaining",
                managed.getId(), managed.getMessages().size(), newTotalChars);

        return managed; // dirty checking + orphanRemoval handle the flush
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
