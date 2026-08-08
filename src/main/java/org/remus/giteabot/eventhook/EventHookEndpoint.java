package org.remus.giteabot.eventhook;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An admin-configured outgoing-webhook endpoint.
 *
 * <p>{@link #secret}, {@link #authorizationHeader}, and {@link #customHeaders} hold
 * <strong>encrypted</strong> values (AES-GCM ciphertext, Base64) — never
 * plaintext. Callers that need the plaintext must go through
 * {@link EventHookEndpointService#decryptSecret} /
 * {@link EventHookEndpointService#decryptAuthorizationHeader} /
 * {@link EventHookEndpointService#decryptCustomHeaders}. All are
 * optional and independent: unsigned + unauthenticated, signed-only,
 * auth-header-only, or both are all valid configurations.
 *
 * <p>Null scope columns ({@link #botId}, {@link #repoOwner},
 * {@link #repoName}) mean "global" — the endpoint receives events from
 * every bot and repository.
 */
@Slf4j
@Data
@NoArgsConstructor
@Entity
@Table(name = "event_hook_endpoint")
public class EventHookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 1024)
    private String url;

    /** AES-GCM ciphertext (Base64) of the HMAC signing secret; null = unsigned deliveries. */
    @Column(length = 1000)
    private String secret;

    /** AES-GCM ciphertext (Base64) of a static Authorization header value; null = none sent. */
    @Column(length = 1000)
    private String authorizationHeader;

    /** Opt-out of HTTPS certificate validation for self-signed/internal PKI targets. Insecure. */
    @Column(nullable = false)
    private boolean skipTlsVerify = false;

    @Column(nullable = false)
    private boolean enabled = true;

    /** Comma-separated {@link EventHookEventType} names this endpoint subscribes to. */
    @Column(nullable = false, length = 1024)
    private String eventTypes;

    /** AES-GCM ciphertext (Base64) of a JSON object of extra HTTP headers. */
    @Column(columnDefinition = "TEXT")
    private String customHeaders;

    private Long botId;

    private String repoOwner;

    private String repoName;

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

    public Set<EventHookEventType> subscribedEventTypes() {
        if (eventTypes == null || eventTypes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(eventTypes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(EventHookEventType::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isSubscribedTo(EventHookEventType type) {
        return enabled && subscribedEventTypes().contains(type);
    }

    /** null/blank scope columns mean "global". */
    public boolean matchesScope(Long botId, String repoOwner, String repoName) {
        if (this.botId != null && !this.botId.equals(botId)) {
            return false;
        }
        if (this.repoOwner != null && !this.repoOwner.isBlank() && !this.repoOwner.equalsIgnoreCase(repoOwner)) {
            return false;
        }
        if (this.repoName != null && !this.repoName.isBlank() && !this.repoName.equalsIgnoreCase(repoName)) {
            return false;
        }
        return true;
    }

    /** Parses the given raw JSON as a headers object; empty map on missing/invalid content. */
    public Map<String, String> parsedCustomHeaders(ObjectMapper mapper, String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = mapper.readValue(rawJson, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("Invalid custom_headers JSON on endpoint {}: {}", id, e.getMessage());
            return Map.of();
        }
    }
}
