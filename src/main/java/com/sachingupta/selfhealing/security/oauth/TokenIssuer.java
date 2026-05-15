package com.sachingupta.selfhealing.security.oauth;

/**
 * Mints short-lived approval tokens. Implementations may use a local HMAC ({@link HmacTokenIssuer})
 * or a real OAuth 2.0 issuer ({@link JwtTokenIssuer}). The selection is controlled by {@code
 * selfhealing.oauth.enabled}.
 */
public interface TokenIssuer {

    /** Construct an in-memory {@link ApprovalToken} with the given fields and the current clock. */
    ApprovalToken mint(String subject, String audience, String scope, long ttlSeconds);

    /** Sign and serialize the given token to its wire representation. */
    String issue(ApprovalToken token);
}
