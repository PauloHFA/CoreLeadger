package com.coreledger.infrastructure.acquirer;

import com.coreledger.domain.exception.AcquirerUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AcquirerGatewayClientTest {

    private SimpleMeterRegistry meterRegistry;
    private AcquirerGatewayClient proxyMock;
    private AcquirerGatewayClient client;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        proxyMock = mock(AcquirerGatewayClient.class);
        client = new AcquirerGatewayClient(
                "http://localhost:9999",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                meterRegistry,
                proxyMock
        );
    }

    @Test
    @DisplayName("Executa com sucesso sem acionar fallback")
    void authorizeSuccess() {
        doNothing().when(proxyMock).authorize("ref-1", 5000L);

        client.authorizeWithFallback("ref-1", 5000L);

        verify(proxyMock).authorize("ref-1", 5000L);
        assertThat(meterRegistry.find("acquirer.fallback.total").counter()).isNull();
    }

    @Test
    @DisplayName("Aciona fallback e incrementa métrica quando adquirente falha")
    void authorizeFailsTriggersFallback() {
        doThrow(new RuntimeException("Connection Timeout")).when(proxyMock).authorize("ref-2", 10000L);

        assertThatThrownBy(() -> client.authorizeWithFallback("ref-2", 10000L))
                .isInstanceOf(AcquirerUnavailableException.class)
                .hasMessageContaining("Adquirente indisponível");

        assertThat(meterRegistry.get("acquirer.fallback.total").tag("cause", "RuntimeException").counter().count())
                .isEqualTo(1.0);
    }
}
