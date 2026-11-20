package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.OwnerRequiredException;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.RepositoryUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.UnknownContentTypeException;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitConnectionTestServiceTest {

    private static final RepositoryUser BOT = new RepositoryUser("42", "ai_bot", "AI Bot", "bot@example.com");

    @Mock
    private GitIntegrationService gitIntegrationService;
    @Mock
    private RepositoryPlatformFactory repositoryPlatformFactory;
    @Mock
    private RepositoryApiClient client;

    private GitConnectionTestService service;

    @BeforeEach
    void setUp() {
        service = new GitConnectionTestService(gitIntegrationService, repositoryPlatformFactory);
        lenient().doCallRealMethod().when(gitIntegrationService).applyProviderDefaults(any());
    }

    @Test
    void reportsTheAccountTheSubmittedTokenAuthenticatesAs() {
        succeedAs(null, BOT);

        GitConnectionTestResult result = service.test(gitea("https://gitea.example.com "), "secret", false, null);

        assertThat(result.success()).isTrue();
        assertThat(result.messageKey()).isEqualTo("test.git.success");
        assertThat(result.user()).isEqualTo(BOT);
        ArgumentCaptor<GitIntegration> candidate = ArgumentCaptor.forClass(GitIntegration.class);
        verify(repositoryPlatformFactory).createApiClient(candidate.capture(), eq("secret"));
        assertThat(candidate.getValue().getUrl()).isEqualTo("https://gitea.example.com");
    }

    @Test
    void usesTheFixedUrlOfCloudOnlyProviders() {
        GitIntegration form = new GitIntegration();
        form.setProviderType(RepositoryType.GITHUB);
        form.setUrl("");
        succeedAs(null, BOT);

        assertThat(service.test(form, "secret", false, null).success()).isTrue();

        ArgumentCaptor<GitIntegration> candidate = ArgumentCaptor.forClass(GitIntegration.class);
        verify(repositoryPlatformFactory).createApiClient(candidate.capture(), anyString());
        assertThat(candidate.getValue().getUrl()).isEqualTo("https://github.com");
    }

    @Test
    void usesTheStoredTokenWhileProviderAndUrlAreUnchanged() {
        GitIntegration stored = gitea("https://gitea.example.com");
        stored.setId(7L);
        stored.setToken("ciphertext");
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(stored));
        when(gitIntegrationService.decryptToken(stored)).thenReturn("stored-secret");
        succeedAs(null, BOT);

        // A save trims the URL as well, so the padded form value is the stored endpoint.
        GitIntegration form = gitea("  https://gitea.example.com\n");
        form.setId(7L);
        GitConnectionTestResult result = service.test(form, "", false, null);

        assertThat(result.success()).isTrue();
        verify(repositoryPlatformFactory).createApiClient(any(), eq("stored-secret"));
    }

    @Test
    void neverSendsTheStoredTokenToAChangedUrl() {
        GitIntegration stored = gitea("https://gitea.example.com");
        stored.setId(7L);
        stored.setToken("ciphertext");
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(stored));

        GitIntegration form = gitea("https://attacker.example.com");
        form.setId(7L);
        GitConnectionTestResult result = service.test(form, null, false, null);

        assertThat(result.success()).isFalse();
        assertThat(result.messageKey()).isEqualTo("test.git.tokenRequiredForChangedEndpoint");
        verify(gitIntegrationService, never()).decryptToken(any());
        verifyNoInteractions(repositoryPlatformFactory);
    }

    @Test
    void requiresATokenWhenNoneIsStoredOrTheStoredOneIsBeingCleared() {
        assertThat(service.test(gitea("https://gitea.example.com"), " ", false, null).messageKey())
                .isEqualTo("test.git.tokenRequired");

        GitIntegration form = gitea("https://gitea.example.com");
        form.setId(7L);
        assertThat(service.test(form, null, true, null).messageKey()).isEqualTo("test.git.tokenRequired");
        verify(gitIntegrationService, never()).findById(any());
        verifyNoInteractions(repositoryPlatformFactory);
    }

    @Test
    void requiresProviderAndUrl() {
        GitIntegration noProvider = gitea("https://gitea.example.com");
        noProvider.setProviderType(null);
        assertThat(service.test(noProvider, "secret", false, null).messageKey())
                .isEqualTo("test.git.providerRequired");
        assertThat(service.test(gitea(" "), "secret", false, null).messageKey())
                .isEqualTo("test.git.urlRequired");
        verifyNoInteractions(repositoryPlatformFactory);
    }

    @Test
    void passesTheEnteredOrganizationTrimmedOrNoneWhenBlank() {
        succeedAs("fabrikam", BOT);
        assertThat(service.test(azure("https://dev.azure.com"), "pat", false, " fabrikam ").success()).isTrue();

        succeedAs(null, BOT);
        assertThat(service.test(azure("https://dev.azure.com/contoso"), "pat", false, " ").success()).isTrue();
    }

    @Test
    void asksForAnAzureDevopsOrganizationWhenTheProviderNeedsOne() {
        failWith(new OwnerRequiredException("no organization"));

        GitConnectionTestResult result = service.test(azure("https://dev.azure.com"), "pat", false, null);

        assertThat(result.messageKey()).isEqualTo("test.git.organizationRequired");
        assertThat(result.organizationRequired()).isTrue();
    }

    @Test
    void reportsARejectedTokenWithTheProvidersAnswer() {
        failWith(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                "{\"message\":\"Bad credentials\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        GitConnectionTestResult result = service.test(gitea("https://gitea.example.com"), "wrong", false, null);

        assertThat(result.success()).isFalse();
        assertThat(result.messageKey()).isEqualTo("test.git.unauthorized");
        assertThat(result.messageArgs()).containsExactly(401);
        assertThat(result.detail()).startsWith("HTTP 401 Unauthorized").contains("Bad credentials");
    }

    @Test
    void reportsAWrongUrlAsNotFound() {
        failWith(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8));

        GitConnectionTestResult result = service.test(gitea("https://gitea.example.com/org/repo"), "t", false, null);

        assertThat(result.messageKey()).isEqualTo("test.git.notFound");
        assertThat(result.detail()).isEqualTo("HTTP 404 Not Found");
    }

    @Test
    void reportsAnHtmlPageInsteadOfTheApi() {
        failWith(new UnknownContentTypeException(RepositoryUser.class, MediaType.TEXT_HTML,
                HttpStatusCode.valueOf(203), "Non-Authoritative Information", HttpHeaders.EMPTY,
                "<html>Sign in</html>".getBytes(StandardCharsets.UTF_8)));

        GitConnectionTestResult result = service.test(gitea("https://gitea.example.com"), "t", false, null);

        assertThat(result.messageKey()).isEqualTo("test.git.unexpectedResponse");
        assertThat(result.messageArgs()).containsExactly(203, "text/html");
        assertThat(result.detail()).contains("<html>Sign in</html>");
    }

    @Test
    void reportsAnUntrustedCertificateWithItsCause() {
        failWith(new ResourceAccessException("I/O error on GET request",
                new SSLHandshakeException("PKIX path building failed")));

        GitConnectionTestResult result = service.test(gitea("https://gitea.internal:8443"), "t", false, null);

        assertThat(result.messageKey()).isEqualTo("test.git.tlsFailed");
        assertThat(result.messageArgs()).containsExactly("gitea.internal");
        assertThat(result.detail()).isEqualTo("javax.net.ssl.SSLHandshakeException: PKIX path building failed");
    }

    @Test
    void reportsConnectionFailuresWithTheirRootCause() {
        failWith(new ResourceAccessException("I/O error", new UnknownHostException("gitea.example.com")));
        GitConnectionTestResult result = service.test(gitea("https://gitea.example.com"), "t", false, null);
        assertThat(result.messageKey()).isEqualTo("test.git.connectFailed");
        assertThat(result.messageArgs()).containsExactly("gitea.example.com");
        assertThat(result.detail()).isEqualTo("java.net.UnknownHostException: gitea.example.com");

        failWith(new ResourceAccessException("I/O error",
                new IOException("request failed", new SocketTimeoutException("Read timed out"))));
        result = service.test(gitea("https://gitea.example.com"), "t", false, null);
        assertThat(result.messageKey()).isEqualTo("test.git.connectFailed");
        assertThat(result.detail()).isEqualTo("java.net.SocketTimeoutException: Read timed out");
    }

    @Test
    void truncatesLongResponseBodies() {
        failWith(HttpClientErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY,
                "x".repeat(10_000).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        GitConnectionTestResult result = service.test(gitea("https://gitea.example.com"), "t", false, null);

        assertThat(result.messageKey()).isEqualTo("test.git.httpError");
        assertThat(result.detail()).hasSizeLessThanOrEqualTo(GitConnectionTestService.MAX_DETAIL_LENGTH + 2);
    }

    @Test
    void reportsAnUnusableUrl() {
        when(repositoryPlatformFactory.createApiClient(any(), anyString()))
                .thenThrow(new IllegalArgumentException("Illegal character in authority"));

        GitConnectionTestResult result = service.test(gitea("https://gitea example.com"), "t", false, null);

        assertThat(result.messageKey()).isEqualTo("test.git.invalidUrl");
        assertThat(result.messageArgs()).isEqualTo(List.of("https://gitea example.com"));
    }

    private void succeedAs(String owner, RepositoryUser user) {
        when(repositoryPlatformFactory.createApiClient(any(), anyString())).thenReturn(client);
        when(client.getAuthenticatedUser(owner)).thenReturn(user);
    }

    private void failWith(RuntimeException e) {
        when(repositoryPlatformFactory.createApiClient(any(), anyString())).thenReturn(client);
        doThrow(e).when(client).getAuthenticatedUser(null);
    }

    private static GitIntegration gitea(String url) {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITEA);
        integration.setUrl(url);
        return integration;
    }

    private static GitIntegration azure(String url) {
        GitIntegration integration = gitea(url);
        integration.setProviderType(RepositoryType.AZURE_DEVOPS);
        return integration;
    }
}
