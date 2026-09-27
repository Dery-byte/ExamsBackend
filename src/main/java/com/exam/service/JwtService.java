package com.exam.service;
//package com.exam.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    public static  final long JWT_TOKEN_VALIDITY=1000 * 60 * 70;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtService.class);

    /**
     * HMAC signing key, from the JWT_SECRET_KEY environment variable (base64, at least 32 bytes).
     * If it's missing a random key is generated at start-up, which is secure but signs everyone
     * out whenever the server restarts, so set the variable in production.
     */
    private final Key signingKey;

    public JwtService(@org.springframework.beans.factory.annotation.Value("${JWT_SECRET_KEY:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            log.warn("[JwtService] JWT_SECRET_KEY is not set: using a random key. Users will be signed out on every restart.");
            this.signingKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);
        } else {
            byte[] bytes = Decoders.BASE64.decode(configuredKey.trim());
            if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET_KEY must be at least 32 bytes (base64-encoded).");
            this.signingKey = Keys.hmacShaKeyFor(bytes);
        }
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(
            Map<String, Object> extraClaims,
            UserDetails userDetails
    ) {
        return Jwts
                .builder()
                .setClaims(extraClaims)
                .setSubject(userDetails.getUsername())
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + JWT_TOKEN_VALIDITY))
                .signWith(getSignInKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername())) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public Claims extractAllClaims(String token) {
        return Jwts
                .parserBuilder()
                .setSigningKey(getSignInKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key getSignInKey() {
        return signingKey;
    }
}
