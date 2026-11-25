package org.remus.giteabot.workflowrun;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRunFilterFormTest {

    private static WorkflowRunFilterForm form() {
        WorkflowRunFilterForm form = new WorkflowRunFilterForm();
        form.setProject("group/sub/app");
        form.setState("error");
        form.setFailedCalls(true);
        form.setFrom("2026-11-20T08:30");
        form.setTz("Europe/Berlin");
        form.setPage(3);
        form.setSize(50);
        return form;
    }

    @Test
    void linksChangeOneParameterKeepTheRestAndGoBackToTheFirstPage() {
        WorkflowRunFilterForm form = form();

        assertThat(form.with("state", "RUNNING")).isEqualTo("project=group%2Fsub%2Fapp&state=RUNNING"
                + "&failedCalls=true&from=2026-11-20T08%3A30&tz=Europe%2FBerlin&size=50&dir=desc");
        assertThat(form.with("page", 4)).contains("page=4").contains("state=ERROR");
        assertThat(form.with("state", null)).doesNotContain("state=").doesNotContain("page=");
    }

    /** A plain "+" in the query is read as a space, so it must be sent as %2B. */
    @Test
    void linksEncodeAPlusSoItSurvivesTheRoundTrip() {
        WorkflowRunFilterForm form = new WorkflowRunFilterForm();
        form.setTz("Etc/GMT+1");
        form.setProject("org/c++ tools");

        assertThat(form.with("page", 2))
                .contains("tz=Etc%2FGMT%2B1")
                .contains("project=org%2Fc%2B%2B+tools");
    }

    @Test
    void hiddenFieldsLeaveOutTheChangedParametersAndEmptyOnes() {
        assertThat(form().paramsWithout("size", "page")).containsOnlyKeys(
                "project", "state", "failedCalls", "from", "tz", "dir");
    }

    @Test
    void filterReadsDatesInTheBrowsersZoneAndIgnoresUnknownValues() {
        WorkflowRunFilter filter = form().toFilter();
        assertThat(filter.state()).isEqualTo(RunStatus.ERROR);
        assertThat(filter.startedFrom()).isEqualTo(Instant.parse("2026-11-20T07:30:00Z"));

        WorkflowRunFilterForm bogus = new WorkflowRunFilterForm();
        bogus.setState("bogus");
        bogus.setFrom("yesterday");
        assertThat(bogus.toFilter().state()).isNull();
        assertThat(bogus.toFilter().startedFrom()).isNull();
    }
}
