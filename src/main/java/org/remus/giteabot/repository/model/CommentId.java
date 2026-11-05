package org.remus.giteabot.repository.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Data;
import lombok.RequiredArgsConstructor;

public interface CommentId {
    @Data
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    final class NumericCommentId implements CommentId {
        @JsonValue
        private final Long id;

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static NumericCommentId of(Long id) {
            if (id == null) {
                return null;
            }
            return new NumericCommentId(id);
        }

        @Override
        public String toString() {
            return String.valueOf(id);
        }
    }
}

