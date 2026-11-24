package org.remus.giteabot.learning.knowledge;

import org.remus.giteabot.learning.feedback.FeedbackCommandParser;
import org.remus.giteabot.learning.feedback.FeedbackReferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code @bot learning …} commands with an anchored pattern, like feedback commands: quoted
 * lines and fenced blocks are ignored, the first matching line is the command, and everything after
 * the references is the lesson text or reason.
 *
 * <p>A {@code paths:} line directly before the lesson text restricts its scope:</p>
 * <pre>
 * &#64;bot learning propose FB31
 * paths: src/main/java/example/settings/**
 * Before reporting a missing permission check, inspect the enclosing security filter.
 * </pre>
 */
public final class LearningCommandParser {

    static final Set<String> VERBS =
            Set.of("propose", "revise", "activate", "reject", "disable", "show", "list");

    private static final Pattern MENTION = Pattern.compile("^\\s*@(\\S+)\\s+learning\\b(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PATHS = Pattern.compile("^\\s*paths\\s*:(.*)$",
            Pattern.CASE_INSENSITIVE);

    private static final String USAGE = "Use `learning list`, `learning show L17`, "
            + "`learning propose FB31 <lesson>`, `learning revise L17 <lesson>`, "
            + "`learning activate L17@1`, `learning reject L17@1` or `learning disable L17`.";

    private LearningCommandParser() {
    }

    /**
     * @param mentionAliases every mention the command may use (see
     *                       {@link FeedbackCommandParser#parse(String, String...)})
     * @return the command, or empty when the comment carries no {@code learning} command for one of
     *         these mentions — the comment then keeps its ordinary routing
     */
    public static Optional<LearningCommand> parse(String body, String... mentionAliases) {
        Matched matched = findCommand(body, mentionAliases);
        return matched == null ? Optional.empty()
                : Optional.of(interpret(matched.tokens(), matched.following()));
    }

    /**
     * Whether the comment is unmistakably a lesson command — a known subcommand follows the word.
     * Prose such as "{@code @bot learning from this, could you …}" is not, and on a bot without
     * lesson management keeps whatever routing it had before these commands existed.
     */
    public static boolean addressesLearning(String body, String... mentionAliases) {
        Matched matched = findCommand(body, mentionAliases);
        if (matched == null || matched.tokens().isEmpty()) {
            return false;
        }
        return VERBS.contains(matched.tokens().split("\\s+")[0].toLowerCase(Locale.ROOT));
    }

    private record Matched(String tokens, String following) {
    }

