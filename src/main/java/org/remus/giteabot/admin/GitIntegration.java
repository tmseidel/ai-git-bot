package org.remus.giteabot.admin;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.remus.giteabot.repository.GitTransport;
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

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

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

    @Column
    @ToString.Exclude
    private String token;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private GitTransport transport = GitTransport.HTTP;

    @Column(columnDefinition = "TEXT")
    @ToString.Exclude
    private String sshPrivateKey;

    @Column(columnDefinition = "TEXT")
    @ToString.Exclude
    private String sshKnownHosts;

    @Column
    private Long sshRemoteKeyId;

    @Column
    private Long sshRemoteKeyOwnerId;

    @Column
    private String sshRemoteKeyTitle;

    @Column(name = "deletion_pending", nullable = false)
    private boolean deletionPending;

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

    /** Returns whether any retryable managed-key marker is present. */
    public boolean hasManagedSshKeyTracking() {
        return sshRemoteKeyId != null || sshRemoteKeyOwnerId != null || sshRemoteKeyTitle != null;
    }
}
