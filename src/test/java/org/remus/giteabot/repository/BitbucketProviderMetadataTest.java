package org.remus.giteabot.repository;

import lombok.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "account@example.com")
    void apiToken_preservesRestAuthenticationAndUsesSupportedGitUsername(String username) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        metadata = new BitbucketProviderMetadata(new ObjectProvider<>() {
            @Override
            public @NonNull RestClient.Builder getObject() {
                return builder;
            }
        });
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://bitbucket.org");
        integration.setUsername(username);
        String token = "ATATT-test-api-token";
        RepositoryCredentials credentials = metadata.createCredentials(integration, token);
        String expectedHeader = credentials.hasUsername()
                ? "Basic " + Base64.getEncoder().encodeToString((username + ":" + token).getBytes(StandardCharsets.UTF_8))
                : "Bearer " + token;
        server.expect(requestTo("https://api.bitbucket.org/2.0/repositories/workspace/repo/pullrequests/1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", expectedHeader))
                .andRespond(withSuccess("{\"state\":\"OPEN\"}", MediaType.APPLICATION_JSON));

        RepositoryApiClient client = metadata.createClient(metadata.buildRestClient(integration, token), credentials);

        assertThat(client.isPullRequestOpen("workspace", "repo", 1L)).isTrue();
        server.verify();
        assertThat(client.getCredentials().username()).isEqualTo("x-token-auth");
        assertThat(client.getCredentials().token()).isEqualTo(token);
        assertThat(credentials.username()).isEqualTo(username);
    }

    @Test
    void apiToken_preservesSshCredentials() {
        RepositoryCredentials credentials = RepositoryCredentials.of(
                "https://api.bitbucket.org/2.0", "https://bitbucket.org", "account@example.com", "ATATT-test-token")
                .withSsh("private-key", "known-hosts");

        RepositoryApiClient client = metadata.createClient(null, credentials);

        assertThat(client.getCredentials()).isSameAs(credentials);
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
