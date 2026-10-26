package org.remus.giteabot.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

@Component
public class ApplicationVersion {

    private static final String FALLBACK_VERSION = "dev";

    private final String version;

    public ApplicationVersion(ObjectProvider<BuildProperties> buildProperties) {
        this.version = buildProperties.stream()
                .findFirst()
                .map(BuildProperties::getVersion)
                .orElse(FALLBACK_VERSION);
    }

    public String get() {
        return version;
    }
}
