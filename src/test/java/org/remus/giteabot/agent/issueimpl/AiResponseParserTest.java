package org.remus.giteabot.agent.issueimpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.model.FileChange;
import org.remus.giteabot.agent.model.ImplementationPlan;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiResponseParserTest {

    private AiResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new AiResponseParser();
    }

    // ---- parseAiResponse tests ----

    @Test
    void parseAiResponse_validJson_returnsImplementationPlan() {
        String aiResponse = """
                Here is the implementation:
                ```json
                {
                  "summary": "Added hello world feature",
                  "fileChanges": [
                    {
                      "path": "src/main/java/Hello.java",
                      "operation": "CREATE",
                      "content": "public class Hello {}"
                    }
                  ]
                }
                ```
                """;

        ImplementationPlan plan = parser.parseAiResponse(aiResponse);

        assertThat(plan).isNotNull();
        assertThat(plan.getSummary()).isEqualTo("Added hello world feature");
        assertThat(plan.getFileChanges()).hasSize(1);
        assertThat(plan.getFileChanges().getFirst().getPath()).isEqualTo("src/main/java/Hello.java");
        assertThat(plan.getFileChanges().getFirst().getOperation()).isEqualTo(FileChange.Operation.CREATE);
        assertThat(plan.getFileChanges().getFirst().getContent()).isEqualTo("public class Hello {}");
    }

    @Test
    void parseAiResponse_multipleFiles_parsesAll() {
        String aiResponse = """
                ```json
                {
                  "summary": "Implemented feature X",
                  "fileChanges": [
                    {
                      "path": "src/Foo.java",
                      "operation": "CREATE",
                      "content": "class Foo {}"
                    },
                    {
                      "path": "src/Bar.java",
                      "operation": "UPDATE",
                      "content": "class Bar { int x; }"
                    },
                    {
                      "path": "src/Old.java",
                      "operation": "DELETE",
                      "content": ""
                    }
                  ]
                }
                ```
                """;

        ImplementationPlan plan = parser.parseAiResponse(aiResponse);

        assertThat(plan).isNotNull();
        assertThat(plan.getFileChanges()).hasSize(3);
        assertThat(plan.getFileChanges().get(0).getOperation()).isEqualTo(FileChange.Operation.CREATE);
        assertThat(plan.getFileChanges().get(1).getOperation()).isEqualTo(FileChange.Operation.UPDATE);
        assertThat(plan.getFileChanges().get(2).getOperation()).isEqualTo(FileChange.Operation.DELETE);
    }

    @Test
    void parseAiResponse_invalidJson_returnsNull() {
        ImplementationPlan plan = parser.parseAiResponse("This is not valid JSON at all");
        assertThat(plan).isNull();
    }

    @Test
    void parseAiResponse_emptyResponse_returnsNull() {
        assertThat(parser.parseAiResponse(null)).isNull();
        assertThat(parser.parseAiResponse("")).isNull();
        assertThat(parser.parseAiResponse("   ")).isNull();
    }

    @Test
    void parseAiResponse_noFileChanges_returnsPlanWithEmptyChanges() {
        String aiResponse = """
                ```json
                {
                  "summary": "Nothing to do"
                }
                ```
                """;

        ImplementationPlan plan = parser.parseAiResponse(aiResponse);

        assertThat(plan).isNotNull();
        assertThat(plan.getSummary()).isEqualTo("Nothing to do");
        assertThat(plan.hasFileChanges()).isFalse();
    }

    @Test
    void parseAiResponse_withRequestFiles_returnsPlan() {
        String aiResponse = """
                ```json
                {
                  "summary": "Need more context",
                  "requestFiles": ["src/Main.java", "pom.xml"]
                }
                ```
                """;

        ImplementationPlan plan = parser.parseAiResponse(aiResponse);

        assertThat(plan).isNotNull();
        assertThat(plan.hasFileRequests()).isTrue();
        assertThat(plan.getRequestFiles()).containsExactly("src/Main.java", "pom.xml");
        assertThat(plan.hasFileChanges()).isFalse();
    }

    @Test
    void parseAiResponse_withRequestedFilesAndTools_returnsPlan() {
        String aiResponse = """
                ```json
                {
                  "summary": "Need more context",
                  "requestedFiles": ["src/Main.java"],
                  "requestedTools": [
                    {"tool": "rg", "args": ["UserService.save", "src"]},
                    {"tool": "cat", "args": ["pom.xml", "1", "20"]}
                  ]
                }
                ```
                """;

        ImplementationPlan plan = parser.parseAiResponse(aiResponse);

        assertThat(plan).isNotNull();
        assertThat(plan.hasContextRequests()).isTrue();
        assertThat(plan.getRequestFiles()).containsExactly("src/Main.java");
        assertThat(plan.getRequestTools()).hasSize(2);
        assertThat(plan.getRequestTools().getFirst().getTool()).isEqualTo("rg");
        assertThat(plan.getRequestTools().get(1).getArgs()).containsExactly("pom.xml", "1", "20");
    }

    @Test
    void parseAiResponse_withToolRequest_returnsPlanWithTool() {
        String aiResponse = """
                ```json
                {
                  "summary": "Implemented feature and requesting validation",
                  "fileChanges": [
                    {
                      "path": "src/main/java/Hello.java",
                      "operation": "CREATE",
                      "content": "public class Hello {}"
                    }
                  ],
                  "runTool": {
                    "tool": "mvn",
                    "args": ["compile", "-q", "-B"]
                  }
                }
                ```
                """;

        ImplementationPlan plan = parser.parseAiResponse(aiResponse);

        assertThat(plan).isNotNull();
        assertThat(plan.getSummary()).isEqualTo("Implemented feature and requesting validation");
        assertThat(plan.hasFileChanges()).isTrue();
        assertThat(plan.getFileChanges()).hasSize(1);
        if (plan.getToolRequest() != null) {
            assertThat(plan.getToolRequest().getTool()).isEqualTo("mvn");
            assertThat(plan.getToolRequest().getArgs()).containsExactly("compile", "-q", "-B");
        }
    }

    @Test
    void parseAiResponse_withDiff_returnsPlan() {
        String aiResponse = """
                ```json
                {
                  "summary": "Updated file",
                  "fileChanges": [
                    {
                      "path": "src/Test.java",
                      "operation": "UPDATE",
                      "diff": "<<<<<<< SEARCH\\nold\\n=======\\nnew\\n>>>>>>> REPLACE"
                    }
                  ]
                }
                ```
                """;

        ImplementationPlan plan = parser.parseAiResponse(aiResponse);

        assertThat(plan).isNotNull();
        assertThat(plan.getFileChanges()).hasSize(1);
        assertThat(plan.getFileChanges().getFirst().isDiffBased()).isTrue();
        assertThat(plan.getFileChanges().getFirst().getDiff()).contains("SEARCH");
    }

    @Test
    void parseAiResponse_rawJson_withoutCodeBlock() {
        String aiResponse = """
                {
                  "summary": "Direct JSON",
                  "fileChanges": [
                    {
                      "path": "test.txt",
                      "operation": "CREATE",
                      "content": "hello"
                    }
                  ]
                }
                """;

        ImplementationPlan plan = parser.parseAiResponse(aiResponse);

        assertThat(plan).isNotNull();
        assertThat(plan.getSummary()).isEqualTo("Direct JSON");
        assertThat(plan.getFileChanges()).hasSize(1);
    }

    @Test
    void parseAiResponse_rawJson_withRunTool() {
        String aiResponse = """
                {
                  "summary": "Implemented feature with validation",
                  "fileChanges": [
                    {
                      "path": "src/Main.java",
                      "operation": "CREATE",
                      "content": "public class Main {}"
                    }
                  ],
                  "runTool": {
                    "tool": "mvn",
                    "args": ["compile", "-q", "-B"]
                  }
                }
                """;

        ImplementationPlan plan = parser.parseAiResponse(aiResponse);

        assertThat(plan).isNotNull();
        assertThat(plan.hasFileChanges()).isTrue();
        assertThat(plan.hasToolRequest()).as("Plan should have tool request").isTrue();
        assertThat(plan.getToolRequest()).isNotNull();
        assertThat(plan.getToolRequest().getTool()).isEqualTo("mvn");
        assertThat(plan.getToolRequest().getArgs()).containsExactly("compile", "-q", "-B");
    }

    @Test
    void parseAiResponse_rawJson_withRunTool_directObjectMapper() {
        String jsonStr = """
                {
                  "summary": "Implemented feature with validation",
                  "fileChanges": [
                    {
                      "path": "src/Main.java",
                      "operation": "CREATE",
                      "content": "public class Main {}"
                    }
                  ],
                  "runTool": {
                    "tool": "mvn",
                    "args": ["compile", "-q", "-B"]
                  }
                }
                """;

        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();

        tools.jackson.databind.JsonNode root = mapper.readTree(jsonStr);
        assertThat(root.has("runTool")).isTrue();
        assertThat(root.get("runTool").has("tool")).isTrue();
        assertThat(root.get("runTool").get("tool").asString()).isEqualTo("mvn");
    }

    @Test
    void parseAiResponse_withInvalidEscapeSequence_stillParses() {
        String aiResponse = """
                ```json
                {
                  "summary": "Fix controller",
                  "fileChanges": [
                    {
                      "path": "src/main/java/Controller.java",
                      "operation": "UPDATE",
                      "diff": "<<<<<<< SEARCH\\        model.addAttribute();\\n=======\\n            model.addAttribute();\\n>>>>>>> REPLACE"
                    }
                  ]
                }
                ```
                """;

        ImplementationPlan plan = parser.parseAiResponse(aiResponse);

        assertThat(plan).isNotNull();
        assertThat(plan.getSummary()).isEqualTo("Fix controller");
        assertThat(plan.getFileChanges()).hasSize(1);
        assertThat(plan.getFileChanges().getFirst().getDiff()).contains("model.addAttribute()");
    }

    // ---- extractNonJsonResponse tests ----

    @Test
    void extractNonJsonResponse_pureJsonBlock_returnsNull() {
        String response = """
                ```json
                {
                  "summary": "test",
                  "fileChanges": []
                }
                ```
                """;
        assertThat(parser.extractNonJsonResponse(response)).isNull();
    }

    @Test
    void extractNonJsonResponse_pureRawJson_returnsNull() {
        String response = """
                {
                  "summary": "test",
                  "fileChanges": []
                }
                """;
        assertThat(parser.extractNonJsonResponse(response)).isNull();
    }

    @Test
    void extractNonJsonResponse_thinkingBeforeJson_returnsThinking() {
        String response = """
                I'll create the following files for you.
                ```json
                {
                  "summary": "test",
                  "fileChanges": []
                }
                ```
                """;
        String result = parser.extractNonJsonResponse(response);
        assertThat(result).isEqualTo("I'll create the following files for you.");
    }

    @Test
    void extractNonJsonResponse_onlyWhitespaceBeforeJson_returnsNull() {
        String response = "\n  \n```json\n{}\n```";
        assertThat(parser.extractNonJsonResponse(response)).isNull();
    }

    @Test
    void extractNonJsonResponse_plainText_returnsText() {
        String response = "I can't implement this because the issue is too vague.";
        assertThat(parser.extractNonJsonResponse(response)).isEqualTo(response);
    }

    // ---- sanitizeInvalidJsonEscapes tests ----

    @Test
    void sanitizeInvalidJsonEscapes_nullInput_returnsNull() {
        assertThat(parser.sanitizeInvalidJsonEscapes(null)).isNull();
    }

    @Test
    void sanitizeInvalidJsonEscapes_emptyInput_returnsEmpty() {
        assertThat(parser.sanitizeInvalidJsonEscapes("")).isEmpty();
    }

    @Test
    void sanitizeInvalidJsonEscapes_validEscapes_unchanged() {
        String input = "{ \"diff\": \"line1\\nline2\\ttab\\\\backslash\\\"quote\\\\/slash\" }";
        assertThat(parser.sanitizeInvalidJsonEscapes(input)).isEqualTo(input);
    }

    @Test
    void sanitizeInvalidJsonEscapes_backslashSpace_fixed() {
        String input = "{ \"diff\": \"<<<<<<< SEARCH\\        model\" }";
        String result = parser.sanitizeInvalidJsonEscapes(input);
        assertThat(result).isEqualTo("{ \"diff\": \"<<<<<<< SEARCH\\\\        model\" }");
    }

    @Test
    void sanitizeInvalidJsonEscapes_multipleInvalidEscapes_allFixed() {
        String input = "\"value\": \"\\a\\z\\1\"";
        String result = parser.sanitizeInvalidJsonEscapes(input);
        assertThat(result).isEqualTo("\"value\": \"\\\\a\\\\z\\\\1\"");
    }

    @Test
    void sanitizeInvalidJsonEscapes_mixedValidAndInvalid_onlyInvalidFixed() {
        String input = "\"diff\": \"line1\\n\\        line2\"";
        String result = parser.sanitizeInvalidJsonEscapes(input);
        assertThat(result).isEqualTo("\"diff\": \"line1\\n\\\\        line2\"");
    }

    // ---- parseRequestedFiles tests ----

    @Test
    void parseRequestedFiles_validJsonResponse_returnsFiles() {
        String response = """
                ```json
                {"reasoning": "Need to see these", "requestFiles": ["src/Main.java", "pom.xml"]}
                ```
                """;
        List<Map<String, Object>> tree = List.of(
                Map.of("type", "blob", "path", "src/Main.java"),
                Map.of("type", "blob", "path", "pom.xml"),
                Map.of("type", "blob", "path", "README.md")
        );

        List<String> files = parser.parseRequestedFiles(response, tree);

        assertThat(files).containsExactly("src/Main.java", "pom.xml");
    }

    @Test
    void parseRequestedFiles_invalidPath_filtered() {
        String response = """
                ```json
                {"reasoning": "...", "requestedFiles": ["src/Missing.java", "pom.xml"]}
                ```
                """;
        List<Map<String, Object>> tree = List.of(
                Map.of("type", "blob", "path", "pom.xml")
        );

        List<String> files = parser.parseRequestedFiles(response, tree);

        assertThat(files).containsExactly("pom.xml");
    }

    @Test
    void parseRequestedFiles_fallbackToPatternMatching() {
        String response = "I need to see pom.xml and README.md";
        List<Map<String, Object>> tree = List.of(
                Map.of("type", "blob", "path", "pom.xml"),
                Map.of("type", "blob", "path", "README.md")
        );

        List<String> files = parser.parseRequestedFiles(response, tree);

        assertThat(files).containsExactlyInAnyOrder("pom.xml", "README.md");
    }
}
