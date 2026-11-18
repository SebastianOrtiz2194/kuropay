package com.kuropay.wallet.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Plays the identity provider in tests that go over real HTTP, without a network call.
 *
 * <p>In production the decoder fetches the issuer's public keys from its JWKS endpoint. Here the
 * test owns an RSA key pair: the decoder gets the public half and the test signs tokens with the
 * private half. Everything else (signature check, expiry, issuer, audience) is the real
 * validation code.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtConfiguration {

    public static final String ISSUER = "https://idp.test.kuropay.example";

    /** Who the tokens are for. Must match the audience the service is configured to accept. */
    public static final String AUDIENCE = "wallet-core-service";

    private static final KeyPair TRUSTED_KEYS = newRsaKeyPair();

    /**
     * Replaces the auto-configured decoder, which backs off when a JwtDecoder bean exists.
     *
     * <p>The accepted audience is read from the same property production uses, not copied here,
     * so the tests fail if application.yml stops requiring it or requires a different one.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.audiences}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) TRUSTED_KEYS.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                new JwtIssuerValidator(ISSUER), new JwtAudienceValidator(audience)));
        return decoder;
    }

    /** A token the service must accept. */
    public static String validToken(String subject) {
        Instant now = Instant.now();
        return sign(TRUSTED_KEYS, subject, ISSUER, AUDIENCE, now, now.plusSeconds(300));
    }

    /** Correctly signed by the trusted issuer, but past its expiry (beyond the 60s clock skew). */
    public static String expiredToken(String subject) {
        Instant now = Instant.now();
        return sign(TRUSTED_KEYS, subject, ISSUER, AUDIENCE, now.minusSeconds(900), now.minusSeconds(600));
    }

    /** Right claims, wrong key: what an attacker forging a token would produce. */
    public static String tokenSignedByAnUntrustedKey(String subject) {
        Instant now = Instant.now();
        return sign(newRsaKeyPair(), subject, ISSUER, AUDIENCE, now, now.plusSeconds(300));
    }

    /** Validly signed by our key but claiming to come from someone else. */
    public static String tokenFromAnotherIssuer(String subject) {
        Instant now = Instant.now();
        return sign(TRUSTED_KEYS, subject, "https://someone-else.example", AUDIENCE, now, now.plusSeconds(300));
    }

    /**
     * Genuine token from our own issuer, but minted for a different API. If another service ever
     * leaks or forwards its tokens, they must not work here.
     */
    public static String tokenForAnotherAudience(String subject) {
        Instant now = Instant.now();
        return sign(TRUSTED_KEYS, subject, ISSUER, "notification-service", now, now.plusSeconds(300));
    }

    private static String sign(
            KeyPair keys, String subject, String issuer, String audience, Instant issuedAt, Instant expiresAt
    ) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(keys.getPrivate()));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Could not sign test token", ex);
        }
        return jwt.serialize();
    }

    private static KeyPair newRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("RSA is required to exist on every JVM", ex);
        }
    }
}
