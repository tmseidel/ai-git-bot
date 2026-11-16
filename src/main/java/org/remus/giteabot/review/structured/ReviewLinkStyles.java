package org.remus.giteabot.review.structured;

import org.remus.giteabot.repository.RepositoryPlatform;
import org.remus.giteabot.repository.RepositoryType;

/**
 * Provider-specific source-link styles for a rendered review. Locations stay plain text when the
 * provider has no addressable line anchor (or when the revision is unknown), which is the safe
 * default: a wrong link is worse than no link.
 */
public final class ReviewLinkStyles {

    private ReviewLinkStyles() {
    }

    /**
     * @param platform the integration's platform; its clone URL is the credential-free web base,
     *                 e.g. {@code https://github.com}
     * @param revision commit the review was generated against; {@code null} disables links
     */
    public static ReviewLinkStyle forProvider(RepositoryType type, RepositoryPlatform platform,
                                              String owner, String repo, String revision) {
        String cloneBaseUrl = platform != null ? platform.getCloneUrl() : null;
        if (type == null || isEmpty(cloneBaseUrl) || isEmpty(owner) || isEmpty(repo)
                || isEmpty(revision)) {
            return ReviewLinkStyle.NONE;
        }
        if (type == RepositoryType.AZURE_DEVOPS) {
            return ref -> platform.getFileWebUrl(owner, repo, revision, ref.path(),
                    ref.startLine(), ref.endLine()).orElse(null);
        }
        String base = stripTrailingSlash(cloneBaseUrl) + "/" + owner + "/" + repo;
        return ref -> {
            String path = encodePath(ref.path());
            return switch (type) {
                case GITEA -> base + "/src/commit/" + revision + "/" + path
                        + anchor(ref, "#L", "-L");
                case GITHUB -> base + "/blob/" + revision + "/" + path
                        + anchor(ref, "#L", "-L");
                case GITLAB -> base + "/-/blob/" + revision + "/" + path
                        + anchor(ref, "#L", "-");
                case BITBUCKET -> base + "/src/" + revision + "/" + path
                        + anchor(ref, "#lines-", ":");
                case AZURE_DEVOPS -> throw new IllegalStateException("handled above");
            };
        };
    }

    private static String anchor(ReviewLinkStyle.FileRef ref, String singlePrefix, String endSeparator) {
        if (ref.startLine() == null) {
            return "";
        }
        if (ref.endLine() == null || ref.endLine().equals(ref.startLine())) {
            return singlePrefix + ref.startLine();
        }
        return singlePrefix + ref.startLine() + endSeparator + ref.endLine();
    }

    private static String encodePath(String path) {
        return path == null ? "" : path.replace(" ", "%20");
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isBlank();
    }
}
