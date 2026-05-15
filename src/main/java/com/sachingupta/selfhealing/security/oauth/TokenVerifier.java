package com.sachingupta.selfhealing.security.oauth;

/**
 * Validates a wire token's signature, audience, scope, and expiry. Implementations may verify a
 * local HMAC signature ({@link HmacTokenVerifier}) or an RSA-signed JWT against a JWK source
 * ({@link JwtTokenVerifier}).
 */
public interface TokenVerifier {

    ApprovalToken verify(String wire, String expectedAudience, String expectedScope);
}
