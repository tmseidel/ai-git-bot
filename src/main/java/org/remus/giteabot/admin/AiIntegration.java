package org.remus.giteabot.admin;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@Entity
@Table(name = "ai_integrations")
public class AiIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String providerType;

    @Column(nullable = false)
    private String apiUrl;

    @Column(length = 1000)
    private String apiKey;

    private String apiVersion;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private int maxTokens = 4096;

    @Column(nullable = false)
    private int maxDiffCharsPerChunk = 120000;

    @Column(nullable = false)
    private int maxDiffChunks = 8;

    @Column(nullable = false)
    private int retryTruncatedChunkChars = 60000;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
