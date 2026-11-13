package org.remus.giteabot.bitbucket.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * API response model for an e-mail address of the authenticated Bitbucket Cloud account.
 * Returned (paginated, see {@link Page}) by GET /user/emails
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BitbucketEmail {

    private String email;

    @JsonProperty("is_primary")
    private boolean primary;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Page {
        private List<BitbucketEmail> values;
    }
}
