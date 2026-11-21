package org.remus.giteabot.azuredevops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

// https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-6.0&tabs=HTTP#identityref
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IdentityRef {
    /** The identity GUID, the same in every organization. */
    private String id;
}
