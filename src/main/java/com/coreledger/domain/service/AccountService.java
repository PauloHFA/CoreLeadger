package com.coreledger.domain.service;

import com.coreledger.domain.exception.AccountNotFoundException;
import com.coreledger.domain.exception.BusinessRuleException;
import com.coreledger.domain.model.Account;
import com.coreledger.domain.model.LedgerEntry;
import com.coreledger.domain.model.TransactionEntity;
import com.coreledger.domain.repository.AccountRepository;
import com.coreledger.domain.repository.LedgerEntryRepository;
import com.coreledger.domain.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class AccountService {

    public static final UUID TREASURY_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    public static final String TREASURY_ACCOUNT_NUMBER = "0000-TREASURY";
    private static final long TREASURY_INITIAL_POOL = 100_000_000_000_000L;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    public AccountService(AccountRepository accountRepository, TransactionRepository transactionRepository,
                          LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    /**
     * Cria a conta. Se houver saldo inicial, a operação é liquidada como partida dobrada
     * debitando a conta de Tesouraria Central e creditando a nova conta, mantendo a soma global zero.
     */
    @Transactional
    public Account create(String holderName, String accountNumber, long initialBalanceCents) {
        if (accountRepository.existsByAccountNumber(accountNumber)) {
            throw new BusinessRuleException("Número de conta já existente.");
        }
        Account account = new Account(holderName, accountNumber);
        if (initialBalanceCents > 0) {
            Account treasury = accountRepository.findByIdWithLock(TREASURY_ACCOUNT_ID)
                    .orElseGet(() -> accountRepository.save(
                            new Account(TREASURY_ACCOUNT_ID, "CoreLedger Central Treasury",
                                    TREASURY_ACCOUNT_NUMBER, TREASURY_INITIAL_POOL)));
            treasury.debit(initialBalanceCents);
            account.credit(initialBalanceCents);
            accountRepository.save(treasury);
            accountRepository.save(account);

            TransactionEntity tx = transactionRepository.save(new TransactionEntity(
                    "OPENING-" + account.getId(), initialBalanceCents, "OPENING", "Saldo de abertura", null));
            entryRepository.save(new LedgerEntry(tx.getId(), treasury.getId(), LedgerEntry.DEBIT, initialBalanceCents));
            entryRepository.save(new LedgerEntry(tx.getId(), account.getId(), LedgerEntry.CREDIT, initialBalanceCents));
        } else {
            accountRepository.save(account);
        }
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException("Conta não encontrada"));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<LedgerEntry> statement(
            UUID accountId, OffsetDateTime from, OffsetDateTime to, org.springframework.data.domain.Pageable pageable) {
        get(accountId);
        return entryRepository.findByAccountIdAndCreatedAtBetweenOrderByCreatedAtDesc(accountId, from, to, pageable);
    }

    @Transactional(readOnly = true)
    public long ledgerSum(UUID accountId) {
        get(accountId);
        return entryRepository.sumSignedByAccount(accountId);
    }
}
