package org.remus.giteabot.admin.configexchange;

import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the configuration-document state to every Thymeleaf page.
 *
 * <p>This lives in the feature package rather than in {@code config} on purpose: the
 * {@code config} package must not depend on {@code admin} (see
 * {@code ArchitectureTest.config_package_is_independent}), and the banner is a property of
 * this feature, not of the admin layer.</p>
 *
 * <p>{@link ConfigMode} is imported explicitly because {@code @WebMvcTest} slices load
 * every controller advice but no plain components.</p>
 */
@ControllerAdvice
@Import(ConfigMode.class)
public class ConfigBannerAdvice {

    private final ConfigMode configMode;

    public ConfigBannerAdvice(ConfigMode configMode) {
        this.configMode = configMode;
    }

    /** Name of {@link ConfigMode.State}; the layout renders one of four messages from it. */
    @ModelAttribute("configState")
    public String configState() {
        return configMode.state().name();
    }
}
