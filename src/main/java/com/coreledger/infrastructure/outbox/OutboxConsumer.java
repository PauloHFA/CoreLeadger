package com.coreledger.infrastructure.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** Consumidor de exemplo: restaura o traceId do HTTP original no MDC dos logs. */
@Component
public class OutboxConsumer {

    public static final String TRACE_HEADER = "x-trace-id";
    private static final Logger log = LoggerFactory.getLogger(OutboxConsumer.class);

    private final MeterRegistry meterRegistry;

    public OutboxConsumer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onEvent(@Payload String payload,
                        @Header(name = TRACE_HEADER, required = false) String traceId) {
        String previous = MDC.get("traceId");
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        try {
            log.info("Evento de transação consumido: {}", payload);
            meterRegistry.counter("ledger.events.consumed").increment();
        } finally {
            if (previous == null) {
                MDC.remove("traceId");
            } else {
                MDC.put("traceId", previous);
            }
        }
    }
}
