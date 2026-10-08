package com.example.bankapp.service;

import com.example.bankapp.TestcontainersConfiguration;
import com.example.bankapp.model.Account;
import com.example.bankapp.model.Transaction;
import com.example.bankapp.repository.AccountRepository;
import com.example.bankapp.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountTransferTests {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @SpyBean
    private TransactionRepository transactionRepository;

    @Test
    void transferMovesMoneyAndRecordsBothSides() {
        Account alice = newAccount("100.00");
        Account bob = newAccount("0.00");

        accountService.transferAmount(alice, bob.getUsername(), new BigDecimal("40.00"));

        assertThat(balanceOf(alice)).isEqualByComparingTo("60.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("40.00");
        assertThat(historyOf(alice)).hasSize(1);
        assertThat(historyOf(bob)).hasSize(1);
    }

    @Test
    void failureMidTransferLeavesBothBalancesUnchanged() {
        Account alice = newAccount("100.00");
        Account bob = newAccount("0.00");
        // Fail on the very last step, after both balances have already been written.
        doThrow(new RuntimeException("Simulated database failure"))
                .when(transactionRepository)
                .save(argThat(t -> t.getType().startsWith("Transfer In")));

        assertThatThrownBy(() -> accountService.transferAmount(alice, bob.getUsername(), new BigDecimal("40.00")))
                .hasMessage("Simulated database failure");

        assertThat(balanceOf(alice)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("0.00");
        assertThat(historyOf(alice)).isEmpty();
        assertThat(historyOf(bob)).isEmpty();
    }

    @Test
    void insufficientFundsChangesNothing() {
        Account alice = newAccount("10.00");
        Account bob = newAccount("0.00");

        assertThatThrownBy(() -> accountService.transferAmount(alice, bob.getUsername(), new BigDecimal("40.00")))
                .hasMessage("Insufficient funds");

        assertThat(balanceOf(alice)).isEqualByComparingTo("10.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("0.00");
        assertThat(historyOf(alice)).isEmpty();
    }

    @Test
    void concurrentTransfersNeverOverdrawOrLoseMoney() throws Exception {
        Account alice = newAccount("100.00");
        Account bob = newAccount("0.00");

        // Ten simultaneous $20 transfers from a $100 account: exactly five can succeed.
        List<Boolean> results = runConcurrently(10, i -> () -> {
            try {
                accountService.transferAmount(alice, bob.getUsername(), new BigDecimal("20.00"));
                return true;
            } catch (RuntimeException e) {
                assertThat(e).hasMessage("Insufficient funds");
                return false;
            }
        });

        assertThat(results).filteredOn(ok -> ok).hasSize(5);
        assertThat(balanceOf(alice)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("100.00");
        assertThat(historyOf(alice)).hasSize(5);
        assertThat(historyOf(bob)).hasSize(5);
    }

    @Test
    void transfersInBothDirectionsAtOnceDoNotDeadlock() throws Exception {
        Account alice = newAccount("100.00");
        Account bob = newAccount("100.00");

        List<Boolean> results = runConcurrently(20, i -> () -> {
            if (i % 2 == 0) {
                accountService.transferAmount(alice, bob.getUsername(), new BigDecimal("5.00"));
            } else {
                accountService.transferAmount(bob, alice.getUsername(), new BigDecimal("5.00"));
            }
            return true;
        });

        assertThat(results).hasSize(20).containsOnly(true);
        assertThat(balanceOf(alice)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("100.00");
    }

    private interface TaskFactory {
        Callable<Boolean> create(int index);
    }

    private List<Boolean> runConcurrently(int count, TaskFactory factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<Boolean> task = factory.create(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Account newAccount(String balance) {
        Account account = new Account();
        account.setUsername("user-" + UUID.randomUUID());
        account.setPassword("password");
        account.setBalance(new BigDecimal(balance));
        return accountRepository.save(account);
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).get().getBalance();
    }

    private List<Transaction> historyOf(Account account) {
        return transactionRepository.findByAccountId(account.getId());
    }
}
