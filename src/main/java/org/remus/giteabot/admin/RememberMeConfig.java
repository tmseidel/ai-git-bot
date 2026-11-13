package org.remus.giteabot.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import javax.sql.DataSource;

/**
 * Persistent token store for the "remember me" checkbox on the native login form.
 *
 * <p>The {@code persistent_logins} table is created by Flyway
 * ({@code V2026_11_13_1200__persistent_logins.sql}), so the JDBC repository must not
 * create it on startup.</p>
 *
 * <p>This lives outside {@link SecurityConfig} because a {@code @WebMvcTest} slice
 * imports {@code SecurityConfig} without a {@link DataSource}; the native chain wires
 * remember-me only when this bean exists.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "giteabot.security", name = "login-method", havingValue = "native", matchIfMissing = true)
public class RememberMeConfig {

    @Bean
    public PersistentTokenRepository rememberMeTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }
}
