package org.remus.giteabot.learning.knowledge;

import org.remus.giteabot.learning.feedback.FeedbackLinks;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the bot's answers to lesson commands — a deterministic function of persisted records, so
 * the same question always gets the same answer and no model is involved.
 *
 * <p>Every answer that could lead to an activation shows the exact text, the revision, the scope —
 * spelling out a repository-wide effect — the evidence and its validation state, so a maintainer
 * approves what they read and nothing else. Overviews name their bot: several bots may share one
 * Git account and each lists only its own lessons.</p>
 */
public final class LessonReplyRenderer {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private static final List<LessonState> GROUP_ORDER = List.of(LessonState.ACTIVE,
            LessonState.CANDIDATE, LessonState.STALE, LessonState.DISABLED, LessonState.REJECTED,
            LessonState.SUPERSEDED);

    private LessonReplyRenderer() {
    }

    /** Vocabulary reminder appended to explanations and refusals. */
    public static String footer(String mention) {
        String bot = mention == null ? "@bot" : mention;
        return """
                Lesson commands: `%1$s learning list [active]`, `%1$s learning show L17`, \
                `%1$s learning propose FB31 <lesson>`, `%1$s learning revise L17 <lesson>`, \
                `%1$s learning activate L17@1`, `%1$s learning reject L17@1`, \
                `%1$s learning disable L17`. A `paths: src/**` line before the lesson text limits it \
                to matching files. Only learning maintainers can change lessons; nothing is activated \
                without one naming the exact revision.""".formatted(bot);
    }

    public static String help(String message, String mention) {
        return message + "\n\n" + footer(mention);
    }

    public static String rejected(LessonRejectedException rejection, String mention) {
        return rejection.getMessage() + "\n\n" + footer(mention);
    }

    /** Answer to {@code propose} and {@code revise}: the candidate exactly as it would be activated. */
    public static String candidate(KnowledgeLessonService.Detail detail, int revision, String botName,
                                   String mention, FeedbackLinks links) {
        KnowledgeLessonService.Summary summary = detail.summary();
        KnowledgeLessonService.RevisionView candidate = revisionOf(detail, revision);
        String named = LessonReference.revision(summary.reference(), revision);
        StringBuilder sb = new StringBuilder();
        sb.append("Candidate **").append(named).append("** for bot **").append(botName)
                .append("** — not used by any review until it is activated.\n\n");
        quote(sb, candidate.text());
        sb.append('\n');
        scope(sb, summary, candidate);
        if (candidate.repositoryWide() && !detail.suggestedPaths().isEmpty()) {
            sb.append("Suggested narrower scope (from the finding's location): `paths: ")
                    .append(String.join(", ", detail.suggestedPaths())).append("`\n");
        }
        evidence(sb, candidate, links);
        source(sb, candidate);
        validation(sb, summary.validation());
        if (summary.activeRevision() != null && summary.activeRevision() != revision) {
            sb.append("Until then, ")
                    .append(LessonReference.revision(summary.reference(), summary.activeRevision()))
                    .append(" stays active.\n");
        }
        sb.append("\nActivate with `").append(mention).append(" learning activate ").append(named)
                .append("`, or turn it down with `").append(mention).append(" learning reject ")
                .append(named).append("`.");
        return sb.toString();
    }

    public static String activated(KnowledgeLessonService.Detail detail, String mention) {
        KnowledgeLessonService.Summary summary = detail.summary();
        KnowledgeLessonService.RevisionView active = summary.current();
        StringBuilder sb = new StringBuilder();
        sb.append("Lesson **").append(summary.currentReference()).append("** activated by ")
                .append(active.activatedBy()).append(" as maintainer-curated guidance.\n\n");
        quote(sb, active.text());
        sb.append('\n');
        scope(sb, summary, active);
        sb.append("\nLater relevant agentic reviews in this scope may receive it. This records a "
                        + "maintainer's judgement, not a measured improvement. Disable it with `")
                .append(mention).append(" learning disable ").append(summary.reference())
                .append("`.");
        return sb.toString();
    }

    public static String disabled(KnowledgeLessonService.Detail detail, int revision,
                                  String mention) {
        String reference = detail.summary().reference();
        return "Lesson **" + reference + "** disabled (" + LessonReference.revision(reference,
                revision) + ") — later agentic reviews no longer receive it. Its text, evidence and "
                + "history are kept: `" + mention + " learning show " + reference + "`.";
    }

    public static String rejectedCandidate(KnowledgeLessonService.Detail detail, int revision,
                                           String mention) {
        String reference = detail.summary().reference();
        return "Candidate **" + LessonReference.revision(reference, revision) + "** rejected. "
                + "Write a new candidate with `" + mention + " learning revise " + reference
                + " <lesson>`.";
    }

