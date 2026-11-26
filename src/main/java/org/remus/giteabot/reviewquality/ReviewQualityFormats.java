package org.remus.giteabot.reviewquality;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/** Locale-aware number formatting for the Review quality page. */
final class ReviewQualityFormats {

    private final Locale locale;

    ReviewQualityFormats(Locale locale) {
        this.locale = locale;
    }

    String number(double value, int decimals) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(decimals);
        format.setMaximumFractionDigits(decimals);
        return format.format(value);
    }

    /** {@code value} per {@code count}, or "–" when there is nothing to divide by. */
    String ratio(double value, long count, int decimals) {
        return count <= 0 ? "–" : number(value / count, decimals);
    }

    String money(double value, String currency) {
        try {
            NumberFormat format = NumberFormat.getCurrencyInstance(locale);
            format.setCurrency(Currency.getInstance(currency));
            return format.format(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return number(value, 2) + " " + currency;
        }
    }

    String compact(long value) {
        NumberFormat format = NumberFormat.getCompactNumberInstance(locale, NumberFormat.Style.SHORT);
        format.setMaximumFractionDigits(1);
        return format.format(value);
    }
}
