package com.coreledger.infrastructure.outbox;

import com.coreledger.domain.model.OutboxEvent;
import com.coreledger.domain.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {

    private OutboxRepository outboxRepository;
    private RabbitTemplate rabbitTemplate;
    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(OutboxRepository.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();

        outboxPublisher = new OutboxPublisher(
                outboxRepository,
                rabbitTemplate,
                objectMapper,
                meterRegistry,
                10,
                3 // maxRetries
        );
    }

    @Test
    @DisplayName("Publica eventos pendentes com sucesso e marca como PROCESSED")
    void publishPendingSuccess() {
        String payload = "{\"transactionId\":\"tx-1\",\"traceId\":\"trace-abc\",\"amountCents\":5000}";
        OutboxEvent event = new OutboxEvent("TRANSACTION", "tx-1", "TransactionCompleted", payload);

        when(outboxRepository.lockPendingBatch(10)).thenReturn(List.of(event));

        doAnswer(invocation -> {
            RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
            RabbitOperations ops = mock(RabbitOperations.class);
            return callback.doInRabbit(ops);
        }).when(rabbitTemplate).invoke(any());

        outboxPublisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxEvent.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();

        verify(rabbitTemplate).invoke(any());
    }

    @Test
    @DisplayName("Incrementa contador de falha quando envio ao RabbitMQ falha e marca FAILED após limite")
    void publishFailureDeadLettersAfterMaxRetries() {
        String payload = "{\"transactionId\":\"tx-2\",\"amountCents\":1000}";
        OutboxEvent event = new OutboxEvent("TRANSACTION", "tx-2", "TransactionCompleted", payload);

        when(outboxRepository.lockPendingBatch(10)).thenReturn(List.of(event));
        doThrow(new RuntimeException("RabbitMQ Broker Down")).when(rabbitTemplate).invoke(any());

        // Tentativa 1
        outboxPublisher.publishPending();
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.PENDING);
        assertThat(meterRegistry.get("ledger.outbox.publish.failures").counter().count()).isEqualTo(1.0);

        // Tentativa 2
        outboxPublisher.publishPending();
        assertThat(event.getRetryCount()).isEqualTo(2);
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.PENDING);

        // Tentativa 3 (maxRetries = 3)
        outboxPublisher.publishPending();
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.FAILED);
        assertThat(meterRegistry.get("ledger.outbox.deadletter.total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Não faz nada se não houver eventos pendentes")
    void noPendingEventsDoesNothing() {
        when(outboxRepository.lockPendingBatch(10)).thenReturn(Collections.emptyList());

        outboxPublisher.publishPending();

        verifyNoInteractions(rabbitTemplate);
    }
}
