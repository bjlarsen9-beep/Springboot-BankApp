package com.example.bankapp.service;

import com.example.bankapp.TestcontainersConfiguration;
import com.example.bankapp.model.Account;
import com.example.bankapp.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @ParameterizedTest
    @ValueSource(strings = {"0", "-50", "0.001"})
    void depositRejectsInvalidAmounts(String amount) {
        Account account = newAccountWithBalance("100");

        assertThatThrownBy(() -> accountService.deposit(account, new BigDecimal(amount)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(balanceOf(account)).isEqualByComparingTo("100");
        assertThat(accountService.getTransactionHistory(account)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-50", "0.001"})
    void withdrawRejectsInvalidAmounts(String amount) {
        Account account = newAccountWithBalance("100");

        assertThatThrownBy(() -> accountService.withdraw(account, new BigDecimal(amount)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(balanceOf(account)).isEqualByComparingTo("100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-500", "0.001"})
    void transferRejectsInvalidAmounts(String amount) {
        Account sender = newAccountWithBalance("100");
        Account recipient = newAccountWithBalance("0");

        assertThatThrownBy(() -> accountService.transferAmount(sender, recipient.getUsername(), new BigDecimal(amount)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(balanceOf(sender)).isEqualByComparingTo("100");
        assertThat(balanceOf(recipient)).isEqualByComparingTo("0");
    }

    @Test
    void transferToOwnAccountIsRejected() {
        Account account = newAccountWithBalance("100");

        assertThatThrownBy(() -> accountService.transferAmount(account, account.getUsername(), new BigDecimal("10")))
                .hasMessage("Cannot transfer to your own account");
        assertThat(balanceOf(account)).isEqualByComparingTo("100");
    }

    @Test
    void concurrentWithdrawalsCannotOverdraw() throws Exception {
        Account account = newAccountWithBalance("100");

        List<Boolean> results = runConcurrently(8, i -> () -> {
            try {
                accountService.withdraw(account, new BigDecimal("20"));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });

        assertThat(results).filteredOn(ok -> ok).hasSize(5);
        assertThat(balanceOf(account)).isEqualByComparingTo("0");
    }

    @Test
    void concurrentDepositsAreNotLost() throws Exception {
        Account account = newAccountWithBalance("0");

        runConcurrently(8, i -> () -> {
            accountService.deposit(account, new BigDecimal("5"));
            return true;
        });

        assertThat(balanceOf(account)).isEqualByComparingTo("40");
    }

    @Test
    void opposingConcurrentTransfersDoNotDeadlockOrLoseMoney() throws Exception {
        Account alice = newAccountWithBalance("100");
        Account bob = newAccountWithBalance("100");

        List<Boolean> results = runConcurrently(8, i -> () -> {
            if (i % 2 == 0) {
                accountService.transferAmount(alice, bob.getUsername(), new BigDecimal("10"));
            } else {
                accountService.transferAmount(bob, alice.getUsername(), new BigDecimal("10"));
            }
            return true;
        });

        assertThat(results).containsOnly(true);
        assertThat(balanceOf(alice)).isEqualByComparingTo("100");
        assertThat(balanceOf(bob)).isEqualByComparingTo("100");
    }

    private interface TaskFactory {
        Callable<Boolean> task(int index);
    }

    private static List<Boolean> runConcurrently(int threads, TaskFactory factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> task = factory.task(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
