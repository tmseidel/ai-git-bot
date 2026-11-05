package org.remus.giteabot.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/** The {@code <name>} declared in {@code pom.xml}, resolved from the generated build info. */
@Component
public class ApplicationName {

    /** Keeps the product name when build info is missing (e.g. running from a plain classpath without a Maven build). */
    private static final String FALLBACK_NAME = "AI Git Bot";

    private final String name;

    public ApplicationName(ObjectProvider<BuildProperties> buildProperties) {
        this.name = buildProperties.stream()
                .map(BuildProperties::getName)
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .findFirst()
                .orElse(FALLBACK_NAME);
    }

    public String get() {
        return name;
    }
}
