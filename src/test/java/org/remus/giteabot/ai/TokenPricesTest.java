package org.remus.giteabot.ai;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TokenPricesTest {

    @Test
    void uncachedInputCacheWritesCacheReadsAndOutputArePricedPerMillionTokens() {
        TokenPrices prices = new TokenPrices(new BigDecimal("3"), new BigDecimal("15"), new BigDecimal("3.75"),
                new BigDecimal("0.30"));
        // 300k uncached x 3 + 200k write x 3.75 + 500k read x 0.30 + 100k output x 15
        assertThat(prices.cost(1_000_000, 100_000, 200_000, 500_000)).isEqualByComparingTo("3.30");
        assertThat(prices.cost(1, 0, 0, 0)).isEqualByComparingTo("0.000003")
                .satisfies(cost -> assertThat(cost.scale()).isEqualTo(TokenPrices.COST_SCALE));
    }

    @Test
    void cachePricesFallBackToTheInputPrice() {
        TokenPrices prices = new TokenPrices(new BigDecimal("2"), new BigDecimal("10"), null, null);
        assertThat(prices.cost(1_000_000, 0, 400_000, 600_000)).isEqualByComparingTo("2");
    }

    @Test
    void withoutAnInputOrOutputPriceTheUsageStaysUnpriced() {
        assertThat(TokenPrices.ofNullable(null, BigDecimal.ONE, null, null)).isNull();
        assertThat(TokenPrices.ofNullable(BigDecimal.ONE, null, BigDecimal.ONE, BigDecimal.ONE)).isNull();
        assertThat(TokenPrices.ofNullable(BigDecimal.ONE, BigDecimal.ONE, null, null)).isNotNull();
    }
}
