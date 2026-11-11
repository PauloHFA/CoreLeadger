package com.coreledger.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Respostas 401/403 no formato RFC 7807. */
@Component
public class ProblemDetailSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemDetailSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(response, ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Token de acesso ausente ou inválido."));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Escopo insuficiente."));
    }

    private void write(HttpServletResponse response, ProblemDetail body) throws IOException {
        response.setStatus(body.getStatus());
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
