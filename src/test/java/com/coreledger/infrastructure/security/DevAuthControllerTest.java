package com.coreledger.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DevAuthControllerTest {

    private DevSecurityConfig devSecurityConfig;
    private DevAuthController controller;

    @BeforeEach
    void setUp() {
        devSecurityConfig = new DevSecurityConfig();
        controller = new DevAuthController(devSecurityConfig);
    }

    @Test
    @DisplayName("Gera token JWT válido decodificável pelo JwtDecoder local")
    void generatesValidJwtToken() {
        Map<String, Object> response = controller.token("user-test", "payment:write ledger:read", 12);

        assertThat(response).containsKey("access_token");
        assertThat(response.get("token_type")).isEqualTo("Bearer");
        assertThat(response.get("scope")).isEqualTo("payment:write ledger:read");

        String token = (String) response.get("access_token");

        JwtDecoder decoder = devSecurityConfig.devJwtDecoder();
        Jwt jwt = decoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("user-test");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("payment:write ledger:read");
        assertThat(jwt.getIssuer().toString()).isEqualTo("http://localhost:8080");
    }

    @Test
    @DisplayName("Expõe JWKS contendo chave pública RSA")
    void exposesJwks() {
        Map<String, Object> jwks = controller.jwks();

        assertThat(jwks).containsKey("keys");
        assertThat(jwks.get("keys")).isInstanceOf(java.util.List.class);
    }
}
