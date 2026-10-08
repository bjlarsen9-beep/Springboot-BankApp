package com.example.bankapp.service;

import com.example.bankapp.model.Account;
import com.example.bankapp.repository.AccountRepository;
import com.example.bankapp.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceAmountValidationTests {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private AccountService accountService;

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        alice = account("alice", "100.00");
        bob = account("bob", "50.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-25.00", "0", "0.00"})
    void depositRejectsNonPositiveAmounts(String amount) {
        assertThatThrownBy(() -> accountService.deposit(alice, new BigDecimal(amount)))
                .hasMessage("Amount must be greater than zero");

        assertThat(alice.getBalance()).isEqualByComparingTo("100.00");
        verifyNothingSaved();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-25.00", "0", "0.00"})
    void withdrawRejectsNonPositiveAmounts(String amount) {
        assertThatThrownBy(() -> accountService.withdraw(alice, new BigDecimal(amount)))
                .hasMessage("Amount must be greater than zero");

        assertThat(alice.getBalance()).isEqualByComparingTo("100.00");
        verifyNothingSaved();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-25.00", "0", "0.00"})
    void transferRejectsNonPositiveAmounts(String amount) {
        assertThatThrownBy(() -> accountService.transferAmount(alice, "bob", new BigDecimal(amount)))
                .hasMessage("Amount must be greater than zero");

        assertThat(alice.getBalance()).isEqualByComparingTo("100.00");
        assertThat(bob.getBalance()).isEqualByComparingTo("50.00");
        verifyNothingSaved();
    }

    @Test
    void nullAmountIsRejected() {
        assertThatThrownBy(() -> accountService.deposit(alice, null))
                .hasMessage("Amount must be greater than zero");
        verifyNothingSaved();
    }

    @Test
    void positiveDepositStillWorks() {
        accountService.deposit(alice, new BigDecimal("25.00"));
        assertThat(alice.getBalance()).isEqualByComparingTo("125.00");
    }

    @Test
    void positiveWithdrawalStillWorks() {
        accountService.withdraw(alice, new BigDecimal("25.00"));
        assertThat(alice.getBalance()).isEqualByComparingTo("75.00");
    }

    @Test
    void positiveTransferStillWorks() {
        when(accountRepository.findByUsername("bob")).thenReturn(Optional.of(bob));

        accountService.transferAmount(alice, "bob", new BigDecimal("25.00"));

        assertThat(alice.getBalance()).isEqualByComparingTo("75.00");
        assertThat(bob.getBalance()).isEqualByComparingTo("75.00");
    }

    private void verifyNothingSaved() {
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    private static Account account(String username, String balance) {
        Account account = new Account();
        account.setUsername(username);
        account.setBalance(new BigDecimal(balance));
        return account;
    }
}
