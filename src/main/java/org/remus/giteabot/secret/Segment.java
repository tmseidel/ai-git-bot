package org.remus.giteabot.secret;

public sealed interface Segment permits Segment.Literal, Segment.SecretReference {
    String raw();

    record Literal(String raw) implements Segment {
    }

    record SecretReference(String type, String key, String raw) implements Segment {
    }
}
