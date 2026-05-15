package com.sachingupta.selfhealing.security.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local HMAC-signed {@link TokenIssuer}. Used as the default when {@code selfhealing.oauth.enabled}
 * is missing or {@code false}. Self-contained: no external issuer required. {@link JwtTokenIssuer}
 * replaces this bean in real-OAuth mode.
 */
@Component
@ConditionalOnProperty(
        name = "selfhealing.oauth.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class HmacTokenIssuer implements TokenIssuer {

    static final byte[] SECRET = "selfhealing-demo-secret".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ApprovalToken mint(String subject, String audience, String scope, long ttlSeconds) {
        Instant now = Instant.now();
        return new ApprovalToken(subject, audience, scope, now, now.plusSeconds(ttlSeconds));
    }

    @Override
    public String issue(ApprovalToken token) {
        try {
            Map<String, Object> payload =
                    Map.of(
                            "sub", token.subject(),
                            "aud", token.audience(),
                            "scp", token.scope(),
                            "iat", token.issuedAt().getEpochSecond(),
                            "exp", token.expiresAt().getEpochSecond());
            String body =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(MAPPER.writeValueAsBytes(payload));
            String signature = sign(body);
            return body + "." + signature;
        } catch (Exception e) {
            throw new TokenException("failed to issue token: " + e.getMessage());
        }
    }

    static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new TokenException("HMAC failure: " + e.getMessage());
        }
    }
}
