package com.coreledger.infrastructure.outbox;

import com.coreledger.domain.model.OutboxEvent;
import com.coreledger.domain.repository.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageBuilderSupport;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Despacha eventos PENDING da tabela outbox_events para o RabbitMQ com publisher confirms.
 * Usa FOR UPDATE SKIP LOCKED para permitir múltiplas instâncias sem duplicidade.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final int maxRetries;

    public OutboxPublisher(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry,
                           @Value("${coreledger.outbox.batch-size:50}") int batchSize,
                           @Value("${coreledger.outbox.max-retries:5}") int maxRetries) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries;
    }

    @Scheduled(fixedDelayString = "${coreledger.outbox.poll-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> events = outboxRepository.lockPendingBatch(batchSize);
        for (OutboxEvent event : events) {
            try {
                publish(event);
                event.markProcessed();
            } catch (Exception ex) {
                meterRegistry.counter("ledger.outbox.publish.failures").increment();
                event.recordFailure(ex.getMessage(), maxRetries);
                if (OutboxEvent.FAILED.equals(event.getStatus())) {
                    meterRegistry.counter("ledger.outbox.deadletter.total").increment();
                    log.error("Evento outbox {} atingiu o limite de {} tentativas e foi marcado como FAILED: {}",
                            event.getId(), maxRetries, ex.getMessage());
                } else {
                    log.warn("Falha ao publicar evento outbox {} (tentativa {}/{}): {}",
                            event.getId(), event.getRetryCount(), maxRetries, ex.getMessage());
                    break; // preserva a ordem se a falha for de conectividade temporária
                }
            }
        }
    }

    private void publish(OutboxEvent event) throws Exception {
        JsonNode node = objectMapper.readTree(event.getPayload());
        MessageBuilderSupport<Message> builder = MessageBuilder
                .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(event.getId().toString())
                .setHeader("event-type", event.getEventType());
        if (node.hasNonNull("traceId")) {
            builder.setHeader(OutboxConsumer.TRACE_HEADER, node.get("traceId").asText());
        }
        rabbitTemplate.invoke(ops -> {
            ops.send(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, builder.build());
            ops.waitForConfirmsOrDie(5000);
            return null;
        });
    }
}
