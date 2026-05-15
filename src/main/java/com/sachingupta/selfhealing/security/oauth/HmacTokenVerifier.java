package com.sachingupta.selfhealing.security.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Verifies HMAC-signed wire tokens produced by {@link HmacTokenIssuer}. Used as the default when
 * {@code selfhealing.oauth.enabled} is missing or {@code false}.
 */
@Component
@ConditionalOnProperty(
        name = "selfhealing.oauth.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class HmacTokenVerifier implements TokenVerifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ApprovalToken verify(String wire, String expectedAudience, String expectedScope) {
        if (wire == null || wire.isBlank()) {
            throw new TokenException("missing approval token");
        }
        String[] parts = wire.split("\\.");
        if (parts.length != 2) {
            throw new TokenException("malformed approval token");
        }
        String body = parts[0];
        String signature = parts[1];
        String expectedSignature = HmacTokenIssuer.sign(body);
        if (!MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new TokenException("token signature mismatch");
        }
        try {
            Map<?, ?> payload = MAPPER.readValue(Base64.getUrlDecoder().decode(body), Map.class);
            String aud = (String) payload.get("aud");
            String scp = (String) payload.get("scp");
            long exp = ((Number) payload.get("exp")).longValue();
            if (!expectedAudience.equals(aud)) {
                throw new TokenException(
                        "wrong audience: token for '"
                                + aud
                                + "', server expects '"
                                + expectedAudience
                                + "'");
            }
            if (!expectedScope.equals(scp)) {
                throw new TokenException(
                        "wrong scope: token for '"
                                + scp
                                + "', server expects '"
                                + expectedScope
                                + "'");
            }
            Instant now = Instant.now();
            Instant expiresAt = Instant.ofEpochSecond(exp);
            if (now.isAfter(expiresAt)) {
                throw new TokenException("token expired");
            }
            return new ApprovalToken(
                    (String) payload.get("sub"),
                    aud,
                    scp,
                    Instant.ofEpochSecond(((Number) payload.get("iat")).longValue()),
                    expiresAt);
        } catch (TokenException e) {
            throw e;
        } catch (Exception e) {
            throw new TokenException("token decode failed: " + e.getMessage());
        }
    }
}
