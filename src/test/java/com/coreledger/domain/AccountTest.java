package com.coreledger.domain;

import com.coreledger.domain.exception.InsufficientBalanceException;
import com.coreledger.domain.model.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    @Timeout(value = 50, unit = TimeUnit.MILLISECONDS)
    void debitAndCreditKeepGlobalSumZero() {
        Account a = new Account("A", "1");
        Account b = new Account("B", "2");
        a.credit(50_000);
        b.credit(10_000);
        long before = a.getBalanceCents() + b.getBalanceCents();

        a.debit(15_000);
        b.credit(15_000);

        assertThat(a.getBalanceCents()).isEqualTo(35_000);
        assertThat(b.getBalanceCents()).isEqualTo(25_000);
        assertThat(a.getBalanceCents() + b.getBalanceCents()).isEqualTo(before);
    }

    @Test
    void rejectsInsufficientBalance() {
        Account a = new Account("A", "1");
        a.credit(1_000);
        assertThatThrownBy(() -> a.debit(1_001)).isInstanceOf(InsufficientBalanceException.class);
        assertThat(a.getBalanceCents()).isEqualTo(1_000);
    }

    @Test
    void rejectsZeroOrNegativeAmounts() {
        Account a = new Account("A", "1");
        assertThatThrownBy(() -> a.credit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.credit(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.debit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.debit(-5)).isInstanceOf(IllegalArgumentException.class);
    }
}
