package org.remus.giteabot.config;

import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes attributes that every Thymeleaf page needs regardless of the
 * controller that rendered it. Currently only the application version, which
 * the balloon-help tour uses to scope its "already shown" cookie (a new
 * release re-triggers the tour).
 *
 * <p>{@link ApplicationVersion} is imported explicitly because {@code @WebMvcTest}
 * slices load every controller advice but no plain components.
 */
@ControllerAdvice
@Import(ApplicationVersion.class)
public class GlobalModelAttributes {

    private final ApplicationVersion applicationVersion;

    public GlobalModelAttributes(ApplicationVersion applicationVersion) {
        this.applicationVersion = applicationVersion;
    }

    @ModelAttribute("appVersion")
    public String appVersion() {
        return applicationVersion.get();
    }

    @ModelAttribute("supportedLocales")
    public java.util.List<I18nConfig.LocaleOption> supportedLocales() {
        return I18nConfig.SUPPORTED;
    }
}
