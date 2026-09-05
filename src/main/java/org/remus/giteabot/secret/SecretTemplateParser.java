package org.remus.giteabot.secret;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SecretTemplateParser {

    private static final Pattern SECRET_REFERENCE_PATTERN = Pattern.compile("(?<escape>\\$)?\\$\\{\\s*(?<type>[a-z]+)\\s*:\\s*(?<key>[^}]+?)\\s*}");

    private final SecretSourceRegistry secretSourceRegistry;

    public SecretTemplateParser(SecretSourceRegistry secretSourceRegistry) {
        this.secretSourceRegistry = secretSourceRegistry;
    }

    public SecretTemplate parse(String value) {

        if (value == null) {
            return new SecretTemplate(secretSourceRegistry, List.of());
        }

        if (value.isBlank()) {
            return new SecretTemplate(secretSourceRegistry, Collections.singletonList(new Segment.Literal(value)));
        }

        Matcher matcher = SECRET_REFERENCE_PATTERN.matcher(value);

        if (!matcher.find()) {
            return new SecretTemplate(secretSourceRegistry, Collections.singletonList(new Segment.Literal(value)));
        }

        List<Segment> segments = new ArrayList<>();
        int lastMatchEndPos = 0;
        do {
            String segment = value.substring(lastMatchEndPos, matcher.start());
            segments.add(new Segment.Literal(segment));

            if (matcher.group("escape") != null) {
                segments.add(new Segment.Literal(matcher.group(0).substring(1)));
            } else {
                segments.add(new Segment.SecretReference(matcher.group("type"), matcher.group("key"), matcher.group(0)));
            }

            lastMatchEndPos = matcher.end();
        } while (matcher.find());

        if (lastMatchEndPos < value.length()) {
            segments.add(new Segment.Literal(value.substring(lastMatchEndPos)));
        }

        return new SecretTemplate(secretSourceRegistry, segments);
    }
}
