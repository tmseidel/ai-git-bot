package org.remus.giteabot.prworkflow.agentreview;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cross-field checks of the agentic-review learning preferences.
 *
 * <p>Ordinary field types stay with the schema infrastructure; this covers what a single field
 * cannot express — which learning modes ship, and what a learning-maintainer account may look like.
 * The same checks guard admin saves, configuration imports and runtime resolution, so an import or a
 * hand-edited row cannot reach a state the admin form would refuse.</p>
 */
public final class AgentReviewLearningSettingsValidator {

    /** Upper bound on configured maintainer accounts; the list is an allow-list, not a directory. */
    public static final int MAX_MAINTAINERS = 100;

    private static final int MAX_ACCOUNT_CHARS = 255;

    /**
     * One account as the Git providers spell it: no whitespace and no markup characters. Provider
     * rules differ (GitLab allows dots, Azure DevOps uses e-mail style names), so this only refuses
     * what no provider produces rather than imitating one provider's syntax.
     */
    private static final Pattern ACCOUNT = Pattern.compile("[^\\s<>\"'`,;()\\[\\]{}]+");

    private static final Pattern SEPARATORS = Pattern.compile("[,\\r\\n]+");

    private AgentReviewLearningSettingsValidator() {
    }

    /** Field-specific messages; empty when the preferences are consistent. */
    public static List<String> validate(Map<String, ?> params) {
        List<String> errors = new ArrayList<>();
        if (params == null) {
            return errors;
        }
        Object mode = params.get(AgentReviewParam.LEARNING_MODE.key());
        if (mode != null && !mode.toString().isBlank()
                && AgentReviewLearningMode.parse(mode).isEmpty()) {
            errors.add("Parameter '" + AgentReviewParam.LEARNING_MODE.key() + "' must be one of: "
                    + String.join(", ", modeNames()));
        }
        Object maintainers = params.get(AgentReviewParam.LEARNING_MAINTAINERS.key());
        List<String> accounts = tokens(maintainers == null ? null : maintainers.toString());
        for (String account : accounts) {
            if (account.length() > MAX_ACCOUNT_CHARS || !ACCOUNT.matcher(account).matches()) {
                errors.add("Parameter '" + AgentReviewParam.LEARNING_MAINTAINERS.key()
                        + "' contains '" + account + "', which is not a single Git account name "
                        + "(one account per line)");
            }
        }
        if (accounts.size() > MAX_MAINTAINERS) {
            errors.add("Parameter '" + AgentReviewParam.LEARNING_MAINTAINERS.key()
                    + "' may list at most " + MAX_MAINTAINERS + " accounts");
        }
        return errors;
    }

    /**
     * The configured maintainers, normalized the way commenter names are compared: trimmed,
     * without a leading {@code @}, lower case. Call only on validated input.
     */
    public static Set<String> maintainers(Object raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String account : tokens(raw == null ? null : raw.toString())) {
            out.add(normalizeAccount(account));
        }
        return out;
    }

    /** The comparison form of one account name, or {@code null} for a blank one. */
    public static String normalizeAccount(String account) {
        if (account == null) {
            return null;
        }
        String trimmed = account.trim();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * Separates on newlines and commas, like the bot's user whitelist. A line is otherwise one
     * account, so "maria smith" stays one (invalid) entry rather than silently becoming two.
     */
    private static List<String> tokens(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String token : SEPARATORS.split(raw)) {
            String trimmed = token.trim();
            if (trimmed.startsWith("@")) {
                trimmed = trimmed.substring(1).trim();
            }
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static List<String> modeNames() {
        List<String> names = new ArrayList<>();
        for (AgentReviewLearningMode mode : AgentReviewLearningMode.values()) {
            names.add(mode.name());
        }
        return names;
    }
}
