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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Plays the identity provider in tests that go over real HTTP, without a network call.
 *
 * <p>In production the decoder fetches the issuer's public keys from its JWKS endpoint. Here the
 * test owns an RSA key pair: the decoder gets the public half and the test signs tokens with the
 * private half. Everything else (signature check, expiry, issuer) is the real validation code.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtConfiguration {

    public static final String ISSUER = "https://idp.test.kuropay.example";

    private static final KeyPair TRUSTED_KEYS = newRsaKeyPair();

    /** Replaces the auto-configured decoder, which backs off when a JwtDecoder bean exists. */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) TRUSTED_KEYS.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    /** A token the service must accept. */
    public static String validToken(String subject) {
        Instant now = Instant.now();
        return sign(TRUSTED_KEYS, subject, ISSUER, now, now.plusSeconds(300));
    }

    /** Correctly signed by the trusted issuer, but past its expiry (beyond the 60s clock skew). */
    public static String expiredToken(String subject) {
        Instant now = Instant.now();
        return sign(TRUSTED_KEYS, subject, ISSUER, now.minusSeconds(900), now.minusSeconds(600));
    }

    /** Right claims, wrong key: what an attacker forging a token would produce. */
    public static String tokenSignedByAnUntrustedKey(String subject) {
        Instant now = Instant.now();
        return sign(newRsaKeyPair(), subject, ISSUER, now, now.plusSeconds(300));
    }

    /** Validly signed by our key but claiming to come from someone else. */
    public static String tokenFromAnotherIssuer(String subject) {
        Instant now = Instant.now();
        return sign(TRUSTED_KEYS, subject, "https://someone-else.example", now, now.plusSeconds(300));
    }

    private static String sign(KeyPair keys, String subject, String issuer, Instant issuedAt, Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
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
