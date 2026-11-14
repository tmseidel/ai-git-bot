package org.remus.giteabot.learning.feedback;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders the bot's answers to feedback commands. Everything here is a deterministic function of
 * persisted records — no model is involved, so the same command always produces the same text.
 */
public final class FeedbackReplyRenderer {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private FeedbackReplyRenderer() {
    }

    /** Footer explaining the vocabulary; posted with every acknowledgement. */
    public static String footer() {
        return """
                Reply with `feedback <target> <verdict> <why>` to judge a review, for example
                `feedback O1234-F2 incorrect The permission check is already in the filter`.
                Verdicts: %s. Targets: `O1234` (whole review), `O1234-F2` (finding),
                `O1234-A` (assessment).

                Feedback only records your assessment — it never re-runs a review, changes code, or
                edits instructions. A later comment from you replaces your earlier assessment of the
                same target; `feedback retract <FB…>` withdraws one.
                """.formatted(FeedbackVerdict.tokens());
    }

    public static String acknowledged(FeedbackService.Recorded recorded) {
        String verb = recorded.duplicate() ? "Already recorded" : "Recorded";
        return """
                %s **%s** — `%s` is **%s** (by %s).

                %s""".formatted(verb, recorded.reference(), recorded.targetReference(),
                recorded.verdict().token(), recorded.actor(), footer());
    }

    public static String retracted(FeedbackService.Recorded recorded) {
        String verb = recorded.duplicate() ? "Already retracted" : "Retracted";
        return "%s **%s** — the assessment of `%s` no longer counts.\n\n%s"
                .formatted(verb, recorded.reference(), recorded.targetReference(), footer());
    }

    /** A recognized command that cannot be carried out: explain and stop. */
    public static String rejected(FeedbackRejectedException rejection) {
        return "%s\n\n%s".formatted(rejection.getMessage(), footer());
    }

    public static String help(String message) {
        return "%s\n\n%s".formatted(message, footer());
    }

    public static String list(boolean repositoryWide, List<FeedbackService.Summary> rows,
                              Long lastId, String mention) {
        String scope = repositoryWide ? "in this repository" : "on this pull request";
        if (rows.isEmpty()) {
            return "No feedback %s yet.\n\n%s".formatted(scope, footer());
        }
        StringBuilder sb = new StringBuilder("Feedback ").append(scope).append(":\n\n");
        for (FeedbackService.Summary row : rows) {
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
                sb.append(" · PR #").append(row.prNumber());
            }
            sb.append(" · ").append(TIMESTAMP.format(row.createdAt())).append('\n');
            if (row.reason() != null && !row.reason().isBlank()) {
                sb.append("  > ").append(firstLine(row.reason())).append('\n');
            }
        }
        if (lastId != null && lastId > 0) {
            sb.append("\nNext page: `").append(mention).append(" feedback list")
                    .append(repositoryWide ? " repository" : "")
                    .append(" page: ").append(lastId).append("`\n");
        }
        return sb.toString().trim();
    }

    public static String show(FeedbackService.Summary row, String mention) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(row.reference()).append("** — `").append(row.targetReference())
                .append("` is **").append(row.verdict().token()).append("** (by ")
                .append(row.actor()).append(", ").append(row.state().name().toLowerCase(Locale.ROOT))
                .append(")\n\n");
        sb.append("Review `").append(row.outputReference()).append("` on PR #")
                .append(row.prNumber()).append(" in ").append(row.repoOwner()).append('/')
                .append(row.repoName()).append(" · ").append(TIMESTAMP.format(row.createdAt()))
                .append('\n');
        if (row.supersededBy() != null) {
            sb.append("Replaced by ").append(row.supersededBy()).append('\n');
        }
        if (row.reason() != null && !row.reason().isBlank()) {
            sb.append("\n> ").append(row.reason().replace("\n", "\n> ")).append('\n');
        }
        sb.append("\nReply `").append(mention).append(" feedback list")
                .append("` to see the current assessments.");
        return sb.toString();
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() <= 200 ? line : line.substring(0, 200) + "…";
    }
}
