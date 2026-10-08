package com.example.bankapp.repository;

import com.example.bankapp.model.Account;
import com.example.bankapp.model.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class RepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private Account save(String username) {
        Account account = new Account();
        account.setUsername(username);
        account.setPassword("pw");
        account.setBalance(new BigDecimal("12.34"));
        return accountRepository.save(account);
    }

    @Test
    void findByUsernameReturnsSavedAccount() {
        Account saved = save("alice");

        assertThat(accountRepository.findByUsername("alice"))
                .hasValueSatisfying(found -> {
                    assertThat(found.getId()).isEqualTo(saved.getId());
                    assertThat(found.getBalance()).isEqualByComparingTo("12.34");
                });
        assertThat(accountRepository.findByUsername("nobody")).isEmpty();
    }

    @Test
    void findByAccountIdReturnsOnlyThatAccountsTransactions() {
        Account alice = save("alice");
        Account bob = save("bob");
        transactionRepository.save(new Transaction(new BigDecimal("5"), "Deposit", LocalDateTime.now(), alice));
        transactionRepository.save(new Transaction(new BigDecimal("2"), "Withdrawal", LocalDateTime.now(), alice));
        transactionRepository.save(new Transaction(new BigDecimal("9"), "Deposit", LocalDateTime.now(), bob));

        assertThat(transactionRepository.findByAccountId(alice.getId()))
                .extracting(Transaction::getType)
                .containsExactlyInAnyOrder("Deposit", "Withdrawal");
        assertThat(transactionRepository.findByAccountId(bob.getId())).hasSize(1);
    }
}
