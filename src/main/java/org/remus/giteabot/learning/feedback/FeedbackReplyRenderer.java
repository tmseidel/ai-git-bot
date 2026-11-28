package org.remus.giteabot.learning.feedback;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders the bot's answers to feedback commands. Everything here is a deterministic function of
 * persisted records — no model is involved, so the same command always produces the same text.
 *
 * <p>Overviews name the bot they belong to: several bots may share one Git account and so answer the
 * same mention, and each one lists only its own records.</p>
 */
public final class FeedbackReplyRenderer {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private FeedbackReplyRenderer() {
    }

    /** Footer explaining the vocabulary; posted with every acknowledgement. */
    public static String footer() {
        return """
                Reply with `feedback <target> <verdict> <why>` to judge a review, for example \
                `feedback O1234-F2 incorrect The permission check is already in the filter`. \
                Verdicts: %s. Targets: `O1234` (whole review), `O1234-F2` (finding), \
                `O1234-A` (assessment).

                Feedback only records your assessment — it never re-runs a review, changes code, or \
                edits instructions. A later comment from you replaces your earlier assessment of the \
                same target; `feedback retract <FB…>` withdraws one.
                """.formatted(FeedbackVerdict.tokens());
    }

    public static String acknowledged(FeedbackService.Recorded recorded) {
        return """
                Recorded **%s** — `%s` is **%s** (by %s).

                %s""".formatted(recorded.reference(), recorded.targetReference(),
                recorded.verdict().token(), recorded.actor(), footer());
    }

    public static String retracted(FeedbackService.Recorded recorded) {
        return "Retracted **%s** — the assessment of `%s` no longer counts.\n\n%s"
                .formatted(recorded.reference(), recorded.targetReference(), footer());
    }

    /** A recognized command that cannot be carried out: explain and stop. */
    public static String rejected(FeedbackRejectedException rejection) {
        return "%s\n\n%s".formatted(rejection.getMessage(), footer());
    }

    public static String help(String message) {
        return "%s\n\n%s".formatted(message, footer());
    }

    public static String list(boolean repositoryWide, FeedbackService.ListPage page, String botName,
                              String mention, FeedbackLinks links) {
        String scope = repositoryWide ? "in this repository" : "on this pull request";
        String header = "Feedback for **%s** %s".formatted(botName, scope);
        if (page.rows().isEmpty()) {
            return "%s: none yet.\n\n%s".formatted(header, footer());
        }
        StringBuilder sb = new StringBuilder(header).append(":\n\n");
        for (FeedbackService.Summary row : page.rows()) {
            sb.append("- **").append(row.reference()).append("** `")
                    .append(row.targetReference()).append("` **")
                    .append(row.verdict().token()).append("** — ")
                    .append(row.actor());
            if (row.state() != FeedbackState.EFFECTIVE) {
                sb.append(" _(").append(row.state().name().toLowerCase(Locale.ROOT));
                if (row.supersededBy() != null) {
                    sb.append(" by ").append(row.supersededBy());
                }
                sb.append(")_");
            }
            if (repositoryWide) {
                sb.append(" · ").append(link("PR #" + row.prNumber(),
                        links.pullRequest(row.prNumber())));
            }
            sb.append(" · ").append(link(TIMESTAMP.format(row.createdAt()),
                    links.comment(row.prNumber(), row.commentKind(), row.commentId())))
                    .append('\n');
            if (row.reason() != null && !row.reason().isBlank()) {
                sb.append("  > ").append(firstLine(row.reason())).append('\n');
            }
        }
        if (page.nextCursor() != null) {
            sb.append("\nNext page: `").append(mention).append(" feedback list")
                    .append(repositoryWide ? " repository" : "")
                    .append(" page: ").append(page.nextCursor()).append("`\n");
        }
        return sb.toString().trim();
    }

    /**
     * @param lessons labels of lessons citing this record ({@code L17@1 (active)}), so a reader
     *                sees what a retraction would affect
     */
    public static String show(FeedbackService.Detail detail, String botName, String mention,
                              FeedbackLinks links, java.util.List<String> lessons) {
        FeedbackService.Summary row = detail.summary();
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(row.reference()).append("** — `").append(row.targetReference())
                .append("` is **").append(row.verdict().token()).append("** (by ")
                .append(row.actor()).append(", ").append(row.state().name().toLowerCase(Locale.ROOT))
                .append(") · bot **").append(botName).append("**\n\n");
        sb.append("Review `").append(row.outputReference()).append("` on ")
                .append(link("PR #" + row.prNumber(), links.pullRequest(row.prNumber())))
                .append(" in ").append(row.repoOwner()).append('/').append(row.repoName())
                .append(" · ")
                .append(link("source comment",
                        links.comment(row.prNumber(), row.commentKind(), row.commentId())))
                .append(" · ").append(TIMESTAMP.format(row.createdAt())).append('\n');
        if (row.supersededBy() != null) {
            sb.append("Replaced by ").append(row.supersededBy()).append('\n');
        }
        if (row.reason() != null && !row.reason().isBlank()) {
            sb.append("\n> ").append(row.reason().replace("\n", "\n> ")).append('\n');
        }
        if (lessons != null && !lessons.isEmpty()) {
            sb.append("\nLessons citing it: ").append(String.join(", ", lessons)).append('\n');
        }
        if (!detail.history().isEmpty()) {
            sb.append("\nHistory:\n");
            for (FeedbackService.HistoryEntry entry : detail.history()) {
                sb.append("- ").append(TIMESTAMP.format(entry.at())).append(" · ")
                        .append(entry.type().name().toLowerCase(Locale.ROOT))
                        .append(" by ").append(entry.actor());
                if (entry.relatedReference() != null) {
                    sb.append(" (").append(entry.relatedReference()).append(')');
                }
                sb.append('\n');
            }
        }
        sb.append("\nReply `").append(mention).append(" feedback list")
                .append("` to see the current assessments.");
        return sb.toString();
    }

    private static String link(String text, String url) {
        return url == null ? text : "[" + text + "](" + url + ")";
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() <= 200 ? line : line.substring(0, 200) + "…";
    }
}
