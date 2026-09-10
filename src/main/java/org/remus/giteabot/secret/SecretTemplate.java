package org.remus.giteabot.secret;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class SecretTemplate {
    private final SecretSourceRegistry secretSourceRegistry;
    private final List<Segment> segments;

    SecretTemplate(SecretSourceRegistry secretSourceRegistry, List<Segment> segments) {
        this.secretSourceRegistry = secretSourceRegistry;
        this.segments = segments;
    }

    public String expose() {
        return segments.stream().map(segment -> {
            switch (segment) {
                case Segment.SecretReference sr -> {
                    Optional<SecretSource> optionalSecretSource = secretSourceRegistry.retrieve(sr.type());

                    if (optionalSecretSource.isEmpty()) {
                        log.error("Could not find secret source for {}", sr.raw());
                        return sr.raw();
                    }

                    SecretSource secretSource = optionalSecretSource.get();
                    Optional<SecretValue> resolved = secretSource.resolve(sr.key());
                    if (resolved.isEmpty()) {
                        return sr.raw();
                    }

                    return resolved.get().value().strip();
                }
                case Segment.Literal literal -> {
                    return literal.raw();
                }
            }
        }).collect(Collectors.joining());
    }

    public String raw() {
        return segments.stream().map(Segment::raw).collect(Collectors.joining());
    }
}
