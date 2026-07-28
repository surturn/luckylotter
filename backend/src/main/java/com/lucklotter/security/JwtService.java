package com.lucklotter.security;

import com.lucklotter.domain.AdminUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies admin JWTs (FR-6, NFR-1).
 *
 * <p>The token carries the admin's ID and their business ID. Nothing else —
 * no name, no contact details — so a leaked or logged token exposes no PII
 * (NFR-4).
 */
@Service
public class JwtService {

    /** Business ID claim. Short key: tokens travel on every request. */
    private static final String CLAIM_BUSINESS_ID = "bid";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${lucklotter.security.jwt-secret}") String secret,
                      @Value("${lucklotter.security.jwt-ttl-minutes}") long ttlMinutes) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // HS256 needs >= 256 bits. Failing at startup beats issuing tokens
            // signed with a key too short to be worth signing with.
            throw new IllegalStateException(
                    "lucklotter.security.jwt-secret must be at least 32 characters (got " + keyBytes.length + ")");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public IssuedToken issue(AdminUser user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ttl);
        String token = Jwts.builder()
                .subject(user.getId().toString())
                .claim(CLAIM_BUSINESS_ID, user.getBusiness().getId().toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Verifies signature and expiry.
     *
     * @return the principal, or empty if the token is absent, malformed,
     *         expired, or signed with the wrong key — the caller cannot tell
     *         which, and shouldn't be able to
     */
    public Optional<AdminPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new AdminPrincipal(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(CLAIM_BUSINESS_ID, String.class)),
                    null));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
