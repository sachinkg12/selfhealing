package com.sachingupta.selfhealing.security.oauth;

import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithms;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Real OAuth 2.0–style {@link TokenIssuer}. Produces RSA-signed JWTs whose claims (sub, aud, scp,
 * iat, exp) are validated downstream by {@link JwtTokenVerifier} against the same JWK source.
 *
 * <p>Implements an OAuth 2.0 client-credentials flow with audiences scoped to one MCP server at a
 * time. The issuer runs in-process to keep the demo self-contained; the wire format and validation
 * rules are unchanged from a hosted authorization server.
 *
 * <p>Active when {@code selfhealing.oauth.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "selfhealing.oauth.enabled", havingValue = "true")
public class JwtTokenIssuer implements TokenIssuer {

    private final JwtEncoder jwtEncoder;

    public JwtTokenIssuer(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    @Override
    public ApprovalToken mint(String subject, String audience, String scope, long ttlSeconds) {
        Instant now = Instant.now();
        return new ApprovalToken(subject, audience, scope, now, now.plusSeconds(ttlSeconds));
    }

    @Override
    public String issue(ApprovalToken token) {
        JwsHeader header = JwsHeader.with(() -> JwsAlgorithms.RS256).build();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer("selfhealing")
                        .subject(token.subject())
                        .audience(List.of(token.audience()))
                        .claim("scp", token.scope())
                        .issuedAt(token.issuedAt())
                        .expiresAt(token.expiresAt())
                        .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
