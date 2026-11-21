package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.OwnerRequiredException;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryUser;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.security.GeneralSecurityException;

/**
 * Tests a Git integration's connection by asking the provider which account the token
 * authenticates as.
 * <p>
 * The test runs against the values submitted in the form, so a configuration can be checked
 * before it is saved. The API client is built uncached and is discarded afterwards. When no new
 * token is submitted, the stored one is used, but only while provider and URL are unchanged: a
 * stored token is never sent to an endpoint it was not saved for.
 * <p>
 * Failures are reported with the provider's own answer (HTTP status and response body) or the
 * root cause of a connection error (TLS, DNS, refused connection), since a misconfigured URL,
 * proxy or certificate is just as likely as a wrong token.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitConnectionTestService {

    /** Longest response body or error text passed back to the form. */
    static final int MAX_DETAIL_LENGTH = 2000;

    private final GitIntegrationService gitIntegrationService;
    private final RepositoryPlatformFactory repositoryPlatformFactory;

    /**
     * Tests the submitted integration.
     *
     * @param submitted    the form values; only provider, URL and id are read
     * @param token        the token typed into the form, or blank to use the stored one
     * @param clearToken   whether the form requests removing the stored token
     * @param organization the Azure DevOps organization or collection to identify the token in,
     *                     or blank to take it from the URL; ignored by other providers. When the
     *                     provider needs one the URL does not name, the result
     *                     {@linkplain GitConnectionTestResult#organizationRequired() asks for one}
     */
    public GitConnectionTestResult test(GitIntegration submitted, String token, boolean clearToken,
                                        String organization) {
        if (submitted.getProviderType() == null) {
            return GitConnectionTestResult.failure("test.git.providerRequired", null);
        }
        GitIntegration candidate = new GitIntegration();
        candidate.setId(submitted.getId());
        candidate.setProviderType(submitted.getProviderType());
        candidate.setUrl(submitted.getUrl());
        gitIntegrationService.applyProviderDefaults(candidate);
        if (isBlank(candidate.getUrl())) {
            return GitConnectionTestResult.failure("test.git.urlRequired", null);
        }

        String plaintextToken;
        if (!isBlank(token)) {
            plaintextToken = token;
        } else {
            GitIntegration existing = clearToken || candidate.getId() == null ? null
                    : gitIntegrationService.findById(candidate.getId()).orElse(null);
            if (existing == null || isBlank(existing.getToken())) {
                return GitConnectionTestResult.failure("test.git.tokenRequired", null);
            }
            if (GitIntegrationService.endpointChanged(existing, candidate)) {
                return GitConnectionTestResult.failure("test.git.tokenRequiredForChangedEndpoint", null);
            }
            plaintextToken = gitIntegrationService.decryptToken(existing);
        }

        RepositoryApiClient client;
        try {
            client = repositoryPlatformFactory.createApiClient(candidate, plaintextToken);
        } catch (RuntimeException e) {
            return GitConnectionTestResult.failure("test.git.invalidUrl", describe(e), candidate.getUrl());
        }

        try {
            RepositoryUser user = client.getAuthenticatedUser(isBlank(organization) ? null : organization.trim());
            log.info("Connection test of {} integration at {} succeeded as '{}'",
                    candidate.getProviderType(), candidate.getUrl(), user.username());
            return GitConnectionTestResult.success(user);
        } catch (OwnerRequiredException e) {
            return GitConnectionTestResult.failure(GitConnectionTestResult.ORGANIZATION_REQUIRED, null);
        } catch (RuntimeException e) {
            log.info("Connection test of {} integration at {} failed: {}",
                    candidate.getProviderType(), candidate.getUrl(), describe(NestedExceptionUtils.getMostSpecificCause(e)));
            return toFailure(e, candidate.getUrl());
        }
    }

    private GitConnectionTestResult toFailure(RuntimeException e, String url) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            String key = switch (status) {
                case 401 -> "test.git.unauthorized";
                case 403 -> "test.git.forbidden";
                case 404 -> "test.git.notFound";
                default -> "test.git.httpError";
            };
            return GitConnectionTestResult.failure(key, httpDetail(status, response.getStatusText(),
                    response.getResponseBodyAsString()), status);
        }
        if (e instanceof UnknownContentTypeException unexpected) {
            // Typically an HTML login page or a proxy's error page instead of the provider's JSON.
            MediaType contentType = unexpected.getContentType();
            return GitConnectionTestResult.failure("test.git.unexpectedResponse",
                    httpDetail(unexpected.getStatusCode().value(), unexpected.getStatusText(),
                            unexpected.getResponseBodyAsString()),
                    unexpected.getStatusCode().value(), contentType == null ? "?" : contentType.toString());
        }
        if (e instanceof ResourceAccessException access) {
            // The detail names the cause (unknown host, refused connection, timeout, ...); only TLS
            // gets its own message, since its fix (the truststore) is not in the URL or network.
            String key = access.contains(SSLException.class) || access.contains(GeneralSecurityException.class)
                    ? "test.git.tlsFailed" : "test.git.connectFailed";
            return GitConnectionTestResult.failure(key, describe(access.getMostSpecificCause()), hostOf(url));
        }
        if (e instanceof RestClientException) {
            return GitConnectionTestResult.failure("test.git.unreadableResponse", describe(e));
        }
        return GitConnectionTestResult.failure("test.git.failed", describe(e));
    }

    private static String httpDetail(int status, String statusText, String body) {
        String line = "HTTP " + status + (isBlank(statusText) ? "" : " " + statusText);
        return truncate(isBlank(body) ? line : line + "\n\n" + body.strip());
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return truncate(t.getClass().getName() + (isBlank(message) ? "" : ": " + message));
    }

    private static String truncate(String text) {
        return text.length() <= MAX_DETAIL_LENGTH ? text : text.substring(0, MAX_DETAIL_LENGTH) + " …";
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
