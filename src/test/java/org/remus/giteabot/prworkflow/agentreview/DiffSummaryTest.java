package org.remus.giteabot.prworkflow.agentreview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiffSummaryTest {

    @Test
    void parse_emptyDiffReturnsEmptySummary() {
        DiffSummary summary = DiffSummary.parse("");
        assertThat(summary.isEmpty()).isTrue();
        assertThat(summary.fileCount()).isEqualTo(0);
        assertThat(summary.changedFiles()).isEmpty();
    }

    @Test
    void parse_nullDiffReturnsEmptySummary() {
        DiffSummary summary = DiffSummary.parse(null);
        assertThat(summary.isEmpty()).isTrue();
    }

    @Test
    void parse_singleFileModification() {
        String diff = """
                diff --git a/src/main/java/Foo.java b/src/main/java/Foo.java
                index abc1234..def5678 100644
                --- a/src/main/java/Foo.java
                +++ b/src/main/java/Foo.java
                @@ -10,6 +10,8 @@ public class Foo {
                     existing line
                -    removed line
                +    added line 1
                +    added line 2
                     existing line
                """;

        DiffSummary summary = DiffSummary.parse(diff);

        assertThat(summary.fileCount()).isEqualTo(1);
        assertThat(summary.changedFiles()).containsExactly("src/main/java/Foo.java");
        assertThat(summary.statLine()).contains("1 file changed");
        assertThat(summary.statLine()).contains("2 insertions(+)");
        assertThat(summary.statLine()).contains("1 deletion(-)");
    }

    @Test
    void parse_multipleFiles() {
        String diff = """
                diff --git a/file1.java b/file1.java
                index abc..def 100644
                --- a/file1.java
                +++ b/file1.java
                @@ -1,3 +1,4 @@
                +added line
                 existing
                diff --git a/file2.java b/file2.java
                index abc..def 100644
                --- a/file2.java
                +++ b/file2.java
                @@ -1,4 +1,3 @@
                 existing
                -removed line
                """;

        DiffSummary summary = DiffSummary.parse(diff);

        assertThat(summary.fileCount()).isEqualTo(2);
        assertThat(summary.changedFiles()).containsExactly("file1.java", "file2.java");
        assertThat(summary.statLine()).contains("2 files changed");
    }

    @Test
    void parse_newFile() {
        String diff = """
                diff --git a/newfile.java b/newfile.java
                new file mode 100644
                index 0000000..abc1234
                --- /dev/null
                +++ b/newfile.java
                @@ -0,0 +1,5 @@
                +line 1
                +line 2
                +line 3
                +line 4
                +line 5
                """;

        DiffSummary summary = DiffSummary.parse(diff);

        assertThat(summary.fileCount()).isEqualTo(1);
        assertThat(summary.changedFiles()).containsExactly("newfile.java");
        assertThat(summary.statLine()).contains("5 insertions(+)");
    }

    @Test
    void parse_deletedFile() {
        String diff = """
                diff --git a/deleted.java b/deleted.java
                deleted file mode 100644
                index abc1234..0000000
                --- a/deleted.java
                +++ /dev/null
                @@ -1,3 +0,0 @@
                -line 1
                -line 2
                -line 3
                """;

        DiffSummary summary = DiffSummary.parse(diff);

        assertThat(summary.fileCount()).isEqualTo(1);
        assertThat(summary.changedFiles()).containsExactly("deleted.java");
        assertThat(summary.statLine()).contains("3 deletions(-)");
    }

    @Test
    void fileTable_generatesMarkdownTable() {
        String diff = """
                diff --git a/file1.java b/file1.java
                index abc..def 100644
                --- a/file1.java
                +++ b/file1.java
                @@ -1,3 +1,4 @@
                +added
                 existing
                diff --git a/file2.java b/file2.java
                index abc..def 100644
                --- a/file2.java
                +++ b/file2.java
                @@ -1,4 +1,3 @@
                 existing
                -removed
                """;

        DiffSummary summary = DiffSummary.parse(diff);
        String table = summary.fileTable();

        assertThat(table).contains("| File | +/- |");
        assertThat(table).contains("|---|---|");
        assertThat(table).contains("file1.java");
        assertThat(table).contains("file2.java");
        assertThat(table).contains("+1 / -0");
        assertThat(table).contains("+0 / -1");
    }

    @Test
    void fileDiff_extractsPerFileHunk() {
        String diff = """
                diff --git a/file1.java b/file1.java
                index abc..def 100644
                --- a/file1.java
                +++ b/file1.java
                @@ -1,3 +1,4 @@
                +added
                 existing
                diff --git a/file2.java b/file2.java
                index abc..def 100644
                --- a/file2.java
                +++ b/file2.java
                @@ -1,4 +1,3 @@
                 existing
                -removed
                """;

        DiffSummary summary = DiffSummary.parse(diff);

        String file1Diff = summary.fileDiff("file1.java");
        assertThat(file1Diff).isNotNull();
        assertThat(file1Diff).contains("+added");
        assertThat(file1Diff).doesNotContain("-removed");

        String file2Diff = summary.fileDiff("file2.java");
        assertThat(file2Diff).isNotNull();
        assertThat(file2Diff).contains("-removed");
        assertThat(file2Diff).doesNotContain("+added");
    }

    @Test
    void fileDiff_returnsNullForUnknownFile() {
        String diff = """
                diff --git a/file1.java b/file1.java
                index abc..def 100644
                --- a/file1.java
                +++ b/file1.java
                @@ -1,3 +1,4 @@
                +added
                 existing
                """;

        DiffSummary summary = DiffSummary.parse(diff);

        assertThat(summary.fileDiff("nonexistent.java")).isNull();
    }

    @Test
    void statLine_handlesPluralization() {
        String diff1 = """
                diff --git a/file.java b/file.java
                index abc..def 100644
                --- a/file.java
                +++ b/file.java
                @@ -1,3 +1,4 @@
                +added
                 existing
                """;

        DiffSummary summary1 = DiffSummary.parse(diff1);
        assertThat(summary1.statLine()).contains("1 file changed");
        assertThat(summary1.statLine()).contains("1 insertion(+)");

        String diff2 = """
                diff --git a/file1.java b/file1.java
                index abc..def 100644
                --- a/file1.java
                +++ b/file1.java
                @@ -1,3 +1,5 @@
                +added1
                +added2
                 existing
                """;

        DiffSummary summary2 = DiffSummary.parse(diff2);
        assertThat(summary2.statLine()).contains("1 file changed");
        assertThat(summary2.statLine()).contains("2 insertions(+)");
    }

    @Test
    void parse_handlesRename() {
        String diff = """
                diff --git a/oldname.java b/newname.java
                similarity index 95%
                rename from oldname.java
                rename to newname.java
                index abc..def 100644
                --- a/oldname.java
                +++ b/newname.java
                @@ -1,3 +1,4 @@
                +added
                 existing
                """;

        DiffSummary summary = DiffSummary.parse(diff);

        assertThat(summary.fileCount()).isEqualTo(1);
        assertThat(summary.changedFiles()).containsExactly("newname.java");
    }
}