    private static Matched findCommand(String body, String... mentionAliases) {
        if (body == null || body.isBlank()) {
            return null;
        }
        List<String> mentions = mentions(mentionAliases);
        if (mentions.isEmpty()) {
            return null;
        }
        String[] lines = FeedbackCommandParser.stripQuotedAndFenced(body).split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = MENTION.matcher(lines[i]);
            if (!matcher.matches() || mentions.stream()
                    .noneMatch(mention -> mention.equalsIgnoreCase(matcher.group(1)))) {
                continue;
            }
            String rest = matcher.group(2) == null ? "" : matcher.group(2).trim();
            StringBuilder following = new StringBuilder();
            for (int j = i + 1; j < lines.length; j++) {
                if (j > i + 1) {
                    following.append('\n');
                }
                following.append(lines[j]);
            }
            return new Matched(rest, following.toString());
        }
        return null;
    }

    private static List<String> mentions(String... mentionAliases) {
        List<String> mentions = new ArrayList<>();
        if (mentionAliases == null) {
            return mentions;
        }
        for (String alias : mentionAliases) {
            if (alias != null && !alias.isBlank()) {
                mentions.add(alias.startsWith("@") ? alias.substring(1) : alias);
            }
        }
        return mentions;
    }

    private static LearningCommand interpret(String tokens, String following) {
        String[] parts = tokens.isEmpty() ? new String[0] : tokens.split("\\s+");
        if (parts.length == 0) {
            return new LearningCommand.Help(USAGE);
        }
        String verb = parts[0].toLowerCase(Locale.ROOT);
        try {
            return switch (verb) {
                case "propose" -> propose(parts, tokens, following);
                case "revise" -> revise(parts, tokens, following);
                case "activate" -> revisionCommand(parts, true);
                case "reject" -> revisionCommand(parts, false);
                case "disable" -> disable(parts, tokens, following);
                case "show" -> show(parts);
                case "list" -> list(parts);
                default -> new LearningCommand.Help("`" + parts[0] + "` is not a lesson command. "
                        + USAGE);
            };
        } catch (LessonRejectedException invalid) {
            return new LearningCommand.Help(invalid.getMessage());
        }
    }

    private static LearningCommand propose(String[] parts, String tokens, String following) {
        if (parts.length < 2 || FeedbackReferences.parse(parts[1]) == null) {
            return new LearningCommand.Help("Name the feedback the lesson comes from: "
                    + "`learning propose FB31 <lesson>`.");
        }
        Body body = body(remainder(tokens, parts[1]), following);
        if (body.text().isEmpty()) {
            return new LearningCommand.Help("Write the lesson after the reference: "
                    + "`learning propose " + FeedbackReferences.parse(parts[1]) + " <lesson>`.");
        }
        return new LearningCommand.Propose(FeedbackReferences.parse(parts[1]), body.paths(),
                body.text());
    }

    private static LearningCommand revise(String[] parts, String tokens, String following) {
        Optional<LessonReference> reference = parts.length < 2
                ? Optional.empty() : LessonReference.parse(parts[1]);
        if (reference.isEmpty() || reference.get().revision() != null) {
            return new LearningCommand.Help("Name the lesson to revise, for example "
                    + "`learning revise L17 <new wording>`; the new revision is numbered for you.");
        }
        Body body = body(remainder(tokens, parts[1]), following);
        if (body.text().isEmpty() && body.paths() == null) {
            return new LearningCommand.Help("Give new wording, a `paths:` line, or both: "
                    + "`learning revise " + reference.get().lesson() + " <new wording>`.");
        }
        return new LearningCommand.Revise(reference.get().lesson(), body.paths(),
                body.text().isEmpty() ? null : body.text());
    }

    private static LearningCommand revisionCommand(String[] parts, boolean activate) {
        String verb = activate ? "activate" : "reject";
        Optional<LessonReference> reference = parts.length == 2
                ? LessonReference.parse(parts[1]) : Optional.empty();
        if (reference.isEmpty() || reference.get().revision() == null) {
            return new LearningCommand.Help("Name the exact revision, for example `learning " + verb
                    + " L17@2`, so text that changed since you read it cannot be " + verb + "d by "
                    + "accident. `learning show L17` lists the revisions.");
        }
        return activate
                ? new LearningCommand.Activate(reference.get().lesson(), reference.get().revision())
                : new LearningCommand.Reject(reference.get().lesson(), reference.get().revision());
    }

    private static LearningCommand disable(String[] parts, String tokens, String following) {
        Optional<LessonReference> reference = parts.length < 2
                ? Optional.empty() : LessonReference.parse(parts[1]);
        if (reference.isEmpty() || reference.get().revision() != null) {
            return new LearningCommand.Help("Use `learning disable L17` with the lesson reference "
                    + "(no revision); a reason may follow.");
        }
        String reason = join(remainder(tokens, parts[1]), following);
        return new LearningCommand.Disable(reference.get().lesson(), reason.isEmpty() ? null : reason);
    }

    private static LearningCommand show(String[] parts) {
        Optional<LessonReference> reference = parts.length == 2
                ? LessonReference.parse(parts[1]) : Optional.empty();
        return reference.<LearningCommand>map(r -> new LearningCommand.Show(r.lesson()))
                .orElseGet(() -> new LearningCommand.Help("Use `learning show L17` with one lesson "
                        + "reference."));
    }

    private static LearningCommand list(String[] parts) {
        LessonState state = null;
        int index = 1;
        if (parts.length > 1 && !"page:".equalsIgnoreCase(parts[1])) {
            state = stateOf(parts[1]);
            if (state == null) {
                return new LearningCommand.Help("`" + parts[1] + "` is not a lesson state. Use "
                        + "`learning list`, `learning list active`, `learning list candidate`, "
                        + "`learning list disabled`, `learning list stale` or "
                        + "`learning list rejected`.");
            }
            index = 2;
        }
        String cursor = null;
        if (parts.length == index + 2 && "page:".equalsIgnoreCase(parts[index])) {
            cursor = parts[index + 1];
        } else if (parts.length != index) {
            return new LearningCommand.Help("Use `learning list [state]`, optionally followed by "
                    + "`page: <cursor>`.");
        }
        return new LearningCommand.ListLessons(state, cursor);
    }

    private static LessonState stateOf(String token) {
        String normalized = token.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("s")) {
            // "candidates" reads as naturally as "candidate"
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        for (LessonState state : LessonState.values()) {
            if (state.label().equals(normalized)) {
                return state;
            }
        }
        return null;
    }

    /** Lesson text with an optional leading {@code paths:} line; {@code paths} is {@code null} without one. */
    private record Body(List<String> paths, String text) {
    }

    private static Body body(String inline, String following) {
        String all = join(inline, following);
        String[] lines = all.split("\\R", -1);
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) {
            first++;
        }
        if (first < lines.length) {
            Matcher matcher = PATHS.matcher(lines[first]);
            if (matcher.matches()) {
                List<String> paths = LessonPathScope.parse(matcher.group(1));
                StringBuilder rest = new StringBuilder();
                for (int i = first + 1; i < lines.length; i++) {
                    if (!rest.isEmpty()) {
                        rest.append('\n');
                    }
                    rest.append(lines[i]);
                }
                return new Body(paths, rest.toString().trim());
            }
        }
        return new Body(null, all);
    }

    /** What follows {@code reference} on the command line. */
    private static String remainder(String tokens, String reference) {
        int at = tokens.indexOf(reference);
        return at < 0 ? "" : tokens.substring(at + reference.length()).trim();
    }

    private static String join(String inline, String following) {
        String below = following == null ? "" : following.trim();
        if (inline.isEmpty()) {
            return below;
        }
        return below.isEmpty() ? inline : inline + "\n" + below;
    }
}
