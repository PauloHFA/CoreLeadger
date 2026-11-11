package com.coreledger.infrastructure.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Provedor de chaves RSA locais e JwtDecoder para desenvolvimento e testes locais,
 * dispensando a necessidade de um servidor OAuth2 externo (ex: Keycloak) ativo.
 */
@Configuration
@ConditionalOnProperty(name = "coreledger.dev-auth.enabled", havingValue = "true", matchIfMissing = true)
public class DevSecurityConfig {

    private final RSAKey rsaJwk;
    private final KeyPair keyPair;

    public DevSecurityConfig() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            this.keyPair = gen.generateKeyPair();
            this.rsaJwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID("coreledger-dev-key")
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Falha ao inicializar chaves RSA de desenvolvimento", e);
        }
    }

    @Bean
    @Primary
    public JwtDecoder devJwtDecoder() {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
    }

    public String generateToken(String subject, String scope, int expirationHours) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject != null ? subject : "dev-user")
                    .issuer("http://localhost:8080")
                    .jwtID(UUID.randomUUID().toString())
                    .claim("scope", scope != null ? scope : "payment:write ledger:read")
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(expirationHours, ChronoUnit.HOURS)))
                    .build();

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(rsaJwk.getKeyID())
                    .build();

            SignedJWT signedJWT = new SignedJWT(header, claims);
            signedJWT.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Erro ao assinar token JWT de desenvolvimento", e);
        }
    }

    public JWKSet getJwkSet() {
        return new JWKSet(rsaJwk.toPublicJWK());
    }
}
