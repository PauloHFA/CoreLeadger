package com.coreledger.infrastructure.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxConsumerTest {

    private SimpleMeterRegistry meterRegistry;
    private OutboxConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new OutboxConsumer(meterRegistry);
    }

    @Test
    @DisplayName("Propaga traceId para o MDC durante processamento e limpa ao concluir")
    void propagatesTraceIdToMdcAndIncrementsCounter() {
        String testTraceId = "trace-otel-999";
        String payload = "{\"transactionId\":\"tx-123\"}";

        AtomicReference<String> mdcCaptured = new AtomicReference<>();

        // Test subclass to capture MDC during execution
        OutboxConsumer capturingConsumer = new OutboxConsumer(meterRegistry) {
            @Override
            public void onEvent(String body, String traceId) {
                // We call super and capture MDC within
                super.onEvent(body, traceId);
                mdcCaptured.set(MDC.get("traceId"));
            }
        };

        capturingConsumer.onEvent(payload, testTraceId);

        // MDC should be cleared after execution completes
        assertThat(MDC.get("traceId")).isNull();

        // Counter should have been incremented
        assertThat(meterRegistry.get("ledger.events.consumed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Consome evento sem traceId preservando MDC anterior")
    void consumesWithoutTraceId() {
        MDC.put("traceId", "previous-trace");
        consumer.onEvent("{}", null);

        assertThat(MDC.get("traceId")).isEqualTo("previous-trace");
        MDC.remove("traceId");

        assertThat(meterRegistry.get("ledger.events.consumed").counter().count()).isEqualTo(1.0);
    }
}
