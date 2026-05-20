package org.remus.giteabot.repository;

import java.util.Map;

/**
 * Provider-agnostic descriptor used by
 * {@link RepositoryApiClient#dispatchWorkflow(WorkflowDispatchRequest)} to
 * trigger a CI workflow run / pipeline on the Git host.
 *
 * <p>Semantics per provider:</p>
 * <ul>
 *     <li><b>GitHub Actions / Gitea Actions:</b> {@link #workflowRef()} is the
 *         workflow file name (e.g. {@code preview.yml}) and {@link #gitRef()}
 *         is the branch or fully qualified ref ({@code refs/heads/main} or
 *         {@code refs/pull/{n}/head}). {@link #inputs()} are passed verbatim
 *         as {@code workflow_dispatch} inputs.</li>
 *     <li><b>GitLab CI:</b> {@link #workflowRef()} is the trigger token (used
 *         as {@code token} on {@code POST /projects/:id/trigger/pipeline}) and
 *         {@link #gitRef()} is the pipeline {@code ref}. {@link #inputs()} are
 *         forwarded as {@code variables[KEY]=VALUE} form fields.</li>
 *     <li><b>Bitbucket Pipelines:</b> {@link #workflowRef()} is the custom
 *         pipeline {@code pattern} from {@code bitbucket-pipelines.yml} and
 *         {@link #gitRef()} is the branch name. {@link #inputs()} are sent as
 *         pipeline {@code variables}.</li>
 * </ul>
 *
 * @param owner       repository owner (or workspace/group for GitLab/Bitbucket)
 * @param repo        repository / project slug
 * @param workflowRef workflow file, trigger token or pipeline pattern (see above)
 * @param gitRef      branch or qualified Git ref to run against
 * @param inputs      free-form name/value map handed to the CI side
 */
public record WorkflowDispatchRequest(
        String owner,
        String repo,
        String workflowRef,
        String gitRef,
        Map<String, String> inputs) {

    public WorkflowDispatchRequest {
        if (inputs == null) {
            inputs = Map.of();
        } else {
            inputs = Map.copyOf(inputs);
        }
    }
}

