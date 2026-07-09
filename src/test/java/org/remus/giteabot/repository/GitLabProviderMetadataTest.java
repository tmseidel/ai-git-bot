package org.remus.giteabot.repository;

import lombok.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.GitIntegration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class GitLabProviderMetadataTest {

    private GitLabProviderMetadata metadata;

    @BeforeEach
    void setUp() {
        metadata = new GitLabProviderMetadata(builderProvider());
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
    void getProviderType_returnsGitLab() {
        assertThat(metadata.getProviderType()).isEqualTo(RepositoryType.GITLAB);
    }

    @Test
    void resolveApiUrl_returnsOriginalUrl() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitlab.example.com");
        integration.setProviderType(RepositoryType.GITLAB);

        String apiUrl = metadata.resolveApiUrl(integration);

        assertThat(apiUrl).isEqualTo("https://gitlab.example.com");
    }

    @Test
    void resolveCloneUrl_removesApiV4Suffix() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitlab.example.com/api/v4");
        integration.setProviderType(RepositoryType.GITLAB);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://gitlab.example.com");
    }

    @Test
    void resolveCloneUrl_regularUrl_unchanged() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitlab.example.com");
        integration.setProviderType(RepositoryType.GITLAB);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://gitlab.example.com");
    }

    @Test
    void resolveCloneUrl_blankUrl_returnsDefault() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("");
        integration.setProviderType(RepositoryType.GITLAB);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://gitlab.com");
    }

    @Test
    void buildAuthorizationHeader_returnsTokenDirectly() {
        // GitLab uses PRIVATE-TOKEN header, not Authorization
        String header = metadata.buildAuthorizationHeader("glpat-abc123");

        assertThat(header).isEqualTo("glpat-abc123");
    }
}
