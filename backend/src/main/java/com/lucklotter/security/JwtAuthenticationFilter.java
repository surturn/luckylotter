package com.lucklotter.security;

import com.lucklotter.domain.AdminUser;
import com.lucklotter.repo.AdminUserRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.Optional;

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

    /** The one route a temporary-password account may call. */
    static final String CHANGE_PASSWORD_PATH = "/v1/auth/change-password";

    private final JwtService jwtService;
    private final AdminUserRepository adminUsers;

    public JwtAuthenticationFilter(JwtService jwtService, AdminUserRepository adminUsers) {
        this.jwtService = jwtService;
        this.adminUsers = adminUsers;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<JwtService.VerifiedToken> verified =
                    jwtService.verify(header.substring(PREFIX.length()).trim());
            if (verified.isPresent()) {
                AdminUser user = adminUsers.findById(verified.get().principal().adminUserId())
                        .orElse(null);
                // A valid signature is not enough. The account can have been
                // deactivated, or its password changed or reset, since the
                // token was issued, and a stateless token can't know. This is
                // one primary-key read per request. Compared at second
                // precision because that is all a JWT's issue time carries.
                boolean stillValid = user != null
                        && user.isActive()
                        && !verified.get().issuedAt()
                                .isBefore(user.getPasswordChangedAt().truncatedTo(ChronoUnit.SECONDS));
                if (stillValid) {
                    // Request URI, not servlet path: under MockMvc the servlet
                    // path is empty, and a gate that only works in production
                    // can't be tested.
                    String path = request.getRequestURI().substring(request.getContextPath().length());
                    if (user.isMustChangePassword() && !CHANGE_PASSWORD_PATH.equals(path)) {
                        // Distinct from 401 so the client does not treat it as
                        // a dead session and sign the admin out.
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Password change required");
                        return;
                    }
                    var authentication = new UsernamePasswordAuthenticationToken(
                            verified.get().principal(), null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }
        chain.doFilter(request, response);
    }
}
