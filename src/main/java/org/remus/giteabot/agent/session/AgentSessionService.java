package org.remus.giteabot.agent.session;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.agent.shared.AgentJackson;
import org.remus.giteabot.ai.AiMessage;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.session.ConversationMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for managing agent coding sessions.
 */
@Slf4j
@Service
public class AgentSessionService {

    /**
     * Default threshold for total content size (in characters) that triggers
     * compaction of persisted agent session history. Set higher than the classic
     * {@link org.remus.giteabot.session.SessionService} threshold (50k) because
     * agentic workflows produce longer conversations. Overridable through
     * {@code agent.session.compact-threshold-chars}.
     */
    static final int DEFAULT_COMPACT_THRESHOLD_CHARS = 80_000;

    /**
     * Default number of messages to retain in persisted context after compaction.
     * Set higher than classic (4) because agentic follow-up runs need more
     * continuity from the previous conversation. Overridable through
     * {@code agent.session.compact-keep-messages}.
     */
    static final int DEFAULT_COMPACT_KEEP_MESSAGES = 8;

    private final AgentSessionRepository repository;
    private final int compactThresholdChars;
    private final int compactKeepMessages;

    /** Uses the built-in compaction defaults; the container picks the value-injected overload. */
    public AgentSessionService(AgentSessionRepository repository) {
        this(repository, DEFAULT_COMPACT_THRESHOLD_CHARS, DEFAULT_COMPACT_KEEP_MESSAGES);
    }

