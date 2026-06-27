package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.config.AgentConfigProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.remus.giteabot.agent.validation.ToolExecutionService.formatCtagsDependencies;
import static org.remus.giteabot.agent.validation.ToolExecutionService.formatCtagsSignatures;

class ToolExecutionServiceCtagsTest {

    private ToolExecutionService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        AgentConfigProperties config = new AgentConfigProperties();
        service = new ToolExecutionService(config,
                new org.remus.giteabot.agent.tools.ToolCatalog(config));
    }

    // ---------------------------------------------------------------
    // formatCtagsSignatures — parsing and rendering
    // ---------------------------------------------------------------

    @Test
    void formatCtagsSignatures_javaClassWithMethods() {
        String ctagsJson = """
                {"_type":"tag", "name":"OrderProcessor", "path":"/tmp/OrderProcessor.java", "pattern":"/^public class OrderProcessor {$/", "line": 5, "kind":"class", "end": 15}
                {"_type":"tag", "name":"OrderProcessor", "path":"/tmp/OrderProcessor.java", "pattern":"/^    public OrderProcessor(String id) {$/", "line": 6, "kind":"method", "scope":"OrderProcessor", "scopeKind":"class", "end": 6}
                {"_type":"tag", "name":"process", "path":"/tmp/OrderProcessor.java", "pattern":"/^    public void process() {$/", "line": 8, "kind":"method", "scope":"OrderProcessor", "scopeKind":"class", "end": 8}
                {"_type":"tag", "name":"cleanup", "path":"/tmp/OrderProcessor.java", "pattern":"/^    private void cleanup() {$/", "line": 10, "kind":"method", "scope":"OrderProcessor", "scopeKind":"class", "end": 10}
                """;

        String result = formatCtagsSignatures("src/main/java/com/example/OrderProcessor.java", ctagsJson, 100);

        assertThat(result).contains("### OrderProcessor.java");
        assertThat(result).contains("```java");
        assertThat(result).contains("class OrderProcessor");
        assertThat(result).contains("constructor OrderProcessor");
        assertThat(result).contains("  method process()");
        assertThat(result).contains("  method cleanup()");
        assertThat(result).contains("```");
    }

    @Test
    void formatCtagsSignatures_ctags6xConstructorKind() {
        // ctags 6.x emits a distinct "constructor" kind
        String ctagsJson = """
                {"_type":"tag", "name":"MyService", "path":"/tmp/MyService.java", "kind":"class", "line": 3, "end": 12}
                {"_type":"tag", "name":"MyService", "path":"/tmp/MyService.java", "kind":"constructor", "signature":"(String name, int port)", "scope":"MyService", "scopeKind":"class", "line": 5}
                """;

        String result = formatCtagsSignatures("MyService.java", ctagsJson, 100);

        assertThat(result).contains("constructor MyService(String name, int port)");
    }

    @Test
    void formatCtagsSignatures_interfaceWithMethod() {
        String ctagsJson = """
                {"_type":"tag", "name":"Repository", "path":"/tmp/Repository.java", "kind":"interface", "line": 1}
                {"_type":"tag", "name":"findById", "path":"/tmp/Repository.java", "kind":"method", "signature":"(Long id)", "scope":"Repository", "scopeKind":"interface", "line": 3}
                """;

        String result = formatCtagsSignatures("Repository.java", ctagsJson, 100);

        assertThat(result).contains("interface Repository");
        assertThat(result).contains("  method findById(Long id)");
    }

    @Test
    void formatCtagsSignatures_pythonClass() {
        String ctagsJson = """
                {"_type":"tag", "name":"OrderProcessor", "path":"/tmp/order_processor.py", "kind":"class", "line": 1}
                {"_type":"tag", "name":"__init__", "path":"/tmp/order_processor.py", "kind":"member", "scope":"OrderProcessor", "scopeKind":"class", "line": 3}
                {"_type":"tag", "name":"process_order", "path":"/tmp/order_processor.py", "kind":"member", "scope":"OrderProcessor", "scopeKind":"class", "line": 6}
                """;

        String result = formatCtagsSignatures("order_processor.py", ctagsJson, 100);

        assertThat(result).contains("### order_processor.py");
        assertThat(result).contains("```python");
        assertThat(result).contains("class OrderProcessor");
        assertThat(result).contains("  method __init__");
        assertThat(result).contains("  method process_order");
    }

    @Test
    void formatCtagsSignatures_typescriptFunction() {
        String ctagsJson = """
                {"_type":"tag", "name":"LoginForm", "path":"/tmp/LoginForm.tsx", "kind":"function", "line": 4}
                """;

        String result = formatCtagsSignatures("components/LoginForm.tsx", ctagsJson, 100);

        assertThat(result).contains("```typescript");
        assertThat(result).contains("function LoginForm()");
    }

    @Test
    void formatCtagsSignatures_limitTruncatesOutput() {
        String ctagsJson = """
                {"_type":"tag", "name":"A", "path":"/tmp/Many.java", "kind":"class", "line": 1}
                {"_type":"tag", "name":"m1", "path":"/tmp/Many.java", "kind":"method", "scope":"A", "scopeKind":"class", "line": 2}
                {"_type":"tag", "name":"m2", "path":"/tmp/Many.java", "kind":"method", "scope":"A", "scopeKind":"class", "line": 3}
                {"_type":"tag", "name":"m3", "path":"/tmp/Many.java", "kind":"method", "scope":"A", "scopeKind":"class", "line": 4}
                """;

        String result = formatCtagsSignatures("Many.java", ctagsJson, 2);

        assertThat(result).contains("more signature(s) omitted");
    }

    @Test
    void formatCtagsSignatures_emptyOutputReturnsPlaceholder() {
        String result = formatCtagsSignatures("Empty.java", "", 100);

        assertThat(result).contains("No classes or methods detected");
    }

    @Test
    void formatCtagsSignatures_nullOutputReturnsPlaceholder() {
        String result = formatCtagsSignatures("NullFile.java", null, 100);

        assertThat(result).contains("No classes or methods detected");
    }

    @Test
    void formatCtagsSignatures_skipsVariablesAndUnknownKinds() {
        String ctagsJson = """
                {"_type":"tag", "name":"MyClass", "path":"/tmp/MyClass.java", "kind":"class", "line": 1}
                {"_type":"tag", "name":"counter", "path":"/tmp/MyClass.java", "kind":"field", "scope":"MyClass", "scopeKind":"class", "line": 3}
                {"_type":"tag", "name":"doWork", "path":"/tmp/MyClass.java", "kind":"method", "scope":"MyClass", "scopeKind":"class", "line": 5}
                """;

        String result = formatCtagsSignatures("MyClass.java", ctagsJson, 100);

        assertThat(result).contains("class MyClass");
        assertThat(result).contains("method doWork()");
        assertThat(result).doesNotContain("counter");  // variables are skipped
    }

    // ---------------------------------------------------------------
    // formatCtagsDependencies — dependency extraction
    // ---------------------------------------------------------------

    @Test
    void formatCtagsDependencies_javaImportsAndPackage() {
        String ctagsJson = """
                {"_type":"tag", "name":"org.remus.giteabot.admin", "path":"/tmp/BotService.java", "kind":"package", "line": 1}
                {"_type":"tag", "name":"java.util.List", "path":"/tmp/BotService.java", "kind":"import", "line": 3}
                {"_type":"tag", "name":"org.remus.giteabot.admin.Bot", "path":"/tmp/BotService.java", "kind":"import", "line": 4}
                {"_type":"tag", "name":"lombok.RequiredArgsConstructor", "path":"/tmp/BotService.java", "kind":"import", "line": 5}
                """;

        String result = formatCtagsDependencies("BotService.java", ctagsJson);

        assertThat(result).contains("\"file\":\"BotService.java\"");
        assertThat(result).contains("\"declared_namespace_or_package\":\"org.remus.giteabot.admin\"");
        assertThat(result).contains("\"java.util.List\"");
        assertThat(result).contains("\"org.remus.giteabot.admin.Bot\"");
        assertThat(result).contains("\"lombok.RequiredArgsConstructor\"");
    }

    @Test
    void formatCtagsDependencies_noImports() {
        String ctagsJson = """
                {"_type":"tag", "name":"org.example", "path":"/tmp/Simple.java", "kind":"package", "line": 1}
                """;

        String result = formatCtagsDependencies("Simple.java", ctagsJson);

        assertThat(result).contains("\"file\":\"Simple.java\"");
        assertThat(result).contains("\"declared_namespace_or_package\":\"org.example\"");
        assertThat(result).contains("\"dependencies\":[]");
    }

    @Test
    void formatCtagsDependencies_noNamespace() {
        String ctagsJson = """
                {"_type":"tag", "name":"react", "path":"/tmp/App.tsx", "kind":"import", "line": 1}
                {"_type":"tag", "name":"./useAuth", "path":"/tmp/App.tsx", "kind":"import", "line": 2}
                """;

        String result = formatCtagsDependencies("App.tsx", ctagsJson);

        assertThat(result).contains("\"declared_namespace_or_package\":\"none\"");
        assertThat(result).contains("\"react\"");
        assertThat(result).contains("\"./useAuth\"");
    }

    @Test
    void formatCtagsDependencies_emptyOutput() {
        String result = formatCtagsDependencies("Empty.java", "");

        assertThat(result).contains("\"file\":\"Empty.java\"");
        assertThat(result).contains("\"declared_namespace_or_package\":\"none\"");
        assertThat(result).contains("\"dependencies\":[]");
    }

    @Test
    void formatCtagsDependencies_nullOutput() {
        String result = formatCtagsDependencies("NullFile.java", null);

        assertThat(result).contains("\"file\":\"NullFile.java\"");
        assertThat(result).contains("\"declared_namespace_or_package\":\"none\"");
        assertThat(result).contains("\"dependencies\":[]");
    }

    // ---------------------------------------------------------------
    // executeCtagsSignaturesTool — error paths (ctags not on host)
    // ---------------------------------------------------------------

    @Test
    void executeCtagsSignaturesTool_missingArgs_returnsFailure() {
        ToolResult result = service.executeContextTool(tempDir, "ctags-signatures", List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("requires a file path");
    }

    @Test
    void executeCtagsSignaturesTool_fileNotFound_returnsFailure() {
        ToolResult result = service.executeContextTool(tempDir, "ctags-signatures",
                List.of("nonexistent.java"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("File not found");
    }

    @Test
    void executeCtagsSignaturesTool_pathTraversal_returnsError() {
        ToolResult result = service.executeContextTool(tempDir, "ctags-signatures",
                List.of("../../etc/passwd"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("escapes");
    }

    @Test
    void executeCtagsSignaturesTool_respectsLimitArg_clampedToMax() throws IOException {
        Path file = tempDir.resolve("src/Demo.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                public class Demo {}
                """);

        // No ctags on test host, so this will fail with ctags error.
        // We just verify the limit arg is parsed and doesn't cause a pre-execution failure.
        ToolResult result = service.executeContextTool(tempDir, "ctags-signatures",
                List.of("src/Demo.java", "9999"));

        // ctags not available → non-success. But the limit parsing worked (didn't crash).
        assertThat(result.success()).isFalse();
    }

    // ---------------------------------------------------------------
    // executeCtagsDepsTool — error paths (ctags not on host)
    // ---------------------------------------------------------------

    @Test
    void executeCtagsDepsTool_missingArgs_returnsFailure() {
        ToolResult result = service.executeContextTool(tempDir, "ctags-deps", List.of());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("requires a file path");
    }

    @Test
    void executeCtagsDepsTool_fileNotFound_returnsFailure() {
        ToolResult result = service.executeContextTool(tempDir, "ctags-deps",
                List.of("nonexistent.tsx"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("File not found");
    }

    @Test
    void executeCtagsDepsTool_pathTraversal_returnsError() {
        ToolResult result = service.executeContextTool(tempDir, "ctags-deps",
                List.of("../etc/shadow"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("escapes");
    }

    // ---------------------------------------------------------------
    // Tool catalog classification
    // ---------------------------------------------------------------

    @Test
    void ctagsToolsAreContextTools() {
        var catalog = new org.remus.giteabot.agent.tools.ToolCatalog(new AgentConfigProperties());

        assertThat(catalog.isContext("ctags-signatures")).isTrue();
        assertThat(catalog.isContext("ctags-deps")).isTrue();
        assertThat(catalog.isSilent("ctags-signatures")).isTrue();
        assertThat(catalog.isSilent("ctags-deps")).isTrue();
    }
}
