package com.sachingupta.selfhealing.security.oauth;

import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Real OAuth 2.0–style {@link TokenVerifier}. Uses Nimbus to validate the JWT signature against the
 * RSA public key in the {@code JWKSource}, then explicitly checks the {@code aud} and {@code scp}
 * claims and the expiry. Same contract as the HMAC verifier; only the wire is changed.
 *
 * <p>Active when {@code selfhealing.oauth.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "selfhealing.oauth.enabled", havingValue = "true")
public class JwtTokenVerifier implements TokenVerifier {

    private final JwtDecoder jwtDecoder;

    public JwtTokenVerifier(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public ApprovalToken verify(String wire, String expectedAudience, String expectedScope) {
        if (wire == null || wire.isBlank()) {
            throw new TokenException("missing approval token");
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(wire);
        } catch (JwtException e) {
            throw new TokenException("JWT decode failed: " + e.getMessage());
        }

        List<String> audiences = jwt.getAudience();
        if (audiences == null || !audiences.contains(expectedAudience)) {
            throw new TokenException(
                    "wrong audience: token for "
                            + audiences
                            + ", server expects '"
                            + expectedAudience
                            + "'");
        }
        String scp = jwt.getClaim("scp");
        if (!expectedScope.equals(scp)) {
            throw new TokenException(
                    "wrong scope: token for '" + scp + "', server expects '" + expectedScope + "'");
        }
        Instant now = Instant.now();
        if (jwt.getExpiresAt() == null || now.isAfter(jwt.getExpiresAt())) {
            throw new TokenException("token expired");
        }
        return new ApprovalToken(
                jwt.getSubject(),
                expectedAudience,
                scp,
                jwt.getIssuedAt() == null ? now : jwt.getIssuedAt(),
                jwt.getExpiresAt());
    }
}
