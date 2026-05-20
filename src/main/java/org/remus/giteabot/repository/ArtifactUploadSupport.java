package org.remus.giteabot.repository;

import java.util.Locale;

/**
 * Tiny helpers shared by the per-provider
 * {@link RepositoryApiClient#attachPullRequestArtifact(String, String, Long, String, String, byte[])}
 * overrides in
 * {@code GitLabApiClient}, {@code GiteaApiClient} and {@code BitbucketApiClient}.
 *
 * <p>The provider implementations all follow the same shape:</p>
 * <ol>
 *     <li>Render the artifact with {@link ArtifactCommentRenderer#render(String, String, byte[])}.</li>
 *     <li>If the renderer returned {@link ArtifactCommentRenderer.RenderMode#INLINE_IMAGE}
 *         or {@link ArtifactCommentRenderer.RenderMode#INLINE_TEXT}, just post the
 *         rendered Markdown via {@code postPullRequestComment(...)} — the inline
 *         representation is strictly better UX than uploading the artifact to a
 *         platform bucket and linking to it.</li>
 *     <li>Only when the renderer punted (mode {@code SUMMARY_ONLY} — typically large
 *         binaries) the provider override uploads the file natively and posts a
 *         header + link comment instead.</li>
 * </ol>
 *
 * <p>This class is deliberately framework-free so it can be used from
 * any client without pulling in Spring beans.</p>
 */
public final class ArtifactUploadSupport {

    private ArtifactUploadSupport() {
        // utility
    }

    /**
     * Builds the standard "header + link" Markdown body the per-provider
     * overrides post once they have a native artifact URL to link to.
     */
    public static String buildLinkComment(String fileName, int byteSize, String linkMarkdown) {
        String safeName = (fileName == null || fileName.isBlank()) ? "artifact" : fileName.trim();
        return "**" + escapeMd(safeName) + "** (" + humanBytes(byteSize) + ")\n\n"
                + linkMarkdown + "\n";
    }

    /**
     * Renders {@code [fileName](url)} with the standard escaping applied to
     * both pieces.
     */
    public static String linkMarkdown(String fileName, String url) {
        String safeName = (fileName == null || fileName.isBlank()) ? "artifact" : fileName.trim();
        return "[" + escapeMd(safeName) + "](" + (url == null ? "" : url) + ")";
    }

    public static String humanBytes(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private static String escapeMd(String s) {
        return s.replace("`", "\\`").replace("|", "\\|");
    }
}

