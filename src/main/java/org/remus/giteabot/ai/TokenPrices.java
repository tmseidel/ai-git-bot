package org.remus.giteabot.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Token prices of an AI integration per million tokens, as in effect when usage is recorded. The
 * cost of an interaction is computed with them once and stored, so a later price change never
 * rewrites past cost. Cache prices fall back to the input price.
 */
public record TokenPrices(BigDecimal input, BigDecimal output, BigDecimal cacheWrite, BigDecimal cacheRead) {

    /** Decimal places of a stored cost (the {@code ai_interaction.cost} column). */
    public static final int COST_SCALE = 8;

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public TokenPrices {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
    }

    /** The prices, or {@code null} when the input or output price is not set (usage stays unpriced). */
    public static TokenPrices ofNullable(BigDecimal input, BigDecimal output, BigDecimal cacheWrite,
                                         BigDecimal cacheRead) {
        return input == null || output == null ? null : new TokenPrices(input, output, cacheWrite, cacheRead);
    }

    /**
     * The cost of one interaction. {@code inputTokens} is the total processed input, including the
     * cache write and cache read tokens.
     */
    public BigDecimal cost(long inputTokens, long outputTokens, long cacheWriteTokens, long cacheReadTokens) {
        long uncached = Math.max(0, inputTokens - cacheWriteTokens - cacheReadTokens);
        BigDecimal total = BigDecimal.valueOf(uncached).multiply(input)
                .add(BigDecimal.valueOf(cacheWriteTokens).multiply(cacheWrite != null ? cacheWrite : input))
                .add(BigDecimal.valueOf(cacheReadTokens).multiply(cacheRead != null ? cacheRead : input))
                .add(BigDecimal.valueOf(outputTokens).multiply(output));
        return total.divide(MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }
}
