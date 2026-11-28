package org.remus.giteabot.reviewquality;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The installation's assumptions for the estimated return of agentic reviews: minutes an accepted
 * finding saves per severity, minutes an incorrect finding costs, and what an engineering hour is
 * worth. One row; they are edited on the Review quality page.
 */
@Data
@Entity
@Table(name = "review_quality_settings")
public class ReviewQualitySettings {

    /** The single row's id. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "minutes_blocker", nullable = false)
    private int minutesBlocker = 90;

    @Column(name = "minutes_medium", nullable = false)
    private int minutesMedium = 30;

    @Column(name = "minutes_low", nullable = false)
    private int minutesLow = 5;

    @Column(name = "minutes_incorrect", nullable = false)
    private int minutesIncorrect = 10;

    @Column(name = "hourly_rate", nullable = false, precision = 12, scale = 2)
    private BigDecimal hourlyRate = new BigDecimal("85.00");

    @Column(nullable = false, length = 8)
    private String currency = "USD";

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.EPOCH;
}
