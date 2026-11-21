package org.remus.giteabot.util;

import java.util.function.Predicate;

/** Cause-chain lookups shared by the error classifiers. */
public final class Throwables {

    /** Guards against cause chains that loop through overridden {@code getCause()}. */
    private static final int MAX_DEPTH = 20;

    private Throwables() {
    }

    /** The first throwable in the cause chain, starting with {@code error}, that matches, or null. */
    public static Throwable findCause(Throwable error, Predicate<Throwable> matches) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++, current = current.getCause()) {
            if (matches.test(current)) {
                return current;
            }
        }
        return null;
    }

    /** Whether any throwable in the cause chain, starting with {@code error}, matches. */
    public static boolean hasCause(Throwable error, Predicate<Throwable> matches) {
        return findCause(error, matches) != null;
    }

    /** The first throwable in the cause chain of the given type, or null. */
    public static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        return type.cast(findCause(error, type::isInstance));
    }
}
