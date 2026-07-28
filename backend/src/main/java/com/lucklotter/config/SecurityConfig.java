package com.lucklotter.config;

import com.lucklotter.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless, JWT-bound security (NFR-1).
 *
 * <p>Everything is authenticated except login and the health/ping probes. The
 * default is deny: a new endpoint is protected unless it is deliberately added
 * to the public list below.
 *
 * <p>Authentication only establishes <em>who</em> is calling. Tenant scoping —
 * that a caller sees only their own business's data — is enforced separately in
 * the service layer from {@link com.lucklotter.security.AdminPrincipal}, because
 * a route pattern cannot express "this row belongs to you".
 */
@Configuration
public class SecurityConfig {

    /** Reachable without a token. Keep this list short and deliberate. */
    private static final String[] PUBLIC_PATHS = {
            "/v1/auth/login",
            "/actuator/health",
            "/api/ping"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless API, no cookies/session
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                // Default would be a 403 with an HTML login redirect; an API
                // client needs to tell "no token" from "wrong tenant".
                .authenticationEntryPoint((request, response, authException) ->
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required"))
                .accessDeniedHandler((request, response, deniedException) ->
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden"))
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
