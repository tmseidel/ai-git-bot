package org.remus.giteabot.learning.feedback;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code @bot feedback …} commands with an anchored pattern, separate from the free-text
 * explanation that follows them.
 *
 * <p>Quoted lines and fenced code blocks are blanked before parsing: people paste reviews and
 * examples, and a documented command inside a quote is not an instruction. The command itself is
 * only the first line; every following line is the reason, kept verbatim (minus surrounding
 * whitespace) and bounded.</p>
 */
public final class FeedbackCommandParser {

    /** Canonical verdict/reason length budget; the column stores exactly this. */
    public static final int MAX_REASON_CHARS = 4_000;

    private static final Pattern MENTION = Pattern.compile("^\\s*@(\\S+)\\s+feedback\\b(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE = Pattern.compile("(?s)^\\s*(```|~~~).*?^\\s*\\1.*$",
            Pattern.MULTILINE);

    private FeedbackCommandParser() {
    }

    /**
     * @param mentionAliases every mention the command may use — a provider can rewrite the mention it
     *                       received (Bitbucket turns {@code @{account_id}} into a readable name), so
     *                       the effective alias and the configured username are both acceptable
     * @return the command, or empty when the comment carries no {@code feedback} command for one of
     *         these mentions — in that case the comment must keep its ordinary routing.
     */
    public static Optional<FeedbackCommand> parse(String body, String... mentionAliases) {
        if (body == null || body.isBlank() || mentionAliases == null || mentionAliases.length == 0) {
            return Optional.empty();
        }
        List<String> mentions = mentions(mentionAliases);
        if (mentions.isEmpty()) {
            return Optional.empty();
        }
        Matched matched = findCommand(body, mentions);
        return matched == null ? Optional.empty()
                : Optional.of(interpret(matched.tokens(), matched.reason()));
    }

    /**
     * Whether the comment is unmistakably addressed to feedback — a target ({@code O1234-F2}) or a
     * subcommand ({@code retract}, {@code list}, {@code show}) follows the word. Prose such as
     * "{@code @bot feedback please, is this approach OK?}" is not: on a bot that collects no
     * feedback it must keep whatever routing it had before feedback commands existed.
     */
    public static boolean addressesFeedback(String body, String... mentionAliases) {
        List<String> mentions = mentions(mentionAliases);
        if (mentions.isEmpty()) {
            return false;
        }
        Matched matched = findCommand(body, mentions);
        if (matched == null || matched.tokens().isEmpty()) {
            return false;
        }
        String first = matched.tokens().split("\\s+")[0];
        String verb = first.toLowerCase(Locale.ROOT);
        return verb.equals("retract") || verb.equals("list") || verb.equals("show")
                || FeedbackTarget.parse(first).isPresent();
    }

    /** The command text and its reason, or {@code null} when the body carries no feedback command. */
    private static Matched findCommand(String body, List<String> mentions) {
        String[] lines = stripQuotedAndFenced(body).split("\\R", -1);

        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = MENTION.matcher(lines[i]);
            if (!matcher.matches() || mentions.stream()
                    .noneMatch(mention -> mention.equalsIgnoreCase(matcher.group(1)))) {
                continue;
            }
            String rest = matcher.group(2) == null ? "" : matcher.group(2).trim();
            return new Matched(rest, collectReason(lines, i + 1));
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

    private record Matched(String tokens, String reason) {
    }

    private static FeedbackCommand interpret(String tokens, String following) {
        String[] parts = tokens.isEmpty() ? new String[0] : tokens.split("\\s+");
        if (parts.length == 0) {
            return new FeedbackCommand.Help("Tell me what to record, for example "
                    + "`feedback O1234-F2 incorrect <why>`.");
        }
        String verb = parts[0].toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "retract" -> retract(parts);
            case "list" -> list(parts);
            case "show" -> show(parts);
            default -> submit(parts, tokens, following);
        };
    }

    private static FeedbackCommand retract(String[] parts) {
        if (parts.length != 2) {
            return new FeedbackCommand.Help("Use `feedback retract FB31` with one reference.");
        }
        String reference = FeedbackReferences.parse(parts[1]);
        return reference == null
                ? new FeedbackCommand.Help("`" + parts[1] + "` is not a feedback reference "
                        + "(they look like FB31).")
                : new FeedbackCommand.Retract(reference);
    }

    private static FeedbackCommand list(String[] parts) {
        boolean repositoryWide = false;
        int index = 1;
        if (parts.length > 1 && "repository".equalsIgnoreCase(parts[1])) {
            repositoryWide = true;
            index = 2;
        }
        Long cursor = null;
        if (parts.length == index + 2 && "page:".equalsIgnoreCase(parts[index])) {
            try {
                cursor = Long.parseLong(parts[index + 1]);
            } catch (NumberFormatException e) {
                return new FeedbackCommand.Help("`" + parts[index + 1]
                        + "` is not a page cursor.");
            }
        } else if (parts.length != index) {
            return new FeedbackCommand.Help("Use `feedback list`, `feedback list repository`, or "
                    + "`feedback list page: <cursor>`.");
        }
        return new FeedbackCommand.ListRecords(repositoryWide, cursor);
    }

    private static FeedbackCommand show(String[] parts) {
        if (parts.length != 2) {
            return new FeedbackCommand.Help("Use `feedback show FB31` with one reference.");
        }
        String reference = FeedbackReferences.parse(parts[1]);
        return reference == null
                ? new FeedbackCommand.Help("`" + parts[1] + "` is not a feedback reference "
                        + "(they look like FB31).")
                : new FeedbackCommand.Show(reference);
    }

    private static FeedbackCommand submit(String[] parts, String tokens, String following) {
        Optional<FeedbackTarget> target = FeedbackTarget.parse(parts[0]);
        if (target.isEmpty()) {
            return new FeedbackCommand.Help("`" + parts[0] + "` is not a review target. Use "
                    + "`O1234`, `O1234-F2`, or `O1234-A`.");
        }
        if (parts.length < 2) {
            return new FeedbackCommand.Help("Add a verdict: " + FeedbackVerdict.tokens() + ".");
        }
        Optional<FeedbackVerdict> verdict = FeedbackVerdict.parse(parts[1]);
        if (verdict.isEmpty()) {
            return new FeedbackCommand.Help("`" + parts[1] + "` is not a verdict. Use one of: "
                    + FeedbackVerdict.tokens() + ".");
        }
        String inline = tokens.substring(tokens.indexOf(parts[1]) + parts[1].length()).trim();
        String reason = (inline + (inline.isEmpty() || following.isEmpty() ? "" : "\n") + following)
                .trim();
        if (reason.length() > MAX_REASON_CHARS) {
            reason = reason.substring(0, MAX_REASON_CHARS);
        }
        return new FeedbackCommand.Submit(target.get(), verdict.get(), reason);
    }

    private static String collectReason(String[] lines, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString().trim();
    }

    /** Blanks fenced blocks and block-quoted lines so documented examples stay examples. */
    static String stripQuotedAndFenced(String body) {
        String withoutFences = FENCE.matcher(body).replaceAll(match -> "\n".repeat(
                (int) match.group().chars().filter(c -> c == '\n').count() + 1));
        StringBuilder sb = new StringBuilder(withoutFences.length());
        for (String line : withoutFences.split("\\R", -1)) {
            sb.append(line.stripLeading().startsWith(">") ? "" : line).append('\n');
        }
        return sb.toString();
    }
}
