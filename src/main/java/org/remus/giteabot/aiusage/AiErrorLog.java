package org.remus.giteabot.aiusage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Audit record of a failed AI provider interaction (e.g. an HTTP 401
 * response), including the full stack trace for diagnosis.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "ai_error_log")
public class AiErrorLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recorded_at", nullable = false)
    private Instant timestamp;

    @Column(nullable = false)
    private String aiIntegrationName;

    private String sessionId;

    @Column(length = 2000)
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String stackTrace;
}
