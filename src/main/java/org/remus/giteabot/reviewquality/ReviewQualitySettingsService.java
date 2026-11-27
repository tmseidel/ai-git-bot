package org.remus.giteabot.reviewquality;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reads and stores the estimated-return assumptions; defaults apply until they are saved once. */
@Service
@RequiredArgsConstructor
public class ReviewQualitySettingsService {

    static final int MAX_MINUTES = 100_000;
    static final BigDecimal MAX_RATE = new BigDecimal("1000000");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z$€£¥]{1,8}");

    private final ReviewQualitySettingsRepository repository;

    @Transactional(readOnly = true)
    public ReviewQualitySettings current() {
        return repository.findById(ReviewQualitySettings.SINGLETON_ID).orElseGet(ReviewQualitySettings::new);
    }

    /** Stores the assumptions; values out of range are refused and nothing is changed. */
    @Transactional
    public ReviewQualitySettings save(int minutesBlocker, int minutesMedium, int minutesLow, int minutesIncorrect,
                                      BigDecimal hourlyRate, String currency, String admin) {
        String normalizedCurrency = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!inRange(minutesBlocker) || !inRange(minutesMedium) || !inRange(minutesLow)
                || !inRange(minutesIncorrect) || hourlyRate == null || hourlyRate.signum() < 0
                || hourlyRate.compareTo(MAX_RATE) > 0 || !CURRENCY.matcher(normalizedCurrency).matches()) {
            throw new IllegalArgumentException("Assumptions out of range");
        }
        ReviewQualitySettings settings = repository.findById(ReviewQualitySettings.SINGLETON_ID)
                .orElseGet(ReviewQualitySettings::new);
        settings.setMinutesBlocker(minutesBlocker);
        settings.setMinutesMedium(minutesMedium);
        settings.setMinutesLow(minutesLow);
        settings.setMinutesIncorrect(minutesIncorrect);
        settings.setHourlyRate(hourlyRate.setScale(2, RoundingMode.HALF_UP));
        settings.setCurrency(normalizedCurrency);
        settings.setUpdatedBy(admin == null ? null : admin.length() > 255 ? admin.substring(0, 255) : admin);
        settings.setUpdatedAt(Instant.now());
        return repository.save(settings);
    }

    private static boolean inRange(int minutes) {
        return minutes >= 0 && minutes <= MAX_MINUTES;
    }
}
