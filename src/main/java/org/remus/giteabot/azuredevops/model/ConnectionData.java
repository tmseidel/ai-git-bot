package org.remus.giteabot.azuredevops.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * API response model for {@code _apis/connectionData}: the identity a token authenticates as.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionData {

    /** {@code null} when the token has no identity in the addressed organization. */
    private Identity authenticatedUser;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Identity {

        /** The same at account and organization level; upper or lower case depending on the API. */
        private String id;

        private String providerDisplayName;

        private Properties properties;

        /** Returns the {@code Account} property, or {@code null} when it is missing. */
        private String getAccountName() {
            return properties == null || properties.getAccount() == null ? null : properties.getAccount().getValue();
        }

        public String getDisplayName() {
            return getAccountName() != null ? getAccountName() : providerDisplayName;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Properties {

        /** The sign-in name on Azure DevOps Services; the account name without domain on Azure DevOps Server. */
        @JsonProperty("Account")
        private PropertyValue account;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PropertyValue {

        @JsonProperty("$value")
        private String value;
    }
}
