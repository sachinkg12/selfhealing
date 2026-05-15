package com.sachingupta.selfhealing.security.oauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Stands up the JWT signing+verification machinery used by {@link JwtTokenIssuer} and {@link
 * JwtTokenVerifier} when {@code selfhealing.oauth.enabled=true}.
 *
 * <p>A 2048-bit RSA key pair is generated at startup; the public half is exposed as a {@link
 * JWKSource} that both the encoder (issuer-side) and the decoder (verifier-side) consume. This is
 * the same shape Spring Authorization Server's in-process token endpoint uses internally, just
 * without the HTTP authorization endpoints — appropriate for a CLI agent rather than a hosted IdP.
 */
@Configuration
@ConditionalOnProperty(name = "selfhealing.oauth.enabled", havingValue = "true")
public class OAuthJwtConfig {

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            RSAKey rsaKey =
                    new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                            .privateKey((RSAPrivateKey) pair.getPrivate())
                            .keyID(UUID.randomUUID().toString())
                            .build();
            JWKSet jwkSet = new JWKSet(rsaKey);
            return new ImmutableJWKSet<>(jwkSet);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to generate RSA key pair: " + e.getMessage(), e);
        }
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwks) {
        return new NimbusJwtEncoder(jwks);
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwks) {
        try {
            RSAKey rsaKey =
                    (RSAKey)
                            jwks.get(
                                            new com.nimbusds.jose.jwk.JWKSelector(
                                                    new com.nimbusds.jose.jwk.JWKMatcher.Builder()
                                                            .build()),
                                            null)
                                    .get(0);
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to construct JwtDecoder from JWK source: " + e.getMessage(), e);
        }
    }
}
