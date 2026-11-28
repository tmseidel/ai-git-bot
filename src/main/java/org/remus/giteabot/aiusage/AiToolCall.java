package org.remus.giteabot.aiusage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** One tool call the model requested in an {@link AiInteraction}'s response. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "ai_tool_call")
public class AiToolCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "interaction_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private AiInteraction interaction;

    /** 1-based order within the interaction. */
    @Column(nullable = false)
    private int sequence;

    /** MCP server name, or {@code built-in}. */
    @Column(nullable = false)
    private String source;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    /** Whether the call succeeded; a failed call has an {@link #errorMessage}. */
    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /**
     * The call's arguments as JSON, with URL credentials redacted and capped in length. Always
     * stored: unlike the raw AI payloads they do not carry the conversation context.
     */
    @Column(columnDefinition = "TEXT")
    private String arguments;
}
