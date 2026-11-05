package org.remus.giteabot.azuredevops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Value;

// https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-iterations/list?view=azure-devops-rest-6.0&tabs=HTTP#gitcommitref
@Value
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitCommitRef {
    String commitId;
}
