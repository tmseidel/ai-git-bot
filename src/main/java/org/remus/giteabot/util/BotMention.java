package org.remus.giteabot.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches a bot alias such as {@code @ai_bot} as a whole mention in comment text.
 *
 * <p>The alias must not be preceded by a word character and not be followed by a
 * character that could continue a username, so longer names ({@code @ai_bot2},
 * {@code @ai_bot.helper}) and e-mail addresses ({@code me@ai_bot.com}) do not match.
 * A trailing {@code .} only ends the mention when no further name character follows,
 * so {@code "thanks @ai_bot."} still counts.
 *
 * <p>Matching is case-sensitive. A blank alias never matches.
 */
public final class BotMention {

    private BotMention() {
    }

    public static boolean isMentioned(String text, String alias) {
        if (text == null || alias == null || alias.isBlank()) {
            return false;
        }
        return pattern(alias).matcher(text).find();
    }

    /**
     * Replaces every whole mention of {@code alias} in {@code text} with {@code replacement}.
     */
    public static String replaceMention(String text, String alias, String replacement) {
        if (text == null || alias == null || alias.isBlank()) {
            return text;
        }
        return pattern(alias).matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
    }

    private static Pattern pattern(String alias) {
        return Pattern.compile("(?<!\\w)" + Pattern.quote(alias) + "(?![\\w-]|\\.[\\w-])");
    }
}
