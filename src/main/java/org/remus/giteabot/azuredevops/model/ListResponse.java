package org.remus.giteabot.azuredevops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Value;

import java.util.List;

/** The {@code {"count": n, "value": [...]}} envelope Azure DevOps wraps every list response in. */
@Value
@JsonIgnoreProperties(ignoreUnknown = true)
public class ListResponse<T> {
    Long count;
    List<T> value;
}
