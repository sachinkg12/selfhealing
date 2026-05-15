package com.sachingupta.selfhealing.security.oauth;

import java.time.Instant;

/**
 * Short-lived approval token. Audience is the MCP server URI, scope is the service namespace.
 * Follows the OAuth 2.0 client-credentials pattern (RFC 6749).
 */
public record ApprovalToken(
        String subject, String audience, String scope, Instant issuedAt, Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
