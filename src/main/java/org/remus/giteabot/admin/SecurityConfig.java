package org.remus.giteabot.admin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;

import java.util.LinkedHashSet;
import java.util.Set;

@Configuration
public class SecurityConfig {

    @Bean
    @ConditionalOnProperty(prefix = "giteabot.security", name = "login-method", havingValue = "native", matchIfMissing = true)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnProperty(prefix = "giteabot.security", name = "login-method", havingValue = "native", matchIfMissing = true)
    public UserDetailsService userDetailsService(AdminUserRepository adminUserRepository) {
        return username -> adminUserRepository.findByUsername(username)
                .map(admin -> User.builder()
                        .username(admin.getUsername())
                        .password(admin.getPasswordHash())
                        .roles("ADMIN")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    /**
     * Security filter chain for API/webhook endpoints - no authentication required.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) {
        http
                .securityMatcher("/api/**", "/actuator/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Security filter chain for the web UI - requires authentication.
     */
    @Bean(name = "nativeWebSecurityFilterChain")
    @Order(2)
    @ConditionalOnProperty(prefix = "giteabot.security", name = "login-method", havingValue = "native", matchIfMissing = true)
    public SecurityFilterChain nativeWebSecurityFilterChain(HttpSecurity http,
                                                            ObjectProvider<AutoLoginConfig.AutoLoginFilter> autoLoginFilter) {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/setup", "/setup/**", "/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/dashboard", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );
        // When the auto-login profile is active (giteabot.security.auto-login.enabled=true)
        // an AutoLoginFilter bean is published — insert it before the form-login filter
        // so every request is pre-authenticated and the form is never shown.
        AutoLoginConfig.AutoLoginFilter filter = autoLoginFilter.getIfAvailable();
        if (filter != null) {
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    /**
     * Web UI chain for OAuth mode. An unauthenticated request is redirected directly
     * to the provider's authorization endpoint; the native login page is not exposed.
     */
    @Bean(name = "oauthWebSecurityFilterChain")
    @Order(2)
    @ConditionalOnProperty(prefix = "giteabot.security", name = "login-method", havingValue = "oauth")
    public SecurityFilterChain oauthWebSecurityFilterChain(HttpSecurity http,
                                                           ObjectProvider<AutoLoginConfig.AutoLoginFilter> autoLoginFilter,
                                                           ObjectProvider<OAuthDebugLoggingFilter> oauthDebugLoggingFilter) {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico",
                                "/oauth2/**", "/login/oauth2/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth -> oauth
                        .defaultSuccessUrl("/dashboard", true)
                        .failureUrl("/oauth2/error")
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(authorities -> {
                            Set<GrantedAuthority> granted = new LinkedHashSet<>(authorities);
                            granted.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                            return granted;
                        }))
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint(
                                "/oauth2/authorization/" + OAuthLoginConfiguration.REGISTRATION_ID))
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/")
                        .permitAll()
                );

        AutoLoginConfig.AutoLoginFilter filter = autoLoginFilter.getIfAvailable();
        if (filter != null) {
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        }
        OAuthDebugLoggingFilter debugFilter = oauthDebugLoggingFilter.getIfAvailable();
        if (debugFilter != null) {
            http.addFilterBefore(debugFilter, OAuth2AuthorizationRequestRedirectFilter.class);
        }
        return http.build();
    }
}
