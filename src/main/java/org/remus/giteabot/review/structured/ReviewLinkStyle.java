package org.remus.giteabot.review.structured;

import java.util.function.Function;

/**
 * Renders a source location as a link when the provider can address one. Everything else
 * about the comment is provider-independent, so only this hook differs between transports.
 *
 * <p>{@link #NONE} renders locations as plain text, which is what a provider without
 * addressable source paths (or a re-render for a plain comment) needs.</p>
 */
@FunctionalInterface
public interface ReviewLinkStyle {

    /** Location as the renderer knows it; the style turns it into a URL or returns null. */
    record FileRef(String path, ReviewSide side, Integer startLine, Integer endLine) {
    }

    ReviewLinkStyle NONE = ref -> null;

    /** Absolute URL for the location, or {@code null} to render it as text. */
    String fileUrl(FileRef ref);

    static ReviewLinkStyle markdown(Function<FileRef, String> urlBuilder) {
        return urlBuilder::apply;
    }
}
