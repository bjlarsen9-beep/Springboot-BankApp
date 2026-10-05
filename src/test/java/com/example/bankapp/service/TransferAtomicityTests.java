package com.example.bankapp.service;

import com.example.bankapp.TestcontainersConfiguration;
import com.example.bankapp.model.Account;
import com.example.bankapp.model.Transaction;
import com.example.bankapp.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferAtomicityTests {

    @Autowired
    private AccountService accountService;

    @MockitoSpyBean
    private TransactionRepository transactionRepository;

    @Test
    void failureHalfwayThroughTransferRollsEverythingBack() {
        Account sender = accountService.registerAccount("user-" + UUID.randomUUID(), "secret");
        Account recipient = accountService.registerAccount("user-" + UUID.randomUUID(), "secret");
        accountService.deposit(sender, new BigDecimal("100"));

        doThrow(new IllegalStateException("simulated crash"))
                .when(transactionRepository)
                .save(argThat((Transaction t) -> t != null && t.getType().startsWith("Transfer In")));

        assertThatThrownBy(() -> accountService.transferAmount(sender, recipient.getUsername(), new BigDecimal("40")))
                .hasMessage("simulated crash");

        assertThat(accountService.findAccountByUsername(sender.getUsername()).getBalance()).isEqualByComparingTo("100");
        assertThat(accountService.findAccountByUsername(recipient.getUsername()).getBalance()).isEqualByComparingTo("0");
        assertThat(accountService.getTransactionHistory(sender)).extracting(Transaction::getType).containsExactly("Deposit");
    }
}
