package com.lucklotter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates a request from its {@code Authorization: Bearer <jwt>} header
 * (NFR-1).
 *
 * <p>A missing or invalid token leaves the context unauthenticated rather than
 * rejecting outright — the authorization rules in
 * {@link com.lucklotter.config.SecurityConfig} decide what that means for the
 * route, so the public endpoints stay reachable.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            jwtService.parse(header.substring(PREFIX.length()).trim()).ifPresent(principal -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
