package org.remus.giteabot.systemsettings;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@Entity
@Table(name = "system_prompts")
public class SystemPrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reviewSystemPrompt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String issueAgentSystemPrompt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String writerAgentSystemPrompt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String e2ePlannerSystemPrompt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String e2eAuthorSystemPrompt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String e2eRunnerSystemPrompt;

    @Column(nullable = false)
    private boolean defaultEntry = false;

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
