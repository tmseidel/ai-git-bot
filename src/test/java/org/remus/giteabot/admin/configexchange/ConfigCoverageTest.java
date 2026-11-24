package org.remus.giteabot.admin.configexchange;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.aiusage.AiInteraction;
import org.remus.giteabot.aiusage.AiToolCall;
import org.remus.giteabot.audit.PrAuditEvent;
import org.remus.giteabot.eventhook.EventHookDelivery;
import org.remus.giteabot.prworkflow.PrWorkflowRun;
import org.remus.giteabot.prworkflow.PrWorkflowStep;
import org.remus.giteabot.prworkflow.config.WorkflowSelection;
import org.remus.giteabot.prworkflow.config.WorkflowSelectionParam;
import org.remus.giteabot.prworkflow.e2e.PrTestCase;
import org.remus.giteabot.prworkflow.e2e.PrTestSuite;
import org.remus.giteabot.prworkflow.unittest.UnitTestCase;
import org.remus.giteabot.prworkflow.unittest.UnitTestSuite;
import org.remus.giteabot.review.structured.ReferenceSequence;
import org.remus.giteabot.review.structured.ReviewAssessment;
import org.remus.giteabot.review.structured.ReviewFinding;
import org.remus.giteabot.review.structured.ReviewFindingLocation;
import org.remus.giteabot.learning.feedback.WorkflowFeedback;
import org.remus.giteabot.learning.feedback.WorkflowFeedbackEvent;
import org.remus.giteabot.review.structured.ReviewPublication;
import org.remus.giteabot.review.structured.ReviewRecord;
import org.remus.giteabot.review.structured.ReviewToolOperation;
import org.remus.giteabot.review.structured.WorkflowOutput;
import org.remus.giteabot.review.structured.WorkflowOutputSnapshot;
import org.remus.giteabot.session.ConversationMessage;
import org.remus.giteabot.session.ReviewSession;
import org.remus.giteabot.systemsettings.BotToolSelection;
import org.remus.giteabot.systemsettings.McpSelectedTool;
import org.remus.giteabot.workflowrun.WorkflowRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps "every configuration entity can be created from a document" true over time.
 *
 * <p>A new {@code @Entity} fails this test until it is either registered in
 * {@link ConfigExchangeRegistry} or listed below with a reason. That is deliberate: the
 * import format has no schema of its own, so the registry is the only place that can
 * silently fall behind the model.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfigCoverageTest {

    /**
     * Entities that are not importable as a table, with the reason. Anything not listed
     * here and not in the registry is a gap, not a decision.
     */
    private static final Map<Class<?>, String> NOT_A_TABLE = new LinkedHashMap<>();

    static {
        // Children of an importable configuration: created through their parent, which
        // declares the @OneToMany with cascade = ALL.
        NOT_A_TABLE.put(WorkflowSelection.class, "child of workflowConfigurations");
        NOT_A_TABLE.put(WorkflowSelectionParam.class, "child of workflow selections");
        NOT_A_TABLE.put(BotToolSelection.class, "child of botToolConfigurations");
        NOT_A_TABLE.put(McpSelectedTool.class, "child of mcpConfigurations");

        // Runtime output, never configuration.
        NOT_A_TABLE.put(AgentSession.class, "agent conversation state");
        NOT_A_TABLE.put(ConversationMessage.class, "agent conversation state");
        NOT_A_TABLE.put(ReviewSession.class, "review conversation state");
        NOT_A_TABLE.put(PrWorkflowRun.class, "run history");
        NOT_A_TABLE.put(PrWorkflowStep.class, "run history");
        NOT_A_TABLE.put(PrAuditEvent.class, "audit log");
        NOT_A_TABLE.put(PrTestSuite.class, "generated test suites");
        NOT_A_TABLE.put(PrTestCase.class, "generated test suites");
        NOT_A_TABLE.put(UnitTestSuite.class, "generated test suites");
        NOT_A_TABLE.put(UnitTestCase.class, "generated test suites");
        NOT_A_TABLE.put(AiInteraction.class, "usage telemetry");
        NOT_A_TABLE.put(AiToolCall.class, "usage telemetry");
        NOT_A_TABLE.put(WorkflowRun.class, "usage telemetry");
        NOT_A_TABLE.put(EventHookDelivery.class, "delivery log");
        NOT_A_TABLE.put(WorkflowOutput.class, "structured review output");
        NOT_A_TABLE.put(ReviewRecord.class, "structured review draft/final record");
        NOT_A_TABLE.put(ReviewFinding.class, "structured review finding");
        NOT_A_TABLE.put(ReviewFindingLocation.class, "structured review finding location");
        NOT_A_TABLE.put(ReviewAssessment.class, "structured review assessment");
        NOT_A_TABLE.put(ReviewToolOperation.class, "review tool receipts");
        NOT_A_TABLE.put(ReviewPublication.class, "review publication log");
        NOT_A_TABLE.put(WorkflowOutputSnapshot.class, "supplemental learning evidence");
        NOT_A_TABLE.put(WorkflowFeedback.class, "runtime feedback records");
        NOT_A_TABLE.put(WorkflowFeedbackEvent.class, "runtime feedback history");
        NOT_A_TABLE.put(ReferenceSequence.class, "runtime reference counters");
    }

    @Autowired
    private ConfigExchangeRegistry registry;

    @Test
    void everyEntityIsImportableOrExplicitlyExcluded() {
        Set<Class<?>> importable = registry.tables().stream()
                .map(ConfigTable::type)
                .collect(Collectors.toSet());

        List<String> unaccounted = new ClassFileImporter()
                .importPackages("org.remus.giteabot")
                .stream()
                .filter(type -> type.isAnnotatedWith(Entity.class))
                .map(JavaClass::reflect)
                .filter(type -> !importable.contains(type) && !NOT_A_TABLE.containsKey(type))
                .map(Class::getName)
                .sorted()
                .toList();

        assertTrue(unaccounted.isEmpty(),
                "New @Entity neither importable nor excluded — add a ConfigTable entry in "
                        + "admin/configexchange/ConfigExchangeRegistry or justify it in "
                        + "ConfigCoverageTest.NOT_A_TABLE: " + unaccounted);
    }
}
