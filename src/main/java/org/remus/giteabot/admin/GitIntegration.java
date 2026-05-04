package org.remus.giteabot.admin;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryType;

import java.time.Instant;

@Data
@NoArgsConstructor
@Entity
@Table(name = "git_integrations")
public class GitIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RepositoryType providerType = RepositoryType.GITEA;

    @Column(nullable = false)
    private String url;

    /**
     * Username for authentication (required for Bitbucket Cloud with App Passwords).
     * For other providers, this field may be optional or unused.
     */
    @Column
    private String username;

    @Column(nullable = false)
    private String token;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private PostReviewAction postReviewAction = PostReviewAction.NONE;

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
