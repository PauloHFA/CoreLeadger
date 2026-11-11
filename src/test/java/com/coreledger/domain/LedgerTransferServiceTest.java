package com.coreledger.domain;

import com.coreledger.domain.exception.AccountNotFoundException;
import com.coreledger.domain.exception.InsufficientBalanceException;
import com.coreledger.domain.model.Account;
import com.coreledger.domain.model.LedgerEntry;
import com.coreledger.domain.model.OutboxEvent;
import com.coreledger.domain.model.TransactionEntity;
import com.coreledger.domain.repository.AccountRepository;
import com.coreledger.domain.repository.LedgerEntryRepository;
import com.coreledger.domain.repository.OutboxRepository;
import com.coreledger.domain.repository.TransactionRepository;
import com.coreledger.domain.service.LedgerTransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LedgerTransferServiceTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private LedgerEntryRepository entryRepository;
    private OutboxRepository outboxRepository;
    private SimpleMeterRegistry meterRegistry;
    private ObjectMapper objectMapper;
    private LedgerTransferService transferService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        entryRepository = mock(LedgerEntryRepository.class);
        outboxRepository = mock(OutboxRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper();

        transferService = new LedgerTransferService(
                accountRepository,
                transactionRepository,
                entryRepository,
                outboxRepository,
                meterRegistry,
                objectMapper
        );
    }

    @Test
    @DisplayName("Executa transferência válida com partidas dobradas e evento Outbox")
    void executeTransferSuccess() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        Account source = new Account(sourceId, "Alice", "111", 50_000);
        Account target = new Account(targetId, "Bob", "222", 10_000);

        when(accountRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UUID txId = transferService.executeTransfer("key-1", sourceId, targetId, 15_000, "Pix", "trace-123");

        assertThat(txId).isNotNull();
        assertThat(source.getBalanceCents()).isEqualTo(35_000);
        assertThat(target.getBalanceCents()).isEqualTo(25_000);

        // Verifica os dois lançamentos contábeis (DEBIT e CREDIT)
        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(entryRepository, times(2)).save(entryCaptor.capture());
        List<LedgerEntry> entries = entryCaptor.getAllValues();

        LedgerEntry debit = entries.stream().filter(e -> LedgerEntry.DEBIT.equals(e.getEntryType())).findFirst().orElseThrow();
        LedgerEntry credit = entries.stream().filter(e -> LedgerEntry.CREDIT.equals(e.getEntryType())).findFirst().orElseThrow();

        assertThat(debit.getAccountId()).isEqualTo(sourceId);
        assertThat(debit.getAmountCents()).isEqualTo(15_000);
        assertThat(debit.signedAmountCents()).isEqualTo(-15_000);

        assertThat(credit.getAccountId()).isEqualTo(targetId);
        assertThat(credit.getAmountCents()).isEqualTo(15_000);
        assertThat(credit.signedAmountCents()).isEqualTo(15_000);

        // Invariante de soma zero
        assertThat(debit.signedAmountCents() + credit.signedAmountCents()).isZero();

        // Verifica evento de outbox
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getValue();
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.PENDING);
        assertThat(event.getPayload()).contains("trace-123").contains("15000");

        // Verifica métrica do Micrometer
        assertThat(meterRegistry.get("ledger.transactions.total").tag("status", "SUCCESS").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Rejeita transferência com saldo insuficiente e incrementa métrica de erro")
    void executeTransferInsufficientBalance() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        Account source = new Account(sourceId, "Alice", "111", 5_000);
        Account target = new Account(targetId, "Bob", "222", 10_000);

        when(accountRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdWithLock(targetId)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> transferService.executeTransfer("key-2", sourceId, targetId, 10_000, "Pix", "trace-xyz"))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Saldo insuficiente");

        assertThat(source.getBalanceCents()).isEqualTo(5_000);
        assertThat(target.getBalanceCents()).isEqualTo(10_000);

        verify(transactionRepository, never()).save(any());
        verify(entryRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());

        assertThat(meterRegistry.get("ledger.transactions.total").tag("status", "INSUFFICIENT_FUNDS").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Rejeita transferência para a mesma conta de origem e destino")
    void executeTransferSameAccount() {
        UUID sameId = UUID.randomUUID();
        assertThatThrownBy(() -> transferService.executeTransfer("key-3", sameId, sameId, 5_000, "Pix", "trace-xyz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conta de origem e destino devem ser diferentes");

        verifyNoInteractions(accountRepository);
    }

    @Test
    @DisplayName("Rejeita transferência quando conta de origem não é encontrada")
    void executeTransferSourceNotFound() {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(accountRepository.findByIdWithLock(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.executeTransfer("key-4", sourceId, targetId, 5_000, "Pix", "trace-xyz"))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
