package com.coreledger.domain.service;

import com.coreledger.domain.exception.AccountNotFoundException;
import com.coreledger.domain.exception.BusinessRuleException;
import com.coreledger.domain.exception.InsufficientBalanceException;
import com.coreledger.domain.model.Account;
import com.coreledger.domain.model.LedgerEntry;
import com.coreledger.domain.model.OutboxEvent;
import com.coreledger.domain.model.TransactionEntity;
import com.coreledger.domain.repository.AccountRepository;
import com.coreledger.domain.repository.LedgerEntryRepository;
import com.coreledger.domain.repository.OutboxRepository;
import com.coreledger.domain.repository.TransactionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Executa a transferência em uma única transação ACID: partidas dobradas (DEBIT + CREDIT) e evento de Outbox.
 */
@Service
public class LedgerTransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final OutboxRepository outboxRepository;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final Timer transferTimer;

    public LedgerTransferService(AccountRepository accountRepository,
                                 TransactionRepository transactionRepository,
                                 LedgerEntryRepository entryRepository,
                                 OutboxRepository outboxRepository,
                                 MeterRegistry meterRegistry,
                                 ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.outboxRepository = outboxRepository;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.transferTimer = Timer.builder("ledger.transfer.latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Transactional
    public UUID executeTransfer(String idempotencyKey, UUID sourceId, UUID targetId, long amountCents,
                                String description, String traceId) {
        return transferTimer.record(() -> doTransfer(idempotencyKey, sourceId, targetId, amountCents, description, traceId));
    }

    private UUID doTransfer(String idempotencyKey, UUID sourceId, UUID targetId, long amountCents,
                            String description, String traceId) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("O valor deve ser maior que zero.");
        }
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("Conta de origem e destino devem ser diferentes.");
        }
        try {
            // Locks sempre em ordem de UUID para evitar deadlock em transferências cruzadas.
            boolean sourceFirst = sourceId.compareTo(targetId) < 0;
            Account first = load(sourceFirst ? sourceId : targetId, sourceFirst ? "origem" : "destino");
            Account second = load(sourceFirst ? targetId : sourceId, sourceFirst ? "destino" : "origem");
            Account source = sourceFirst ? first : second;
            Account target = sourceFirst ? second : first;

            source.debit(amountCents);
            target.credit(amountCents);

            TransactionEntity tx = transactionRepository.save(
                    new TransactionEntity(idempotencyKey, amountCents, "COMPLETED", description, traceId));
            entryRepository.save(new LedgerEntry(tx.getId(), source.getId(), LedgerEntry.DEBIT, amountCents));
            entryRepository.save(new LedgerEntry(tx.getId(), target.getId(), LedgerEntry.CREDIT, amountCents));
            outboxRepository.save(new OutboxEvent("TRANSACTION", tx.getId().toString(), "TransactionCompleted",
                    payload(tx.getId(), source.getId(), target.getId(), amountCents, traceId)));
            accountRepository.save(source);
            accountRepository.save(target);

            meterRegistry.counter("ledger.transactions.total", "status", "SUCCESS").increment();
            return tx.getId();
        } catch (InsufficientBalanceException ex) {
            meterRegistry.counter("ledger.transactions.total", "status", "INSUFFICIENT_FUNDS").increment();
            throw ex;
        } catch (BusinessRuleException | AccountNotFoundException | IllegalArgumentException ex) {
            meterRegistry.counter("ledger.transactions.total", "status", "REJECTED").increment();
            throw ex;
        }
    }

    private Account load(UUID id, String role) {
        return accountRepository.findByIdWithLock(id)
                .orElseThrow(() -> new AccountNotFoundException("Conta de " + role + " não encontrada"));
    }

    private String payload(UUID txId, UUID source, UUID target, long amount, String traceId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("transactionId", txId);
        map.put("sourceAccountId", source);
        map.put("targetAccountId", target);
        map.put("amountCents", amount);
        map.put("traceId", traceId);
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