    public static String list(LessonState filter, KnowledgeLessonService.ListPage page,
                              String botName, String repository, String mention,
                              FeedbackLinks links) {
        String header = "Lessons for bot **" + botName + "** in " + repository
                + (filter == null ? "" : " · " + filter.label() + " only");
        if (page.rows().isEmpty()) {
            return header + ": none.\n\n" + footer(mention);
        }
        Map<LessonState, List<KnowledgeLessonService.Summary>> groups = new EnumMap<>(LessonState.class);
        for (KnowledgeLessonService.Summary row : page.rows()) {
            groups.computeIfAbsent(row.state(), state -> new ArrayList<>()).add(row);
        }
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (LessonState state : GROUP_ORDER) {
            List<KnowledgeLessonService.Summary> rows = groups.get(state);
            if (rows == null) {
                continue;
            }
            sb.append("\n**").append(capitalize(state.label())).append("**\n");
            for (KnowledgeLessonService.Summary row : rows) {
                KnowledgeLessonService.RevisionView current = row.current();
                sb.append("- **").append(row.currentReference()).append("** · ")
                        .append(current.repositoryWide() ? "repository-wide"
                                : "`" + String.join("`, `", current.paths()) + "`")
                        .append(" · used in ").append(row.usageCount())
                        .append(row.usageCount() == 1 ? " review" : " reviews");
                if (row.pendingRevision() != null) {
                    sb.append(" · candidate ").append(LessonReference.revision(row.reference(),
                            row.pendingRevision())).append(" waiting");
                }
                sb.append('\n');
                sb.append("  ").append(firstLine(current.text())).append('\n');
                List<String> cited = new ArrayList<>();
                for (KnowledgeLessonService.EvidenceView evidence : current.evidence()) {
                    cited.add(evidenceLabel(evidence, links));
                }
                if (!cited.isEmpty()) {
                    sb.append("  Evidence: ").append(String.join(", ", cited)).append('\n');
                }
                if (!row.validation().valid()) {
                    sb.append("  ⚠ ").append(String.join("; ", issues(row.validation())))
                            .append('\n');
                }
            }
        }
        if (page.nextCursor() != null) {
            sb.append("\nNext page: `").append(mention).append(" learning list")
                    .append(filter == null ? "" : " " + filter.label())
                    .append(" page: ").append(page.nextCursor()).append("`\n");
        }
        sb.append("\nInspect one with `").append(mention).append(" learning show L…`. Usage counts "
                + "say how often a lesson was supplied to a review, not whether it helped.");
        return sb.toString().trim();
    }