    @Autowired
    public AgentSessionService(AgentSessionRepository repository,
                               @Value("${agent.session.compact-threshold-chars:80000}") int compactThresholdChars,
                               @Value("${agent.session.compact-keep-messages:8}") int compactKeepMessages) {
        // Both values index into the message list, so a nonsensical one does not fail
        // at startup where the operator would see it: keep=0 makes removeCount equal to
        // the window size, and sorted.get(removeCount) then throws on every follow-up
        // of a long session. Reject the value when the bean is built instead.
        if (compactKeepMessages < 1) {
            throw new IllegalArgumentException(
                    "agent.session.compact-keep-messages must be at least 1, was " + compactKeepMessages);
        }
        if (compactThresholdChars < 1) {
            throw new IllegalArgumentException(
                    "agent.session.compact-threshold-chars must be positive, was " + compactThresholdChars);
        }
        this.repository = repository;
        this.compactThresholdChars = compactThresholdChars;
        this.compactKeepMessages = compactKeepMessages;
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
     * <p>Native tool-call payloads are persisted with their message, so a later
     * run can replay an assistant turn together with the tool rows that answer
     * it.</p>
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
            managed.addMessage(msg.role(), msg.content(), base.plus(i, ChronoUnit.MICROS),
                    toolCallsJson(msg.payload()), toolCallId(msg.payload()));
        }
        managed.setTotalInputTokens(totalInputTokens);
        managed.setTotalOutputTokens(totalOutputTokens);
        return managed; // dirty checking flushes inserts + token counts on commit
    }

    /**
     * Serialises an assistant turn's tool calls for storage. Returns {@code null}
     * when the message carries none; a serialisation failure degrades to
     * {@code null} as well — the exchange is then dropped on replay, which is
     * the same outcome as a pre-V53 row and never a broken request.
     */
    private static String toolCallsJson(PendingMessage.ToolPayload payload) {
        if (payload == null || payload.toolCalls() == null || payload.toolCalls().isEmpty()) {
            return null;
        }
        try {
            return AgentJackson.mapper().writeValueAsString(payload.toolCalls());
        } catch (Exception e) {
            log.warn("Could not serialise tool_calls payload: {}", e.getMessage());
            return null;
        }
    }

    private static String toolCallId(PendingMessage.ToolPayload payload) {
        return payload == null ? null : payload.toolCallId();
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
     * exceeds {@code agent.session.compact-threshold-chars} (80k by default).
     * Keeps the {@code agent.session.compact-keep-messages} most recent messages
     * (8 by default) and replaces the removed portion with a summary.
     *
     * <p>This is the agentic equivalent of
     * {@link org.remus.giteabot.session.SessionService#compactContextWindow}.
     * It should be called between agent runs (e.g., after a follow-up comment
     * triggers a new coding round) to prevent the DB-persisted history from
     * growing without bound across sessions.</p>
     *
     * <p>The retained window never starts inside a tool exchange: the same rows
     * back {@link #toAiMessages}' replay, so cutting between an assistant turn and
     * the tool rows that answer it would leave them unpaired. The size counted
     * against the threshold includes the persisted tool-call payload, which is
     * replayed as well.</p>
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

        if (sorted.size() <= compactKeepMessages) {
            log.debug("Agent session {} has {} messages, no compaction needed",
                    managed.getId(), sorted.size());
            return managed;
        }

        int totalChars = sorted.stream().mapToInt(AgentSessionService::messageChars).sum();

        if (totalChars < compactThresholdChars) {
            log.debug("Agent session {} has {} chars, below threshold {}, no compaction needed",
                    managed.getId(), totalChars, compactThresholdChars);
            return managed;
        }

        log.info("Compacting agent session {} context window: {} messages, {} chars -> keeping last {}",
                managed.getId(), sorted.size(), totalChars, compactKeepMessages);

        // Identify messages to remove (all but the most recent N). A window that
        // would start on a tool row is widened back onto the assistant turn that
        // announced the call, so the replay never sees an unanswered pair.
        // Only the boundary row is inspected, so a window whose every retained row
        // were a tool row (possible only with a pathological history) could still cut
        // a pair; toAiMessages then drops the orphans, so the request stays valid.
        int removeCount = sorted.size() - compactKeepMessages;
        while (removeCount > 0 && "tool".equalsIgnoreCase(sorted.get(removeCount).getRole())) {
            removeCount--;
        }
        List<ConversationMessage> toRemove = sorted.subList(0, removeCount);

        // Build a summary of what was removed
        String summary = buildAgentContextSummary(managed, toRemove);

        // Remove old messages from the managed Set (orphanRemoval = true will DELETE from DB)
        toRemove.forEach(managed.getMessages()::remove);

        // Add a summary message as the first message in the surviving history. The
        // collection is an unordered Set replayed in createdAt order, so "first" has
        // to be stamped, not assumed: a default timestamp lands it *after* the
        // retained window, i.e. right before the new prompt, where it reads as a
        // closing note instead of the opening context it summarizes.
        if (!summary.isBlank()) {
            Instant firstRetained = managed.getMessages().stream()
                    .map(ConversationMessage::getCreatedAt)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (firstRetained == null) {
                managed.addMessage("user", summary);
            } else {
                managed.addMessage("user", summary, firstRetained.minus(1, ChronoUnit.MICROS));
            }
        }

        int newTotalChars = managed.getMessages().stream()
                .mapToInt(AgentSessionService::messageChars).sum();

        log.info("Agent session {} compacted: {} messages, {} chars remaining",
                managed.getId(), managed.getMessages().size(), newTotalChars);

        return managed; // dirty checking + orphanRemoval handle the flush
    }

    /**
     * Persisted size of a message: its content plus the native tool-call payload,
     * which is replayed to the provider and therefore counts towards the
     * compaction threshold.
     */
    private static int messageChars(ConversationMessage message) {
        int contentChars = message.getContent() == null ? 0 : message.getContent().length();
        int payloadChars = message.getToolCalls() == null ? 0 : message.getToolCalls().length();
        return contentChars + payloadChars;
    }

    /** Characters kept per quoted user request in the compaction summary. */
    private static final int SUMMARY_REQUEST_CHARS = 160;
    /** Characters kept for the last plan summary. */
    private static final int SUMMARY_PLAN_CHARS = 400;
    /** Upper bound on the files named in the compaction summary. */
    private static final int SUMMARY_MAX_FILES = 20;
    /** Upper bound on the earlier requests quoted in the compaction summary. */
    private static final int SUMMARY_MAX_REQUESTS = 5;

    /** Opening sentence of a compaction summary; a later compaction recognizes its predecessor by it. */
    private static final String SUMMARY_PREFIX =
            "[Previous agent session context was compacted to save space.";

    /**
     * Label of the file list, plus the wording an earlier version emitted: a summary
     * written before this change may still be the row a later compaction removes.
     */
    private static final List<String> SUMMARY_FILES_LABELS =
            List.of("Files edited earlier in this session: ",
                    "Files already changed on that branch: ");

    /** Heading the loop wraps a follow-up comment in; the body below it is what the user wrote. */
    private static final String USER_COMMENT_HEADING = "## New User Comment";

    /**
     * Openings of user turns that carry no human request, so they must not be quoted
     * as "earlier requests". The issue prompt repeats the title and body the summary
     * already states, tool results are not something the user asked for, and the
     * round-budget reminder is the loop talking to itself.
     */
    private static final List<String> SCAFFOLDING_PREFIXES = List.of(
            "## Issue", "## Tool Execution Results", "The review is approaching");

    /** Position of the exit code in a persisted tool row ({@code ToolResult.formatForAi}). */
    private static final Pattern EXIT_CODE = Pattern.compile("Exit code:\\s*(-?\\d+)");

    /**
     * Builds a summary of the removed part of a coding session.
     *
     * <p>A fixed sentence plus a count is not context: the run that compacts has to
     * be able to tell what the task was, where the work lives and what has already
     * been changed. Without that the model re-derives the requirement from the
     * codebase and repeats reads and edits that are already committed.</p>
     */
    private String buildAgentContextSummary(AgentSession session, List<ConversationMessage> removedMessages) {
        if (removedMessages.isEmpty()) {
            return "";
        }

        // A long session compacts more than once, and the summary the previous round
        // left behind sits inside the window this round removes. It is the only place
        // the older files and requests survive, so read it before dropping it.
        ConversationMessage previous = previousSummary(removedMessages);

        long userMessages = removedMessages.stream()
                .filter(m -> "user".equals(m.getRole())).count();
        long assistantMessages = removedMessages.stream()
                .filter(m -> "assistant".equals(m.getRole())).count();

        StringBuilder summary = new StringBuilder();
        summary.append(SUMMARY_PREFIX).append(' ')
                .append("This is a coding agent session. ")
                .append(Math.min(userMessages, assistantMessages))
                .append(" previous exchanges were summarized.");

        if (session.getIssueTitle() != null && !session.getIssueTitle().isBlank()) {
            summary.append("\nIssue: ");
            if (session.getIssueNumber() != null) {
                summary.append("#").append(session.getIssueNumber()).append(" — ");
            }
            summary.append(session.getIssueTitle().strip());
        }
        if (session.getBranchName() != null && !session.getBranchName().isBlank()) {
            summary.append("\nBranch: ").append(session.getBranchName().strip());
        }
        if (session.getLastPlanSummary() != null && !session.getLastPlanSummary().isBlank()) {
            summary.append("\nPlan: ").append(brief(session.getLastPlanSummary(), SUMMARY_PLAN_CHARS));
        }

        // Files the older summary listed come first: they were edited even earlier.
        List<String> files = previousFiles(previous);
        files.addAll(changedFiles(removedMessages));
        List<String> namedFiles = capFiles(files);
        if (!namedFiles.isEmpty()) {
            summary.append("\n").append(SUMMARY_FILES_LABELS.get(0))
                    .append(String.join(", ", namedFiles));
        }

        List<String> requests = earlierRequests(removedMessages, previous);
        if (!requests.isEmpty()) {
            summary.append("\nEarlier requests:");
            requests.forEach(request -> summary.append("\n- \"").append(request).append("\""));
        }

        // The old placeholder invited "re-read files or re-run tools to recover
        // context", which is precisely what a compacted run then did. Its replacement
        // over-corrected: it claimed the changes were committed and told the model not
        // to read the files at all, while a workspace is cloned fresh for every
        // follow-up and `patch-file` needs the file's current text. State only what
        // holds — the branch carries whatever was pushed — and leave reading allowed.
        summary.append("\nThose files were edited earlier in this session; the branch already ")
                .append("contains what was pushed. Read a file only if you need to change it again, ")
                .append("and ask for what you still need instead of assuming the work was not done.]");
        return summary.toString();
    }

    /**
     * The summary a previous compaction left behind, or {@code null}. Recognized by its
     * fixed opening sentence, which is why the text is a constant.
     */
    private static ConversationMessage previousSummary(List<ConversationMessage> removedMessages) {
        for (int index = removedMessages.size() - 1; index >= 0; index--) {
            ConversationMessage message = removedMessages.get(index);
            String content = message.getContent();
            if ("user".equalsIgnoreCase(message.getRole())
                    && content != null && content.strip().startsWith(SUMMARY_PREFIX)) {
                return message;
            }
        }
        return null;
    }

    /** Files named by a previous summary's list, so a second compaction keeps them. */
    private static List<String> previousFiles(ConversationMessage previous) {
        List<String> files = new ArrayList<>();
        if (previous == null || previous.getContent() == null) {
            return files;
        }
        for (String line : previous.getContent().split("\n")) {
            String trimmed = line.strip();
            for (String label : SUMMARY_FILES_LABELS) {
                if (!trimmed.startsWith(label)) {
                    continue;
                }
                for (String file : trimmed.substring(label.length()).split(",")) {
                    String name = file.strip();
                    if (!name.isEmpty()) {
                        files.add(name);
                    }
                }
            }
        }
        return files;
    }

    /**
     * Files the removed part of the session wrote, patched, deleted or created, in the
     * order the calls were made. A call whose own result row reports a non-zero exit
     * code is left out: telling the model the edit is already in place when it is not
     * is worse than saying nothing.
     *
     * <p>Native calls only. A run driven through the legacy text protocol keeps its
     * tool requests inside the assistant's JSON content ({@code runTools}) instead of
     * {@code toolCalls}, so such a window contributes no files.</p>
     */
    private static List<String> changedFiles(List<ConversationMessage> removedMessages) {
        Map<String, String> results = toolResultsById(removedMessages);
        Set<String> files = new LinkedHashSet<>();
        for (ConversationMessage message : removedMessages) {
            if (!"assistant".equalsIgnoreCase(message.getRole())) {
                continue;
            }
            for (ToolCall call : parseToolCalls(message.getToolCalls())) {
                if (!isFileTool(call.name())) {
                    continue;
                }
                if (toolRowFailed(results.get(call.id()))) {
                    continue;
                }
                String path = toolPath(call);
                if (path != null && !path.isBlank()) {
                    files.add(path.strip());
                }
            }
        }
        return new ArrayList<>(files);
    }

    /** Bounds the file list; the first entries are the ones edited earliest. */
    private static List<String> capFiles(List<String> files) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(files));
        return distinct.subList(0, Math.min(distinct.size(), SUMMARY_MAX_FILES));
    }

    /** Result text of every tool row in the window, keyed by the call id it answers. */
    private static Map<String, String> toolResultsById(List<ConversationMessage> removedMessages) {
        Map<String, String> results = new LinkedHashMap<>();
        for (ConversationMessage message : removedMessages) {
            if (!"tool".equalsIgnoreCase(message.getRole())) {
                continue;
            }
            String id = message.getToolCallId();
            if (id != null && !id.isBlank()) {
                results.put(id, message.getContent());
            }
        }
        return results;
    }

    /**
     * True when the row that answers a call says the call did not run or exited
     * non-zero. A missing row is not treated as a failure: rows persisted before the
     * call id was stored cannot be paired, and dropping those calls would lose the
     * history of a session that did its work.
     */
    private static boolean toolRowFailed(String row) {
        if (row == null) {
            return false;
        }
        String text = row.strip();
        // The loop prefixes the stored row with the call id for post-hoc review.
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            if (end > 0) {
                text = text.substring(end + 1).strip();
            }
        }
        if (text.startsWith("not executed")) {
            return true;
        }
        Matcher exit = EXIT_CODE.matcher(text);
        return exit.find() && !"0".equals(exit.group(1));
    }

    /**
     * True for the tools that change the workspace. Mirrors the {@code ToolKind.FILE}
     * entries of {@code ToolCatalog} — a new file tool has to be added in both places,
     * since this runs on persisted history without a catalog in hand.
     */
    private static boolean isFileTool(String name) {
        return "write-file".equals(name) || "patch-file".equals(name)
                || "delete-file".equals(name) || "mkdir".equals(name);
    }

    /**
     * Path argument of a file tool call. Native calls carry the schema-shaped object
     * ({@code {"path": "..."}}); legacy plans carry positional args. Both shapes are
     * read, because the persisted payload is whatever the provider produced.
     */
    private static String toolPath(ToolCall call) {
        var args = call.args();
        if (args == null || args.isNull()) {
            return null;
        }
        var path = args.get("path");
        if (path == null) {
            path = args.get("file");
        }
        if (path != null && path.isTextual()) {
            return path.asText();
        }
        if (args.isArray() && args.size() > 0 && args.get(0).isTextual()) {
            return args.get(0).asText();
        }
        return null;
    }

    /**
     * The last few human turns of the removed window, one line each.
     *
     * <p>What the loop persists as a user turn is mostly <em>not</em> a user turn: the
     * issue prompt, the tool-result round and the round-budget reminder are all written
     * with the user role. Skipping everything that starts with {@code #} was the wrong
     * test — every real follow-up comment arrives inside a {@code ## New User Comment}
     * heading (see {@code AgentPromptBuilder.buildContinuationPrompt}), so the filter
     * dropped exactly the requests it was meant to keep and let only plain-text turns
     * through. Only the known scaffolding is skipped now, and the comment's body is
     * quoted rather than the heading around it.</p>
     *
     * @param previous the previous summary, already surfaced on its own line; its text
     *                 is not a request a human made
     */
    private static List<String> earlierRequests(List<ConversationMessage> removedMessages,
                                                ConversationMessage previous) {
        List<String> requests = new ArrayList<>();
        for (ConversationMessage message : removedMessages) {
            if (!"user".equalsIgnoreCase(message.getRole()) || message == previous) {
                continue;
            }
            String request = requestText(message.getContent());
            if (request == null) {
                continue;
            }
            requests.add(brief(request, SUMMARY_REQUEST_CHARS));
        }
        return requests.subList(Math.max(0, requests.size() - SUMMARY_MAX_REQUESTS), requests.size());
    }

    /**
     * The human part of a user turn, or {@code null} when the turn is scaffolding.
     * An unknown opening is kept: a turn this method cannot classify is more likely a
     * human than a shape the loop started emitting.
     */
    private static String requestText(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.strip();
        if (text.startsWith(USER_COMMENT_HEADING)) {
            String body = text.substring(USER_COMMENT_HEADING.length()).strip();
            // The block after the comment is the discussion context, not what the user wrote.
            int nextHeading = body.indexOf("\n## ");
            if (nextHeading >= 0) {
                body = body.substring(0, nextHeading).strip();
            }
            return body.isEmpty() ? null : body;
        }
        for (String prefix : SCAFFOLDING_PREFIXES) {
            if (text.startsWith(prefix)) {
                return null;
            }
        }
        return text;
    }

    /** Collapses whitespace and bounds a value quoted in the summary. */
    private static String brief(String text, int max) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max).strip() + "…";
    }


    /**
     * Converts stored conversation messages to the provider-agnostic AI message
     * format, sorted by creation time.
     *
     * <p>Native tool exchanges are rebuilt from the persisted payload
     * ({@link ConversationMessage#getToolCalls()} on the assistant turn,
     * {@link ConversationMessage#getToolCallId()} on the tool row), so a follow-up
     * run replays the same pairs the previous run produced. A pair is only replayed
     * <em>complete</em>: the assistant turn keeps its {@code tool_calls} payload only
     * when the tool rows that follow it answer every announced id, and those rows
     * are replayed in the order the calls were announced, once each. Everything else
     * is dropped, because OpenAI and Anthropic both reject a request that breaks the
     * contract — {@code "assistant message with 'tool_calls' must be followed by tool
     * messages"} / {@code "tool_use ids were found without tool_result blocks"}:</p>
     * <ul>
     *   <li>a tool row without a {@code tool_call_id} (persisted before V53), whose
     *       call id no following row answers, or that arrives twice for one id,</li>
     *   <li>an assistant turn only <em>partly</em> answered — it is replayed as plain
     *       content instead, or skipped when its content is blank. This is the shape a
     *       run leaves behind when it finishes on a tool-call turn (the writer's
     *       give-up branch, or any strategy returning {@code Finish} without answering
     *       its calls), and replaying it verbatim would 400 the follow-up run.</li>
     * </ul>
     *
     * <p>Announced ids are rewritten to the {@code [a-zA-Z0-9_-]} alphabet every
     * provider accepts ({@code cat:0} is what a local Ollama integration hands out,
     * Anthropic rejects it) on both sides of the pair, so a session that outlives the
     * integration which wrote it still replays. The {@code "[<id>] "} marker the loop
     * stores in a tool row for post-hoc review is stripped: it is storage metadata,
     * not part of the result the model saw.</p>
     */
    public List<AiMessage> toAiMessages(AgentSession session) {
        List<ConversationMessage> ordered = new ArrayList<>(session.getMessages());
        ordered.sort(Comparator.comparing(ConversationMessage::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())));

        List<AiMessage> replay = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            ConversationMessage message = ordered.get(index);
            String role = message.getRole();
            if ("assistant".equalsIgnoreCase(role)) {
                appendAssistantTurn(replay, ordered, index);
            } else if ("tool".equalsIgnoreCase(role)) {
                // Replayed by the assistant turn that announced the call (if any) and
                // dropped otherwise — an orphaned tool message is not a valid request.
                continue;
            } else {
                replay.add(AiMessage.builder().role(role).content(message.getContent()).build());
            }
        }
        return replay;
    }

    /**
     * Replays one persisted assistant turn together with the tool rows that answer
     * it. See {@link #toAiMessages} for the pairing contract.
     */
    private static void appendAssistantTurn(List<AiMessage> replay,
                                            List<ConversationMessage> ordered, int index) {
        ConversationMessage turn = ordered.get(index);
        List<ToolCall> announced = parseToolCalls(turn.getToolCalls());
        if (announced.isEmpty()) {
            appendPlainTurn(replay, turn);
            return;
        }

        // The rows answering this turn are the run of tool rows right behind it; the
        // loop writes an assistant turn and its results in one flush batch, so
        // adjacency is what pairs them.
        Map<String, ConversationMessage> answers = new LinkedHashMap<>();
        for (int i = index + 1;
                i < ordered.size() && "tool".equalsIgnoreCase(ordered.get(i).getRole()); i++) {
            ConversationMessage row = ordered.get(i);
            if (row.getToolCallId() != null) {
                answers.putIfAbsent(row.getToolCallId(), row);
            }
        }

        if (!announced.stream().allMatch(call -> answers.containsKey(call.id()))) {
            // Unanswered (or only partly answered) calls: the payload cannot be
            // replayed, and the orphaned rows have to go with it.
            appendPlainTurn(replay, turn);
            return;
        }

        Map<String, String> replayedIds = replayedIds(announced);
        replay.add(AiMessage.builder()
                .role(turn.getRole())
                .content(turn.getContent())
                .toolCalls(announced.stream()
                        .map(call -> new ToolCall(replayedIds.get(call.id()), call.name(),
                                call.args(), call.providerMetadata()))
                        .toList())
                .build());
        for (ToolCall call : announced) {
            String result = stripToolMarker(answers.get(call.id()).getContent(), call.id());
            replay.add(AiMessage.builder()
                    .role("tool")
                    .content(result)
                    .toolResult(result)
                    .toolCallId(replayedIds.get(call.id()))
                    .build());
        }
    }

    /** Replays a turn without a payload; a blank turn is skipped entirely. */
    private static void appendPlainTurn(List<AiMessage> replay, ConversationMessage turn) {
        if (turn.getContent() != null && !turn.getContent().isBlank()) {
            replay.add(AiMessage.builder().role(turn.getRole()).content(turn.getContent()).build());
        }
    }

    /**
     * Maps the announced ids onto the {@code [a-zA-Z0-9_-]} alphabet every provider
     * accepts, leaving already-safe ids untouched and resolving collisions (a real
     * {@code cat:0} beside a literal {@code cat_0}) with a numeric suffix.
     */
    private static Map<String, String> replayedIds(List<ToolCall> announced) {
        Map<String, String> replayed = new LinkedHashMap<>();
        Set<String> taken = new LinkedHashSet<>();
        int suffix = 0;
        for (ToolCall call : announced) {
            String id = call.id() == null ? "" : call.id();
            String safe = id.replaceAll("[^a-zA-Z0-9_-]", "_");
            if (safe.isEmpty()) {
                safe = "call";
            }
            String unique = safe;
            while (!taken.add(unique)) {
                unique = safe + "_" + (++suffix);
            }
            replayed.put(id, unique);
        }
        return replayed;
    }

    /**
     * Drops the {@code "[<id>] "} prefix the loop writes into a persisted tool row for
     * post-hoc review: the marker is storage metadata, and the replayed result should
     * read like the one the model got in the run that produced it.
     */
    private static String stripToolMarker(String content, String callId) {
        if (content == null) {
            return null;
        }
        String marker = "[" + callId + "] ";
        return content.startsWith(marker) ? content.substring(marker.length()) : content;
    }

    private static List<ToolCall> parseToolCalls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(AgentJackson.mapper().readValue(json, ToolCall[].class));
        } catch (Exception e) {
            log.warn("Could not parse persisted tool_calls payload: {}", e.getMessage());
            return List.of();
        }
    }
}
