package com.coreledger.infrastructure.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "coreledger.dev-auth.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Autenticação (Desenvolvimento)", description = "Geração de tokens JWT locais para testes no Swagger")
public class DevAuthController {

    private final DevSecurityConfig devSecurityConfig;

    public DevAuthController(DevSecurityConfig devSecurityConfig) {
        this.devSecurityConfig = devSecurityConfig;
    }

    @GetMapping("/token")
    @Operation(summary = "Emite um token JWT assinado para testes",
            description = "Gera um token Bearer RSA256 válido contendo os escopos desejados. Use no botão Authorize do Swagger UI.")
    public Map<String, Object> token(
            @Parameter(description = "Identificador do usuário", example = "operador-financeiro")
            @RequestParam(defaultValue = "operador-financeiro") String subject,
            @Parameter(description = "Escopos OAuth2 separados por espaço", example = "payment:write ledger:read")
            @RequestParam(defaultValue = "payment:write ledger:read") String scope,
            @Parameter(description = "Validade do token em horas", example = "24")
            @RequestParam(defaultValue = "24") int expirationHours) {
        String token = devSecurityConfig.generateToken(subject, scope, expirationHours);
        return Map.of(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in_hours", expirationHours,
                "scope", scope
        );
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Expõe as chaves públicas em formato JWKS")
    public Map<String, Object> jwks() {
        return devSecurityConfig.getJwkSet().toJSONObject();
    }
}
