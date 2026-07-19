package org.remus.giteabot.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Opt-in diagnostics for the application's OAuth request flow.
 *
 * <p>This deliberately logs only application-side OAuth endpoints, not outbound
 * token or user-info responses. OAuth codes, tokens, cookies, secrets, and redirect
 * locations are redacted before logging.</p>
 */
@Slf4j
public class OAuthDebugLoggingFilter extends OncePerRequestFilter {

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-api-key", "client-secret");
    private static final Set<String> SENSITIVE_PARAMETERS = Set.of(
            "code", "state", "error", "error_description", "access_token", "id_token", "refresh_token", "token");

    private final int maxBodyLength;

    public OAuthDebugLoggingFilter(int maxBodyLength) {
        this.maxBodyLength = Math.max(0, maxBodyLength);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/oauth2/") || path.startsWith("/login/oauth2/"));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,@NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!log.isDebugEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, maxBodyLength);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            log.debug("OAuth HTTP {} {} requestHeaders={} requestBody={} -> status={} responseHeaders={} responseBody={}",
                    request.getMethod(), safeUri(request), headers(request),
                    body(requestWrapper.getContentAsByteArray(), request.getCharacterEncoding(), request.getContentType()),
                    responseWrapper.getStatus(), headers(responseWrapper),
                    body(responseWrapper.getContentAsByteArray(), responseWrapper.getCharacterEncoding(), responseWrapper.getContentType()));
            responseWrapper.copyBodyToResponse();
        }
    }

    private String safeUri(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return request.getRequestURI();
        }
        StringBuilder safeQuery = new StringBuilder();
        for (String part : query.split("&")) {
            if (!safeQuery.isEmpty()) {
                safeQuery.append('&');
            }
            int separator = part.indexOf('=');
            String name = separator < 0 ? part : part.substring(0, separator);
            safeQuery.append(name);
            if (separator >= 0) {
                safeQuery.append('=').append(isSensitiveParameter(name) ? "<redacted>" : part.substring(separator + 1));
            }
        }
        return request.getRequestURI() + '?' + safeQuery;
    }

    private Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        for (String name : Collections.list(names)) {
            result.put(name, isSensitiveHeader(name) ? "<redacted>" : request.getHeader(name));
        }
        return result;
    }

    private Map<String, String> headers(HttpServletResponse response) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            result.put(name, isSensitiveHeader(name) || "location".equalsIgnoreCase(name)
                    ? "<redacted>" : response.getHeader(name));
        }
        return result;
    }

    private String body(byte[] content, String encoding, String contentType) {
        if (content.length == 0) {
            return "";
        }
        MediaType mediaType;
        try {
            mediaType = contentType == null ? null : MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            return "<unparseable content type; " + content.length + " bytes>";
        }
        if (mediaType != null && !(mediaType.isCompatibleWith(MediaType.TEXT_PLAIN)
                || mediaType.isCompatibleWith(MediaType.TEXT_HTML)
                || mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)
                || mediaType.isCompatibleWith(MediaType.APPLICATION_XML)
                || mediaType.getSubtype().endsWith("+json") || mediaType.getSubtype().endsWith("+xml"))) {
            return "<non-text response; " + content.length + " bytes>";
        }
        Charset charset;
        try {
            charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        } catch (Exception ex) {
            charset = StandardCharsets.UTF_8;
        }
        int length = Math.min(content.length, maxBodyLength);
        String result = new String(content, 0, length, charset);
        if (content.length > maxBodyLength) {
            result += "... <truncated; " + content.length + " bytes total>";
        }
        return redactBody(result);
    }

    private boolean isSensitiveHeader(String name) {
        String normalized = name.toLowerCase();
        return SENSITIVE_HEADERS.contains(normalized) || normalized.contains("authorization")
                || normalized.contains("token") || normalized.contains("secret");
    }

    private boolean isSensitiveParameter(String name) {
        return SENSITIVE_PARAMETERS.contains(name.toLowerCase());
    }

    private String redactBody(String value) {
        return value.replaceAll(
                "(?i)(\"?(?:code|state|access_token|id_token|refresh_token|client_secret)\"?\\s*[=:]\\s*)(?:\"[^\"]*\"|[^&\\s<,}]+)",
                "$1<redacted>");
    }
}
