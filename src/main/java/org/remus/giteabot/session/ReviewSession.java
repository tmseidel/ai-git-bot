package org.remus.giteabot.session;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@NoArgsConstructor
@Entity
@Table(name = "review_sessions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"repoOwner", "repoName", "prNumber"}))
public class ReviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String repoOwner;

    @Column(nullable = false)
    private String repoName;

    @Column(nullable = false)
    private Long prNumber;

    private String promptName;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "review_session_participants", joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "participant_login", nullable = false)
    private Set<String> participants = new HashSet<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    @OrderBy("createdAt ASC")
    private List<ConversationMessage> messages = new ArrayList<>();

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

    public ReviewSession(String repoOwner, String repoName, Long prNumber, String promptName) {
        this.repoOwner = repoOwner;
        this.repoName = repoName;
        this.prNumber = prNumber;
        this.promptName = promptName;
    }

    public void addMessage(String role, String content) {
        messages.add(new ConversationMessage(role, content));
    }

    public boolean hasParticipant(String login) {
        return normalizeParticipant(login) != null && participants.contains(normalizeParticipant(login));
    }

    public void addParticipant(String login) {
        String normalized = normalizeParticipant(login);
        if (normalized != null) {
            participants.add(normalized);
        }
    }

    private String normalizeParticipant(String login) {
        if (login == null || login.isBlank()) {
            return null;
        }
        return login.trim().toLowerCase();
    }
}
