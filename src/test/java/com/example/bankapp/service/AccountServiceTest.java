package com.example.bankapp.service;

import com.example.bankapp.model.Account;
import com.example.bankapp.model.Transaction;
import com.example.bankapp.repository.AccountRepository;
import com.example.bankapp.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private AccountService accountService;

    private static Account account(Long id, String username, String balance) {
        Account account = new Account();
        account.setId(id);
        account.setUsername(username);
        account.setPassword("encoded-" + username);
        account.setBalance(new BigDecimal(balance));
        return account;
    }

    @Test
    void registerAccountEncodesPasswordAndStartsWithZeroBalance() {
        when(accountRepository.findByUsername("alice")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret")).thenReturn("hashed-secret");
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        Account saved = accountService.registerAccount("alice", "secret");

        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getPassword()).isEqualTo("hashed-secret");
        assertThat(saved.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(accountRepository).save(saved);
    }

    @Test
    void registerAccountWithDuplicateUsernameThrows() {
        when(accountRepository.findByUsername("alice")).thenReturn(Optional.of(account(1L, "alice", "0")));

        assertThatThrownBy(() -> accountService.registerAccount("alice", "secret"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Username already exists");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void findAccountByUsernameReturnsAccount() {
        Account alice = account(1L, "alice", "10.00");
        when(accountRepository.findByUsername("alice")).thenReturn(Optional.of(alice));

        assertThat(accountService.findAccountByUsername("alice")).isSameAs(alice);
    }

    @Test
    void findAccountByUsernameThrowsWhenMissing() {
        when(accountRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.findAccountByUsername("ghost"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Account not found");
    }

    @Test
    void loadUserByUsernameReturnsUserDetailsWithUserAuthority() {
        when(accountRepository.findByUsername("alice")).thenReturn(Optional.of(account(1L, "alice", "10.00")));

        UserDetails details = accountService.loadUserByUsername("alice");

        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).isEqualTo("encoded-alice");
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("USER");
    }

    @Test
    void loadUserByUsernameThrowsForUnknownUser() {
        when(accountRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.loadUserByUsername("ghost"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void depositIncreasesBalanceAndRecordsTransaction() {
        Account alice = account(1L, "alice", "100.00");

        accountService.deposit(alice, new BigDecimal("25.50"));

        assertThat(alice.getBalance()).isEqualByComparingTo("125.50");
        verify(accountRepository).save(alice);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction tx = captor.getValue();
        assertThat(tx.getType()).isEqualTo("Deposit");
        assertThat(tx.getAmount()).isEqualByComparingTo("25.50");
        assertThat(tx.getAccount()).isSameAs(alice);
        assertThat(tx.getTimestamp()).isNotNull();
    }

    @Test
    void withdrawDecreasesBalanceAndRecordsTransaction() {
        Account alice = account(1L, "alice", "100.00");

        accountService.withdraw(alice, new BigDecimal("40.00"));

        assertThat(alice.getBalance()).isEqualByComparingTo("60.00");
        verify(accountRepository).save(alice);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("Withdrawal");
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("40.00");
    }

    @Test
    void withdrawWithInsufficientFundsThrowsAndSavesNothing() {
        Account alice = account(1L, "alice", "10.00");

        assertThatThrownBy(() -> accountService.withdraw(alice, new BigDecimal("10.01")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Insufficient funds");
        assertThat(alice.getBalance()).isEqualByComparingTo("10.00");
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transferAmountMovesFundsAndRecordsBothTransactions() {
        Account alice = account(1L, "alice", "100.00");
        Account bob = account(2L, "bob", "5.00");
        when(accountRepository.findByUsername("bob")).thenReturn(Optional.of(bob));

        accountService.transferAmount(alice, "bob", new BigDecimal("30.00"));

        assertThat(alice.getBalance()).isEqualByComparingTo("70.00");
        assertThat(bob.getBalance()).isEqualByComparingTo("35.00");
        verify(accountRepository).save(alice);
        verify(accountRepository).save(bob);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        List<Transaction> txs = captor.getAllValues();
        assertThat(txs.get(0).getType()).isEqualTo("Transfer Out to bob");
        assertThat(txs.get(0).getAccount()).isSameAs(alice);
        assertThat(txs.get(1).getType()).isEqualTo("Transfer In from alice");
        assertThat(txs.get(1).getAccount()).isSameAs(bob);
        assertThat(txs).allSatisfy(tx -> assertThat(tx.getAmount()).isEqualByComparingTo("30.00"));
    }

    @Test
    void transferAmountWithInsufficientFundsThrows() {
        Account alice = account(1L, "alice", "10.00");

        assertThatThrownBy(() -> accountService.transferAmount(alice, "bob", new BigDecimal("50.00")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Insufficient funds");
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void transferAmountToUnknownRecipientThrows() {
        Account alice = account(1L, "alice", "100.00");
        when(accountRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.transferAmount(alice, "ghost", new BigDecimal("10.00")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Recipient account not found");
        assertThat(alice.getBalance()).isEqualByComparingTo("100.00");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void getTransactionHistoryLooksUpByAccountId() {
        Account alice = account(7L, "alice", "0");
        List<Transaction> history = List.of(new Transaction());
        when(transactionRepository.findByAccountId(7L)).thenReturn(history);

        assertThat(accountService.getTransactionHistory(alice)).isSameAs(history);
    }
}