    public static String show(KnowledgeLessonService.Detail detail, String botName, String mention,
                              FeedbackLinks links) {
        KnowledgeLessonService.Summary summary = detail.summary();
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(summary.reference()).append("** · ").append(summary.state().label())
                .append(" · bot **").append(botName).append("** · ").append(summary.repoOwner())
                .append('/').append(summary.repoName()).append(" · agentic-review\n");
        if (summary.activeRevision() != null) {
            sb.append("Active revision: ").append(LessonReference.revision(summary.reference(),
                    summary.activeRevision())).append('\n');
        }
        if (summary.pendingRevision() != null) {
            sb.append("Waiting candidate: ").append(LessonReference.revision(summary.reference(),
                    summary.pendingRevision())).append('\n');
        }
        sb.append("Used in ").append(summary.usageCount())
                .append(summary.usageCount() == 1 ? " review" : " reviews")
                .append(" (adoption, not proof of improvement)\n");
        validation(sb, summary.validation());
        if (summary.current().repositoryWide() && !detail.suggestedPaths().isEmpty()) {
            sb.append("Suggested narrower scope: `paths: ")
                    .append(String.join(", ", detail.suggestedPaths())).append("`\n");
        }
        sb.append("\n**Revisions**\n");
        for (KnowledgeLessonService.RevisionView revision : detail.revisions().reversed()) {
            sb.append("\n- **").append(LessonReference.revision(summary.reference(),
                    revision.number())).append("** · ").append(revision.state().label())
                    .append(" · ").append(revision.repositoryWide() ? "repository-wide"
                            : "`" + String.join("`, `", revision.paths()) + "`")
                    .append(" · written by ").append(revision.createdBy()).append(", ")
                    .append(TIMESTAMP.format(revision.createdAt()));
            if (revision.activationProvenance() != null) {
                sb.append(" · activated by ").append(revision.activatedBy()).append(" (")
                        .append(revision.activationProvenance().name().toLowerCase(java.util.Locale.ROOT)
                                .replace('_', '-')).append(')');
            }
            sb.append('\n');
            sb.append("  > ").append(revision.text().replace("\n", "\n  > ")).append('\n');
            List<String> cited = new ArrayList<>();
            for (KnowledgeLessonService.EvidenceView evidence : revision.evidence()) {
                cited.add(evidenceLabel(evidence, links));
            }
            if (!cited.isEmpty()) {
                sb.append("  Evidence: ").append(String.join(", ", cited)).append('\n');
            }
        }
        if (!detail.history().isEmpty()) {
            sb.append("\n**History**\n");
            for (KnowledgeLessonService.HistoryEntry entry : detail.history()) {
                sb.append("- ").append(TIMESTAMP.format(entry.at())).append(" · ")
                        .append(entry.type().label());
                if (entry.revision() != null) {
                    sb.append(' ').append(LessonReference.revision(summary.reference(),
                            entry.revision()));
                }
                sb.append(" by ").append(entry.actor());
                if (entry.detail() != null && !entry.detail().isBlank()) {
                    sb.append(" — ").append(firstLine(entry.detail()));
                }
                sb.append('\n');
            }
        }
        sb.append("\nList all lessons with `").append(mention).append(" learning list`.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ parts

    private static void scope(StringBuilder sb, KnowledgeLessonService.Summary summary,
                              KnowledgeLessonService.RevisionView revision) {
        sb.append("Scope: agentic reviews of ").append(summary.repoOwner()).append('/')
                .append(summary.repoName()).append(" by this bot");
        if (revision.repositoryWide()) {
            sb.append(" — **every path of the repository** (no `paths:` restriction)\n");
        } else {
            sb.append(", only for changes matching `").append(String.join("`, `", revision.paths()))
                    .append("`\n");
        }
    }

    private static void evidence(StringBuilder sb, KnowledgeLessonService.RevisionView revision,
                                 FeedbackLinks links) {
        List<String> cited = new ArrayList<>();
        for (KnowledgeLessonService.EvidenceView evidence : revision.evidence()) {
            String verdict = evidence.verdict() == null ? "" : " " + evidence.verdict().token();
            cited.add(evidenceLabel(evidence, links) + " — `" + evidence.targetReference() + "`"
                    + verdict + (evidence.actor() == null ? "" : " by " + evidence.actor()));
        }
        sb.append("Evidence: ").append(cited.isEmpty() ? "none" : String.join("; ", cited))
                .append('\n');
    }

    private static void source(StringBuilder sb, KnowledgeLessonService.RevisionView revision) {
        List<String> parts = new ArrayList<>();
        if (revision.category() != null) {
            parts.add("category `" + revision.category() + "`");
        }
        if (revision.sourceCommitSha() != null) {
            parts.add("written against commit `" + shortSha(revision.sourceCommitSha()) + "`");
        }
        if (revision.recheckAfter() != null) {
            parts.add("recheck after " + DAY.format(revision.recheckAfter()));
        }
        if (!parts.isEmpty()) {
            sb.append("Source: ").append(String.join(" · ", parts)).append('\n');
        }
    }

    private static void validation(StringBuilder sb, KnowledgeLessonService.Validation validation) {
        if (validation.valid()) {
            sb.append("Validation: no open issues\n");
            return;
        }
        sb.append("Validation: ⚠ ").append(String.join("; ", issues(validation))).append('\n');
    }

    private static List<String> issues(KnowledgeLessonService.Validation validation) {
        List<String> all = new ArrayList<>(validation.issues());
        for (String conflict : validation.conflicts()) {
            all.add("possible conflict: " + conflict);
        }
        return all;
    }

    private static String evidenceLabel(KnowledgeLessonService.EvidenceView evidence,
                                        FeedbackLinks links) {
        String reference = link(evidence.feedbackReference(), evidence.prNumber() == null ? null
                : links.comment(evidence.prNumber(), evidence.commentKind(), evidence.commentId()));
        StringBuilder sb = new StringBuilder(reference);
        if (evidence.prNumber() != null) {
            sb.append(" (").append(link("PR #" + evidence.prNumber(),
                    links.pullRequest(evidence.prNumber()))).append(')');
        }
        if (evidence.state() == null) {
            sb.append(" _(deleted)_");
        } else if (!evidence.effective()) {
            sb.append(" _(").append(evidence.state().name().toLowerCase(java.util.Locale.ROOT));
            if (evidence.supersededBy() != null) {
                sb.append(" by ").append(evidence.supersededBy());
            }
            sb.append(")_");
        }
        return sb.toString();
    }

    private static KnowledgeLessonService.RevisionView revisionOf(KnowledgeLessonService.Detail detail,
                                                                  int revision) {
        return detail.revisions().stream().filter(view -> view.number() == revision).findFirst()
                .orElse(detail.summary().current());
    }

    private static void quote(StringBuilder sb, String text) {
        sb.append("> ").append(text.replace("\n", "\n> ")).append('\n');
    }

    private static String link(String text, String url) {
        return url == null ? text : "[" + text + "](" + url + ")";
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() <= 200 ? line : line.substring(0, 200) + "…";
    }

    private static String shortSha(String sha) {
        return sha.length() <= 12 ? sha : sha.substring(0, 12);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
