package com.coreledger.domain;

import com.coreledger.domain.exception.AccountNotFoundException;
import com.coreledger.domain.exception.BusinessRuleException;
import com.coreledger.domain.model.Account;
import com.coreledger.domain.model.LedgerEntry;
import com.coreledger.domain.model.TransactionEntity;
import com.coreledger.domain.repository.AccountRepository;
import com.coreledger.domain.repository.LedgerEntryRepository;
import com.coreledger.domain.repository.TransactionRepository;
import com.coreledger.domain.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountServiceTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private LedgerEntryRepository entryRepository;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        entryRepository = mock(LedgerEntryRepository.class);
        accountService = new AccountService(accountRepository, transactionRepository, entryRepository);
    }

    @Test
    @DisplayName("Cria conta simples sem saldo inicial")
    void createAccountWithoutInitialBalance() {
        when(accountRepository.existsByAccountNumber("0001")).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account account = accountService.create("Maria", "0001", 0);

        assertThat(account.getId()).isNotNull();
        assertThat(account.getBalanceCents()).isZero();
        verify(accountRepository).save(account);
        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(entryRepository);
    }

    @Test
    @DisplayName("Cria conta com saldo inicial debitando a Tesouraria em partidas dobradas")
    void createAccountWithInitialBalance() {
        when(accountRepository.existsByAccountNumber("0002")).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.save(any(TransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Account treasury = new Account(AccountService.TREASURY_ACCOUNT_ID, "Treasury", "0000", 1_000_000);
        when(accountRepository.findByIdWithLock(AccountService.TREASURY_ACCOUNT_ID)).thenReturn(Optional.of(treasury));

        Account account = accountService.create("João", "0002", 50_000);

        assertThat(account.getBalanceCents()).isEqualTo(50_000);
        assertThat(treasury.getBalanceCents()).isEqualTo(950_000);

        ArgumentCaptor<LedgerEntry> entryCaptor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(entryRepository, times(2)).save(entryCaptor.capture());
        List<LedgerEntry> entries = entryCaptor.getAllValues();

        LedgerEntry treasuryDebit = entries.stream().filter(e -> e.getAccountId().equals(AccountService.TREASURY_ACCOUNT_ID)).findFirst().orElseThrow();
        LedgerEntry accountCredit = entries.stream().filter(e -> e.getAccountId().equals(account.getId())).findFirst().orElseThrow();

        assertThat(treasuryDebit.getEntryType()).isEqualTo(LedgerEntry.DEBIT);
        assertThat(treasuryDebit.getAmountCents()).isEqualTo(50_000);

        assertThat(accountCredit.getEntryType()).isEqualTo(LedgerEntry.CREDIT);
        assertThat(accountCredit.getAmountCents()).isEqualTo(50_000);

        // Invariante de soma zero global
        assertThat(treasuryDebit.signedAmountCents() + accountCredit.signedAmountCents()).isZero();
    }

    @Test
    @DisplayName("Rejeita criação de conta com número duplicado")
    void rejectDuplicateAccountNumber() {
        when(accountRepository.existsByAccountNumber("0001")).thenReturn(true);

        assertThatThrownBy(() -> accountService.create("Maria", "0001", 100))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Número de conta já existente");
    }

    @Test
    @DisplayName("Consulta extrato e integridade contábil")
    void statementAndIntegrity() {
        UUID accountId = UUID.randomUUID();
        Account account = new Account(accountId, "Carlos", "0003", 25_000);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(entryRepository.sumSignedByAccount(accountId)).thenReturn(25_000L);

        Page<LedgerEntry> mockPage = new PageImpl<>(List.of(
                new LedgerEntry(UUID.randomUUID(), accountId, LedgerEntry.CREDIT, 25_000)
        ));
        when(entryRepository.findByAccountIdAndCreatedAtBetweenOrderByCreatedAtDesc(eq(accountId), any(), any(), any()))
                .thenReturn(mockPage);

        Page<LedgerEntry> result = accountService.statement(accountId, OffsetDateTime.now().minusDays(1), OffsetDateTime.now(), PageRequest.of(0, 10));
        assertThat(result.getContent()).hasSize(1);

        long sum = accountService.ledgerSum(accountId);
        assertThat(sum).isEqualTo(25_000L);
    }
}
