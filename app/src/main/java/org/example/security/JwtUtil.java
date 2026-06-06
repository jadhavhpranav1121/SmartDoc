package org.example.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret:SmartDocAI_SuperSecret_JWT_Key_2024_Change_This}")
    private String secret;

    @Value("${jwt.expiration.ms:86400000}")
    private long expirationMs;

    // -----------------------------------------------------------------------
    // Token generation
    // -----------------------------------------------------------------------

    /**
     * Generates a signed JWT for the given username.
     */
    public String generateToken(String username) {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        Date now     = new Date();
        Date expires = new Date(now.getTime() + expirationMs);

        String token = JWT.create()
                .withSubject(username)
                .withIssuedAt(now)
                .withExpiresAt(expires)
                .sign(algorithm);

        log.info("Generated JWT for user '{}', expires at {}", username, expires);
        return token;
    }

    // -----------------------------------------------------------------------
    // Token validation
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the token is structurally valid, signed with the
     * correct secret, and not expired.
     */
    public boolean validateToken(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret);
            JWT.require(algorithm).build().verify(token);
            log.debug("JWT validated successfully.");
            return true;
        } catch (JWTVerificationException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Claims extraction
    // -----------------------------------------------------------------------

    /**
     * Extracts the subject (username) from a token without re-verifying the signature.
     * Always call {@link #validateToken} first.
     */
    public String extractUsername(String token) {
        return JWT.decode(token).getSubject();
    }
}
