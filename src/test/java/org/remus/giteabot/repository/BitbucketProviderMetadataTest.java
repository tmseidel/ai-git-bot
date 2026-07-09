package org.remus.giteabot.repository;

import lombok.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.GitIntegration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class BitbucketProviderMetadataTest {

    private BitbucketProviderMetadata metadata;

    @BeforeEach
    void setUp() {
        metadata = new BitbucketProviderMetadata(builderProvider());
    }

    private static ObjectProvider<RestClient.Builder> builderProvider() {
        return new ObjectProvider<>() {
            @Override
            public @NonNull RestClient.Builder getObject() {
                return RestClient.builder();
            }
        };
    }

    @Test
    void getProviderType_returnsBitbucket() {
        assertThat(metadata.getProviderType()).isEqualTo(RepositoryType.BITBUCKET);
    }

    @Test
    void resolveApiUrl_publicBitbucket_convertsToApi() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://bitbucket.org");
        integration.setProviderType(RepositoryType.BITBUCKET);

        String apiUrl = metadata.resolveApiUrl(integration);

        assertThat(apiUrl).isEqualTo("https://api.bitbucket.org/2.0");
    }

    @Test
    void resolveApiUrl_alreadyApiUrl_unchanged() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://api.bitbucket.org/2.0");
        integration.setProviderType(RepositoryType.BITBUCKET);

        String apiUrl = metadata.resolveApiUrl(integration);

        assertThat(apiUrl).isEqualTo("https://api.bitbucket.org/2.0");
    }

    @Test
    void resolveApiUrl_selfHosted_addsRestApi() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://bitbucket.example.com");
        integration.setProviderType(RepositoryType.BITBUCKET);

        String apiUrl = metadata.resolveApiUrl(integration);

        assertThat(apiUrl).isEqualTo("https://bitbucket.example.com/rest/api/1.0");
    }

    @Test
    void resolveCloneUrl_publicBitbucketApi_convertsToWeb() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://api.bitbucket.org/2.0");
        integration.setProviderType(RepositoryType.BITBUCKET);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://bitbucket.org");
    }

    @Test
    void resolveCloneUrl_selfHostedApi_removesRestApi() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://bitbucket.example.com/rest/api/1.0");
        integration.setProviderType(RepositoryType.BITBUCKET);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://bitbucket.example.com");
    }

    @Test
    void resolveCloneUrl_regularUrl_unchanged() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://bitbucket.org");
        integration.setProviderType(RepositoryType.BITBUCKET);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://bitbucket.org");
    }

    @Test
    void buildAuthorizationHeader_apiToken_usesBearer() {
        String header = metadata.buildAuthorizationHeader("ATATT3xFfGF0CNndTrZZuJdJfXcmNmuF2RQK9fTUUTRhThM");

        assertThat(header).startsWith("Bearer ATATT");
    }

    @Test
    void buildAuthorizationHeader_appPassword_usesBasicAuth() {
        String header = metadata.buildAuthorizationHeader("username:app_password");

        // Base64 of "username:app_password" is "dXNlcm5hbWU6YXBwX3Bhc3N3b3Jk"
        assertThat(header).isEqualTo("Basic dXNlcm5hbWU6YXBwX3Bhc3N3b3Jk");
    }

    @Test
    void buildAuthorizationHeader_unknownFormat_usesBearer() {
        String header = metadata.buildAuthorizationHeader("someOtherToken123");

        assertThat(header).isEqualTo("Bearer someOtherToken123");
    }

    @Test
    void resolveApiUrl_nullUrl_returnsDefault() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl(null);
        integration.setProviderType(RepositoryType.BITBUCKET);

        String apiUrl = metadata.resolveApiUrl(integration);

        assertThat(apiUrl).isEqualTo("https://api.bitbucket.org/2.0");
    }

    @Test
    void resolveCloneUrl_nullUrl_returnsDefault() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl(null);
        integration.setProviderType(RepositoryType.BITBUCKET);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://bitbucket.org");
    }
}
