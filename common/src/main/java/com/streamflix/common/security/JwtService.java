package com.streamflix.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

/**
 * Stateless HMAC-signed JWT issuance and validation, shared by user-service (issuer),
 * the API gateway and the downstream services (validators).
 *
 * <p>Because every service validates the same signed token with the same shared secret,
 * no service holds session state — any replica can serve any request, which is what makes
 * the fleet horizontally scalable.</p>
 */
public class JwtService {

    private final SecretKey key;
    private final long expirationMillis;

    public JwtService(String secret, long expirationMillis) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMillis = expirationMillis;
    }

    /** Issue a signed token whose subject is the user id, carrying the email as a claim. */
    public String generateToken(Long userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of("email", email))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMillis)))
                .signWith(key)
                .compact();
    }

    /** Parse and verify a token, returning its claims. Throws JwtException if invalid/expired. */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }
}
