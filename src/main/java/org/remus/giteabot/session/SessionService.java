package org.remus.giteabot.session;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class SessionService {

    /**
     * Maximum number of messages to retain in context after compaction.
     * Keeps the most recent messages for continuity.
     */
    private static final int MAX_MESSAGES_AFTER_COMPACT = 4;

    /**
     * Threshold for total content size (in characters) that triggers compaction.
     * Large diffs can easily exceed 100KB+ per message.
     */
    private static final int COMPACT_THRESHOLD_CHARS = 50000;

    private final ReviewSessionRepository repository;

    public SessionService(ReviewSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ReviewSession getOrCreateSession(String owner, String repo, Long prNumber, String promptName) {
        Optional<ReviewSession> existing = repository.findByRepoOwnerAndRepoNameAndPrNumber(owner, repo, prNumber);
        if (existing.isPresent()) {
            log.info("Reusing existing session for PR #{} in {}/{}", prNumber, owner, repo);
            return existing.get();
        }

        log.info("Creating new session for PR #{} in {}/{}", prNumber, owner, repo);
        ReviewSession session = new ReviewSession(owner, repo, prNumber, promptName);
        return repository.save(session);
    }

    @Transactional(readOnly = true)
    public Optional<ReviewSession> getSession(String owner, String repo, Long prNumber) {
        return repository.findByRepoOwnerAndRepoNameAndPrNumber(owner, repo, prNumber);
    }

    @Transactional
    public ReviewSession addMessage(ReviewSession session, String role, String content) {
        session.addMessage(role, content);
        return repository.save(session);
    }

    @Transactional
    public void deleteSession(String owner, String repo, Long prNumber) {
        log.info("Deleting session for PR #{} in {}/{}", prNumber, owner, repo);
        repository.deleteByRepoOwnerAndRepoNameAndPrNumber(owner, repo, prNumber);
    }

    /**
     * Compacts the context window by removing old messages when the total size exceeds the threshold.
     * Keeps only the most recent messages to maintain continuity while reducing memory/token usage.
     *
     * <p>This should be called after posting a review to prevent unbounded growth of the conversation history.
     *
     * @param session the session to compact
     * @return the compacted session
     */
    @Transactional
    public ReviewSession compactContextWindow(ReviewSession session) {
        List<ConversationMessage> messages = session.getMessages();

        if (messages.size() <= MAX_MESSAGES_AFTER_COMPACT) {
            log.debug("Session {} has {} messages, no compaction needed", session.getId(), messages.size());
            return session;
        }

        // Calculate total content size
        int totalChars = messages.stream()
                .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum();

        if (totalChars < COMPACT_THRESHOLD_CHARS) {
            log.debug("Session {} has {} chars, below threshold {}, no compaction needed",
                    session.getId(), totalChars, COMPACT_THRESHOLD_CHARS);
            return session;
        }

        log.info("Compacting session {} context window: {} messages, {} chars -> keeping last {} messages",
                session.getId(), messages.size(), totalChars, MAX_MESSAGES_AFTER_COMPACT);

        // Keep only the most recent messages
        int removeCount = messages.size() - MAX_MESSAGES_AFTER_COMPACT;

        // Create a summary of what was removed
        String contextSummary = buildContextSummary(messages.subList(0, removeCount));

        // Remove old messages (from the beginning)
        for (int i = 0; i < removeCount; i++) {
            messages.removeFirst();
        }

        // Add a context summary as the first message if we removed substantial content
        if (!contextSummary.isBlank()) {
            ConversationMessage summaryMessage = new ConversationMessage("user", contextSummary);
            messages.addFirst(summaryMessage);
        }

        int newTotalChars = messages.stream()
                .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
                .sum();

        log.info("Session {} compacted: {} messages, {} chars remaining",
                session.getId(), messages.size(), newTotalChars);

        return repository.save(session);
    }

    /**
     * Builds a brief summary of the removed conversation context.
     */
    private String buildContextSummary(List<ConversationMessage> removedMessages) {
        if (removedMessages.isEmpty()) {
            return "";
        }

        // Extract key information from removed messages
        StringBuilder summary = new StringBuilder();
        summary.append("[Previous conversation context was compacted. ");
        summary.append("This discussion involves a code review. ");

        // Count message types
        long userMessages = removedMessages.stream().filter(m -> "user".equals(m.getRole())).count();
        long assistantMessages = removedMessages.stream().filter(m -> "assistant".equals(m.getRole())).count();

        summary.append(String.format("%d previous exchanges were summarized to save context space.]",
                Math.min(userMessages, assistantMessages)));

        return summary.toString();
    }

    /**
     * Converts stored conversation messages to provider-agnostic AI message format.
     */
    public List<AiMessage> toAiMessages(ReviewSession session) {
        return session.getMessages().stream()
                .map(m -> AiMessage.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .build())
                .toList();
    }
}
