package com.example.bankapp.controller;

import com.example.bankapp.config.SecurityConfig;
import com.example.bankapp.model.Account;
import com.example.bankapp.model.Transaction;
import com.example.bankapp.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(BankController.class)
@Import(SecurityConfig.class)
class BankControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountService accountService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account alice;

    @BeforeEach
    void setUp() {
        alice = new Account();
        alice.setId(1L);
        alice.setUsername("alice");
        alice.setBalance(new BigDecimal("100.00"));
    }

    @Test
    void registerPageIsPublic() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"));
    }

    @Test
    void registerSuccessRedirectsToLogin() throws Exception {
        mockMvc.perform(post("/register").with(csrf()).param("username", "alice").param("password", "secret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        verify(accountService).registerAccount("alice", "secret");
    }

    @Test
    void registerDuplicateShowsError() throws Exception {
        when(accountService.registerAccount("alice", "secret")).thenThrow(new RuntimeException("Username already exists"));

        mockMvc.perform(post("/register").with(csrf()).param("username", "alice").param("password", "secret"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attribute("error", "Username already exists"));
    }

    @Test
    void loginPageIsPublic() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }

    @Test
    void formLoginWithValidCredentialsRedirectsToDashboard() throws Exception {
        Account details = new Account("alice", passwordEncoder.encode("secret"), BigDecimal.ZERO, null,
                List.of(() -> "USER"));
        when(accountService.loadUserByUsername("alice")).thenReturn(details);

        mockMvc.perform(formLogin("/login").user("alice").password("secret"))
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(authenticated().withUsername("alice"));
    }

    @Test
    void formLoginWithBadPasswordFails() throws Exception {
        Account details = new Account("alice", passwordEncoder.encode("secret"), BigDecimal.ZERO, null,
                List.of(() -> "USER"));
        when(accountService.loadUserByUsername("alice")).thenReturn(details);

        mockMvc.perform(formLogin("/login").user("alice").password("wrong"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    @Test
    void dashboardRequiresLogin() throws Exception {
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithMockUser(username = "alice")
    void dashboardShowsAccount() throws Exception {
        when(accountService.findAccountByUsername("alice")).thenReturn(alice);

        mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("account", alice))
                .andExpect(content().string(containsString("Welcome, alice")))
                .andExpect(content().string(containsString("Current Balance: $100.00")));
    }

    @Test
    @WithMockUser(username = "alice")
    void depositRedirectsToDashboard() throws Exception {
        when(accountService.findAccountByUsername("alice")).thenReturn(alice);

        mockMvc.perform(post("/deposit").with(csrf()).param("amount", "50.00"))
                .andExpect(redirectedUrl("/dashboard"));
        verify(accountService).deposit(alice, new BigDecimal("50.00"));
    }

    @Test
    @WithMockUser(username = "alice")
    void withdrawRedirectsToDashboard() throws Exception {
        when(accountService.findAccountByUsername("alice")).thenReturn(alice);

        mockMvc.perform(post("/withdraw").with(csrf()).param("amount", "20.00"))
                .andExpect(redirectedUrl("/dashboard"));
        verify(accountService).withdraw(alice, new BigDecimal("20.00"));
    }

    @Test
    @WithMockUser(username = "alice")
    void withdrawWithInsufficientFundsShowsError() throws Exception {
        when(accountService.findAccountByUsername("alice")).thenReturn(alice);
        doThrow(new RuntimeException("Insufficient funds")).when(accountService).withdraw(eq(alice), any());

        mockMvc.perform(post("/withdraw").with(csrf()).param("amount", "500.00"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("error", "Insufficient funds"))
                .andExpect(content().string(containsString("Insufficient funds")));
    }

    @Test
    @WithMockUser(username = "alice")
    void transferRedirectsToDashboard() throws Exception {
        when(accountService.findAccountByUsername("alice")).thenReturn(alice);

        mockMvc.perform(post("/transfer").with(csrf()).param("toUsername", "bob").param("amount", "30.00"))
                .andExpect(redirectedUrl("/dashboard"));
        verify(accountService).transferAmount(alice, "bob", new BigDecimal("30.00"));
    }

    @Test
    @WithMockUser(username = "alice")
    void transferToUnknownRecipientShowsError() throws Exception {
        when(accountService.findAccountByUsername("alice")).thenReturn(alice);
        doThrow(new RuntimeException("Recipient account not found"))
                .when(accountService).transferAmount(eq(alice), eq("ghost"), any());

        mockMvc.perform(post("/transfer").with(csrf()).param("toUsername", "ghost").param("amount", "30.00"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("error", "Recipient account not found"));
    }

    @Test
    @WithMockUser(username = "alice")
    void transactionsShowsHistory() throws Exception {
        Transaction deposit = new Transaction(new BigDecimal("50.00"), "Deposit", LocalDateTime.now(), alice);
        deposit.setId(11L);
        Transaction withdrawal = new Transaction(new BigDecimal("20.00"), "Withdrawal", LocalDateTime.now(), alice);
        withdrawal.setId(12L);
        when(accountService.findAccountByUsername("alice")).thenReturn(alice);
        when(accountService.getTransactionHistory(alice)).thenReturn(List.of(deposit, withdrawal));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(view().name("transactions"))
                .andExpect(model().attribute("transactions", List.of(deposit, withdrawal)))
                .andExpect(content().string(containsString("<td class=\"text-success\">50.00</td>")))
                .andExpect(content().string(containsString("<td class=\"text-danger\">20.00</td>")));
    }
}
