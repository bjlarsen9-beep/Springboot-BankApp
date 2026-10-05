package com.example.bankapp.controller;

import com.example.bankapp.TestcontainersConfiguration;
import com.example.bankapp.model.Account;
import com.example.bankapp.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BankControllerTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccountService accountService;

    private Account register() {
        return accountService.registerAccount("user-" + UUID.randomUUID(), "secret");
    }

    private BigDecimal balanceOf(Account account) {
        return accountService.findAccountByUsername(account.getUsername()).getBalance();
    }

    @Test
    void protectedPagesRedirectAnonymousUsersToLogin() throws Exception {
        mvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginAndRegisterPagesArePublic() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(view().name("login"));
        mvc.perform(get("/register")).andExpect(status().isOk()).andExpect(view().name("register"));
    }

    @Test
    void registerRedirectsToLoginAndCreatesAccount() throws Exception {
        String username = "user-" + UUID.randomUUID();

        mvc.perform(post("/register").with(csrf()).param("username", username).param("password", "secret"))
                .andExpect(redirectedUrl("/login"));

        assertThat(accountService.findAccountByUsername(username).getBalance()).isEqualByComparingTo("0");
    }

    @Test
    void registerWithTakenUsernameShowsError() throws Exception {
        Account existing = register();

        mvc.perform(post("/register").with(csrf()).param("username", existing.getUsername()).param("password", "x"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(content().string(containsString("User already present.")));
    }

    @Test
    void formLoginWithValidCredentialsGoesToDashboard() throws Exception {
        Account account = register();

        mvc.perform(formLogin("/login").user(account.getUsername()).password("secret"))
                .andExpect(authenticated().withUsername(account.getUsername()))
                .andExpect(redirectedUrl("/dashboard"));
    }

    @Test
    void formLoginWithWrongPasswordIsRejected() throws Exception {
        Account account = register();

        mvc.perform(formLogin("/login").user(account.getUsername()).password("wrong"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void dashboardShowsCurrentBalance() throws Exception {
        Account account = register();
        accountService.deposit(account, new BigDecimal("42.00"));

        mvc.perform(get("/dashboard").with(user(account.getUsername())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome, " + account.getUsername())))
                .andExpect(content().string(containsString("Current Balance: $42.00")));
    }

    @Test
    void depositWithdrawAndTransferThroughTheWebLayer() throws Exception {
        Account sender = register();
        Account recipient = register();

        mvc.perform(post("/deposit").with(user(sender.getUsername())).with(csrf()).param("amount", "100"))
                .andExpect(redirectedUrl("/dashboard"));
        mvc.perform(post("/withdraw").with(user(sender.getUsername())).with(csrf()).param("amount", "30"))
                .andExpect(redirectedUrl("/dashboard"));
        mvc.perform(post("/transfer").with(user(sender.getUsername())).with(csrf())
                        .param("toUsername", recipient.getUsername()).param("amount", "20"))
                .andExpect(redirectedUrl("/dashboard"));

        assertThat(balanceOf(sender)).isEqualByComparingTo("50");
        assertThat(balanceOf(recipient)).isEqualByComparingTo("20");
    }

    @Test
    void withdrawingTooMuchShowsErrorOnDashboard() throws Exception {
        Account account = register();

        mvc.perform(post("/withdraw").with(user(account.getUsername())).with(csrf()).param("amount", "1"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(content().string(containsString("Insufficient funds")));
    }

    @Test
    void transactionsPageListsHistory() throws Exception {
        Account account = register();
        accountService.deposit(account, new BigDecimal("15"));

        mvc.perform(get("/transactions").with(user(account.getUsername())))
                .andExpect(status().isOk())
                .andExpect(view().name("transactions"))
                .andExpect(content().string(containsString("Deposit")));
    }
}
