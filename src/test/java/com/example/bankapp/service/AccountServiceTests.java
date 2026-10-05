package com.example.bankapp.service;

import com.example.bankapp.TestcontainersConfiguration;
import com.example.bankapp.model.Account;
import com.example.bankapp.model.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountServiceTests {

    @Autowired
    private AccountService accountService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static String uniqueName() {
        return "user-" + UUID.randomUUID();
    }

    private Account newAccountWithBalance(String amount) {
        Account account = accountService.registerAccount(uniqueName(), "secret");
        if (new BigDecimal(amount).signum() > 0) {
            accountService.deposit(account, new BigDecimal(amount));
        }
        return accountService.findAccountByUsername(account.getUsername());
    }

    private BigDecimal balanceOf(Account account) {
        return accountService.findAccountByUsername(account.getUsername()).getBalance();
    }

    @Test
    void registerCreatesAccountWithZeroBalanceAndHashedPassword() {
        String username = uniqueName();

        Account account = accountService.registerAccount(username, "secret");

        assertThat(account.getId()).isNotNull();
        assertThat(account.getBalance()).isEqualByComparingTo("0");
        assertThat(account.getPassword()).isNotEqualTo("secret");
        assertThat(passwordEncoder.matches("secret", account.getPassword())).isTrue();
    }

    @Test
    void registerRejectsDuplicateUsername() {
        String username = uniqueName();
        accountService.registerAccount(username, "secret");

        assertThatThrownBy(() -> accountService.registerAccount(username, "other"))
                .hasMessage("Username already exists");
    }

    @Test
    void loadUserByUsernameReturnsStoredCredentials() {
        Account account = accountService.registerAccount(uniqueName(), "secret");

        var user = accountService.loadUserByUsername(account.getUsername());

        assertThat(user.getUsername()).isEqualTo(account.getUsername());
        assertThat(passwordEncoder.matches("secret", user.getPassword())).isTrue();
        assertThat(user.getAuthorities()).extracting("authority").containsExactly("USER");
    }

    @Test
    void depositIncreasesBalanceAndRecordsTransaction() {
        Account account = newAccountWithBalance("0");

        accountService.deposit(account, new BigDecimal("125.50"));

        assertThat(balanceOf(account)).isEqualByComparingTo("125.50");
        List<Transaction> history = accountService.getTransactionHistory(account);
        assertThat(history).singleElement().satisfies(tx -> {
            assertThat(tx.getType()).isEqualTo("Deposit");
            assertThat(tx.getAmount()).isEqualByComparingTo("125.50");
            assertThat(tx.getTimestamp()).isNotNull();
        });
    }

    @Test
    void withdrawDecreasesBalanceAndRecordsTransaction() {
        Account account = newAccountWithBalance("100");

        accountService.withdraw(account, new BigDecimal("40"));

        assertThat(balanceOf(account)).isEqualByComparingTo("60");
        assertThat(accountService.getTransactionHistory(account))
                .extracting(Transaction::getType)
                .containsExactly("Deposit", "Withdrawal");
    }

    @Test
    void withdrawMoreThanBalanceFailsAndLeavesBalanceUnchanged() {
        Account account = newAccountWithBalance("50");

        assertThatThrownBy(() -> accountService.withdraw(account, new BigDecimal("50.01")))
                .hasMessage("Insufficient funds");

        assertThat(balanceOf(account)).isEqualByComparingTo("50");
        assertThat(accountService.getTransactionHistory(account)).hasSize(1);
    }

    @Test
    void transferMovesMoneyAndRecordsBothSides() {
        Account sender = newAccountWithBalance("200");
        Account recipient = newAccountWithBalance("10");

        accountService.transferAmount(sender, recipient.getUsername(), new BigDecimal("75"));

        assertThat(balanceOf(sender)).isEqualByComparingTo("125");
        assertThat(balanceOf(recipient)).isEqualByComparingTo("85");
        assertThat(accountService.getTransactionHistory(sender))
                .extracting(Transaction::getType)
                .contains("Transfer Out to " + recipient.getUsername());
        assertThat(accountService.getTransactionHistory(recipient))
                .extracting(Transaction::getType)
                .contains("Transfer In from " + sender.getUsername());
    }

    @Test
    void transferMoreThanBalanceFailsAndMovesNothing() {
        Account sender = newAccountWithBalance("20");
        Account recipient = newAccountWithBalance("0");

        assertThatThrownBy(() -> accountService.transferAmount(sender, recipient.getUsername(), new BigDecimal("20.01")))
                .hasMessage("Insufficient funds");

        assertThat(balanceOf(sender)).isEqualByComparingTo("20");
        assertThat(balanceOf(recipient)).isEqualByComparingTo("0");
    }

    @Test
    void transferToUnknownRecipientFailsAndMovesNothing() {
        Account sender = newAccountWithBalance("20");

        assertThatThrownBy(() -> accountService.transferAmount(sender, uniqueName(), new BigDecimal("5")))
                .hasMessage("Recipient account not found");

        assertThat(balanceOf(sender)).isEqualByComparingTo("20");
    }
}
