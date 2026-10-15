package org.remus.giteabot.admin;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.GitAuthor;

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
    @Column(nullable = false)
    private Long lockVersion;

    @Column(nullable = false)
    private boolean deletionPending;

    @Column(nullable = false)
    private boolean sshCleanupVerified;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private RepositoryType providerType = RepositoryType.GITEA;

    @Column(nullable = false)
    private String url;

    /**
     * A string used to identify the user for the provider.
     * For most providers, this is the username used with the API token for REST API calls and git operations.
     * Bitbucket Cloud: the Atlassian account e-mail used with the API token for REST API calls.
     */
    @Column(nullable = false)
    private String username;

    /**
     * Git author name for commits that bots using this integration push.
     * New integrations start with {@link GitAuthor#DEFAULT_NAME}.
     */
    @Column(name = "git_author_name", nullable = false)
    private String gitAuthorName = GitAuthor.DEFAULT_NAME;

    /**
     * Git author e-mail for commits that bots using this integration push.
     * New integrations start with {@link GitAuthor#DEFAULT_EMAIL}.
     */
    @Column(name = "git_author_email", nullable = false)
    private String gitAuthorEmail = GitAuthor.DEFAULT_EMAIL;

    @Column(length = 1000)
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

    /** Resolves the Git author identity for commits pushed through this integration. */
    public GitAuthor gitAuthor() {
        return new GitAuthor(gitAuthorName, gitAuthorEmail);
    }

    /** Includes incomplete registration markers so ambiguous failures remain recoverable. */
    public boolean hasManagedSshKeyTracking() {
        return sshRemoteKeyId != null || sshRemoteKeyOwnerId != null || sshRemoteKeyTitle != null;
    }

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
