package com.coreledger.domain.service;

import com.coreledger.infrastructure.acquirer.AcquirerGatewayClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Orquestra a transferência: (opcional) autorização na adquirente, trava distribuída Redisson por conta
 * (fora da transação de banco) e execução ACID com retry em conflito otimista (@Version).
 */
@Service
public class TransferOrchestrator {

    public enum Type { INTERNAL, EXTERNAL }

    private static final int MAX_OPTIMISTIC_RETRIES = 3;

    private final LedgerTransferService transferService;
    private final RedissonClient redisson;
    private final AcquirerGatewayClient acquirer;
    private final MeterRegistry meterRegistry;
    private final ObjectProvider<Tracer> tracer;
    private final long lockWaitSeconds;
    private final long lockLeaseSeconds;

    public TransferOrchestrator(LedgerTransferService transferService,
                                RedissonClient redisson,
                                AcquirerGatewayClient acquirer,
                                MeterRegistry meterRegistry,
                                ObjectProvider<Tracer> tracer,
                                @Value("${coreledger.lock.wait-seconds:15}") long lockWaitSeconds,
                                @Value("${coreledger.lock.lease-seconds:30}") long lockLeaseSeconds) {
        this.transferService = transferService;
        this.redisson = redisson;
        this.acquirer = acquirer;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        this.lockWaitSeconds = lockWaitSeconds;
        this.lockLeaseSeconds = lockLeaseSeconds;
    }

    public UUID transfer(String idempotencyKey, UUID sourceId, UUID targetId, long amountCents,
                         String description, Type type) {
        if (type == Type.EXTERNAL) {
            acquirer.authorizeWithFallback(idempotencyKey, amountCents);
        }
        String traceId = currentTraceId();

        List<UUID> ids = new ArrayList<>(List.of(sourceId, targetId));
        ids.sort(UUID::compareTo);
        List<RLock> locks = new ArrayList<>();
        try {
            for (UUID id : ids) {
                RLock lock = redisson.getLock("coreledger:lock:account:" + id);
                if (!lock.tryLock(lockWaitSeconds, lockLeaseSeconds, TimeUnit.SECONDS)) {
                    throw new ConcurrencyFailureException("Conta em alta concorrência, tente novamente.");
                }
                locks.add(lock);
            }
            return executeWithRetry(idempotencyKey, sourceId, targetId, amountCents, description, traceId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyFailureException("Interrompido aguardando trava da conta.", e);
        } finally {
            for (RLock lock : locks) {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    private UUID executeWithRetry(String key, UUID s, UUID t, long amount, String description, String traceId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transferService.executeTransfer(key, s, t, amount, description, traceId);
            } catch (ConcurrencyFailureException ex) { // inclui ObjectOptimisticLockingFailureException
                meterRegistry.counter("ledger.optimistic.retries").increment();
                if (attempt >= MAX_OPTIMISTIC_RETRIES) {
                    throw ex;
                }
            }
        }
    }

    private String currentTraceId() {
        Tracer t = tracer.getIfAvailable();
        if (t == null || t.currentSpan() == null) {
            return null;
        }
        return t.currentSpan().context().traceId();
    }
}
