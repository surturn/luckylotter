package com.lucklotter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline security wiring. Stateless (JWT-bound, FR-4) — the JWT auth filter
 * and role-based authorization rules are added with the accounts/auth work.
 * For now the public health check and ping are open; everything else will be
 * locked down once authentication exists.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless API, no cookies/session
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/api/ping").permitAll()
                // TODO(NFR-1): every endpoint is PUBLIC until the JWT filter
                // lands. Tracked as a hard gate in PRD-TODOS.md § "M4 — Pilot
                // readiness / Hard gates" — this must be authenticated() before
                // any pilot data exists. Do not remove this comment without
                // closing that gate.
                .anyRequest().permitAll()
            );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
