package org.remus.giteabot.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Optional "skip-the-login" mode for e2e and demo environments.
 *
 * <p>Activated by the property {@code giteabot.security.auto-login.enabled=true}
 * (or env {@code GITEABOT_SECURITY_AUTO_LOGIN_ENABLED=true}). When enabled
 * this configuration:</p>
 *
 * <ol>
 *   <li>On startup, ensures an admin user with the configured username/password
 *       exists in the database (creates it if missing — uses
 *       {@link AdminService#createAdmin(String, String)}).</li>
 *   <li>Registers a servlet filter that, on every web request that is not
 *       already authenticated, pre-populates the {@link SecurityContext}
 *       with an {@code AUTHENTICATED} principal for the auto-login user so
 *       Spring Security's form login is bypassed entirely.</li>
 * </ol>
 *
 * <p><strong>Never enable this in production.</strong> It is a deliberate
 * authentication bypass intended for ephemeral preview / e2e environments
 * (see {@code .github/workflows/preview.yml}).</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "giteabot.security.auto-login", name = "enabled", havingValue = "true")
public class AutoLoginConfig {

    private final String username;
    private final String password;

    public AutoLoginConfig(
            @Value("${giteabot.security.auto-login.username:admin}") String username,
            @Value("${giteabot.security.auto-login.password:admin}") String password) {
        this.username = username;
        this.password = password;
        log.warn("⚠️  Auto-login mode is ENABLED (user '{}') — authentication is bypassed. "
                + "This must NEVER be used in production.", username);
    }

    /**
     * Bootstraps the auto-login admin account on startup. Idempotent: if the
     * user already exists nothing happens, so restarts and persistent
     * databases are supported.
     */
    @Bean
    public ApplicationRunner autoLoginBootstrap(AdminService adminService) {
        return args -> {
            if (adminService.findByUsername(username).isEmpty()) {
                adminService.createAdmin(username, password);
                log.info("Auto-login: created admin user '{}'", username);
            } else {
                log.debug("Auto-login: admin user '{}' already exists, skipping creation", username);
            }
        };
    }

    /**
     * Pre-authenticates every unauthenticated web request as the auto-login
     * admin user. Wired into the security chain by {@link SecurityConfig}.
     */
    @Bean
    public AutoLoginFilter autoLoginFilter() {
        return new AutoLoginFilter(username);
    }

    /**
     * Servlet filter that populates the Spring Security context with a
     * fixed admin principal when auto-login is active. Skips requests that
     * are already authenticated (so a real form login is still respected if
     * someone explicitly logs in) and skips the {@code /logout} endpoint
     * (otherwise Spring's logout handler would immediately re-authenticate
     * the user on the next request anyway — but at least the round-trip
     * works without a 403).
     */
    public static class AutoLoginFilter extends OncePerRequestFilter {

        private static final SecurityContextRepository CONTEXT_REPOSITORY =
                new HttpSessionSecurityContextRepository();

        private final String username;

        public AutoLoginFilter(String username) {
            this.username = username;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (SecurityContextHolder.getContext().getAuthentication() == null
                    || !SecurityContextHolder.getContext().getAuthentication().isAuthenticated()
                    || "anonymousUser".equals(SecurityContextHolder.getContext().getAuthentication().getName())) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        username,
                        "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);
                // Persist in the session so that CSRF / logout / etc. see the
                // same authentication across the request lifecycle.
                CONTEXT_REPOSITORY.saveContext(context, request, response);
            }
            filterChain.doFilter(request, response);
        }
    }
}


