package org.remus.giteabot.secret;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class SecretTemplateParser {

    private static final Pattern SECRET_REFERENCE_PATTERN = Pattern.compile("(?<escape>\\$)?\\$\\{\\s*(?<type>[a-z]+)\\s*:\\s*(?<key>[^}]+?)\\s*}");

    private final SecretSourceRegistry secretSourceRegistry;

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
            if (!segment.isEmpty()) {
                segments.add(new Segment.Literal(segment));
            }

            String matched = matcher.group();
            if (matcher.group("escape") != null) {
                segments.add(new Segment.Literal(matched.substring(1)));
            } else {
                segments.add(new Segment.SecretReference(matcher.group("type"), matcher.group("key"), matched));
            }

            lastMatchEndPos = matcher.end();
        } while (matcher.find());

        if (lastMatchEndPos < value.length()) {
            segments.add(new Segment.Literal(value.substring(lastMatchEndPos)));
        }

        return new SecretTemplate(secretSourceRegistry, segments);
    }
}
