package org.remus.giteabot.agent.shared;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.config.AgentConfigProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates raw agent JSON-payloads against the bundled JSON-Schemas.
 *
 * <p>By default this runs in <em>observe-only</em> mode: violations are
 * counted via the {@code agent.plan.schema_violations_total} Micrometer
 * counter and logged at WARN level, but the parsers still execute their
 * existing repair heuristics. Switching {@code agent.schema.enforce=true}
 * causes parsers to discard payloads that do not satisfy the schema.</p>
 *
 * <p>The validator is also exposed as a process-wide singleton via
 * {@link AgentSchemaValidatorHolder} so that non-Spring components
 * (parsers constructed with {@code new}) can access it without a
 * constructor dependency.</p>
 *
 * <p>Built on top of {@code com.networknt:json-schema-validator} 3.x which
 * ships with the same Jackson 3 ({@code tools.jackson}) used elsewhere in
 * the project. The major version is also required by the MCP SDK
 * ({@code mcp-json-jackson3}), so a single coherent dependency satisfies
 * both modules.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSchemaValidator {

    private final ObjectMapper objectMapper = AgentJackson.mapper();
    private final SchemaRegistry registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<AgentSchema, Schema> schemas = new EnumMap<>(AgentSchema.class);
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final AgentConfigProperties config;

    @PostConstruct
    void init() {
        for (AgentSchema schema : AgentSchema.values()) {
            try (InputStream in = new ClassPathResource(schema.classpathLocation()).getInputStream()) {
                schemas.put(schema, registry.getSchema(in));
                log.info("Loaded JSON-Schema for {}", schema.agentLabel());
            } catch (IOException e) {
                log.error("Failed to load JSON-Schema {} from {}: {}",
                        schema, schema.classpathLocation(), e.getMessage());
            }
        }
        AgentSchemaValidatorHolder.set(this);
    }

    /**
     * Validates {@code json} against {@code schema}. Returns an empty optional
     * when validation succeeded (or when no schema is registered for the
     * requested kind, e.g. during early bootstrap), and a non-empty optional
     * containing the validation errors when it failed. Always counts
     * violations against the {@code agent.plan.schema_violations_total} meter.
     */
    public Optional<List<Error>> validate(String json, AgentSchema schema) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        Schema compiled = schemas.get(schema);
        if (compiled == null) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            // Not even valid JSON. The repair layer in the parsers handles this.
            recordViolation(schema);
            log.warn("Schema {} validation skipped: payload is not valid JSON ({}). Snippet: {}",
                    schema.agentLabel(), e.getMessage(), snippet(json));
            return Optional.of(List.of());
        }
        List<Error> errors = compiled.validate(node);
        if (errors == null || errors.isEmpty()) {
            return Optional.empty();
        }
        recordViolation(schema);
        log.warn("Schema violation for agent={}: {} error(s). First: {}. Snippet: {}",
                schema.agentLabel(), errors.size(), errors.get(0), snippet(json));
        return Optional.of(List.copyOf(errors));
    }

    /**
     * Returns {@code true} when the {@code agent.schema.enforce} flag is set
     * and parsers should reject payloads that fail validation.
     */
    public boolean isEnforce() {
        return config != null && config.getSchema() != null && config.getSchema().isEnforce();
    }

    private void recordViolation(AgentSchema schema) {
        if (meterRegistry == null) {
            return;
        }
        Counter counter = counters.computeIfAbsent(schema.agentLabel(),
                label -> Counter.builder("agent.plan.schema_violations_total")
                        .description("Number of agent plan responses that failed JSON-Schema validation.")
                        .tag("agent", label)
                        .register(meterRegistry));
        counter.increment();
    }

    private static String snippet(String json) {
        if (json == null) {
            return "";
        }
        return json.length() <= 500 ? json : json.substring(0, 500) + "…";
    }

    /**
     * Test/utility constructor that wires no metrics or config, e.g. for unit
     * tests that just want to assert schema conformance.
     */
    public static AgentSchemaValidator forTesting() {
        AgentSchemaValidator v = new AgentSchemaValidator(null, null);
        v.init();
        return v;
    }

    /** Returns an unmodifiable snapshot of the loaded schemas (for diagnostics). */
    public Map<AgentSchema, Schema> loadedSchemas() {
        return Collections.unmodifiableMap(schemas);
    }
}

