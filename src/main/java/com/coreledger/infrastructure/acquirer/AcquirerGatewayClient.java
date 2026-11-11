package com.coreledger.infrastructure.acquirer;

import com.coreledger.domain.exception.AcquirerUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Cliente da adquirente externa protegido por Circuit Breaker + Retry (instância "acquirerGateway").
 * Falhas viram {@link AcquirerUnavailableException} (HTTP 503) e incrementam a métrica de fallback.
 */
@Component
public class AcquirerGatewayClient {

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    /** Referência ao próprio proxy para que as anotações Resilience4j sejam aplicadas. */
    private final AcquirerGatewayClient self;

    public AcquirerGatewayClient(@Value("${coreledger.acquirer.base-url}") String baseUrl,
                                 @Value("${coreledger.acquirer.connect-timeout:1s}") Duration connectTimeout,
                                 @Value("${coreledger.acquirer.read-timeout:2s}") Duration readTimeout,
                                 MeterRegistry meterRegistry,
                                 @Lazy AcquirerGatewayClient self) {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout).withReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
        this.meterRegistry = meterRegistry;
        this.self = self;
    }

    public void authorizeWithFallback(String reference, long amountCents) {
        try {
            self.authorize(reference, amountCents);
        } catch (Exception ex) {
            meterRegistry.counter("acquirer.fallback.total", "cause", ex.getClass().getSimpleName()).increment();
            throw new AcquirerUnavailableException("Adquirente indisponível, tente novamente mais tarde.", ex);
        }
    }

    @Retry(name = "acquirerGateway")
    @CircuitBreaker(name = "acquirerGateway")
    public void authorize(String reference, long amountCents) {
        restClient.post()
                .uri("/authorize")
                .body(Map.of("reference", reference, "amountCents", amountCents))
                .retrieve()
                .toBodilessEntity();
    }
}
