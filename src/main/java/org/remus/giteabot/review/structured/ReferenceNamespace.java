package org.remus.giteabot.review.structured;

/**
 * Public-reference namespaces. Each one owns a counter row and a prefix, so a reference is
 * readable on its own ({@code O1234}) and cannot be confused with another record kind.
 *
 * <p>Namespaces ship with the steps that introduce their records.</p>
 */
public enum ReferenceNamespace {

    OUTPUT("workflow_output", "O"),

    FEEDBACK("workflow_feedback", "FB"),

    LESSON("knowledge_lesson", "L");

    private final String key;
    private final String prefix;

    ReferenceNamespace(String key, String prefix) {
        this.key = key;
        this.prefix = prefix;
    }

    public String key() {
        return key;
    }

    public String prefix() {
        return prefix;
    }
}
