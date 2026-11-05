package org.remus.giteabot.azuredevops.model;

import lombok.AccessLevel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.remus.giteabot.repository.model.CommentId;

@Data
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ThreadCommentId implements CommentId {
    private final Long threadId;
    private final Long commentId;

    public static ThreadCommentId of(Long threadId, Long commentId) {
        if (threadId == null || commentId == null) {
            return null;
        }
        return new ThreadCommentId(threadId, commentId);
    }

    /** Mirrors the REST path segments {@code threads/{threadId}/comments/{commentId}}. */
    @Override
    public String toString() {
        return threadId + "/" + commentId;
    }
}
