package com.example.bankapp;

import com.example.bankapp.repository.AccountRepository;
import com.example.bankapp.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end flow through the real service, security config and H2 database. */
@SpringBootTest
@AutoConfigureMockMvc
class BankingFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private void register(String username) throws Exception {
        mockMvc.perform(post("/register").with(csrf()).param("username", username).param("password", "secret"))
                .andExpect(redirectedUrl("/login"));
    }

    private String balanceOf(String username) {
        return accountRepository.findByUsername(username).orElseThrow().getBalance().toPlainString();
    }

    @Test
    void registerLoginDepositWithdrawTransferAndViewTransactions() throws Exception {
        register("flow-alice");
        register("flow-bob");

        mockMvc.perform(formLogin("/login").user("flow-alice").password("secret"))
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(authenticated().withUsername("flow-alice"));

        mockMvc.perform(post("/deposit").with(user("flow-alice")).with(csrf()).param("amount", "100.00"))
                .andExpect(redirectedUrl("/dashboard"));
        mockMvc.perform(post("/withdraw").with(user("flow-alice")).with(csrf()).param("amount", "30.00"))
                .andExpect(redirectedUrl("/dashboard"));
        mockMvc.perform(post("/transfer").with(user("flow-alice")).with(csrf())
                        .param("toUsername", "flow-bob").param("amount", "20.00"))
                .andExpect(redirectedUrl("/dashboard"));

        assertThat(balanceOf("flow-alice")).isEqualTo("50.00");
        assertThat(balanceOf("flow-bob")).isEqualTo("20.00");

        Long aliceId = accountRepository.findByUsername("flow-alice").orElseThrow().getId();
        assertThat(transactionRepository.findByAccountId(aliceId)).hasSize(3);

        mockMvc.perform(get("/dashboard").with(user("flow-alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Current Balance: $50.00")));
        mockMvc.perform(get("/transactions").with(user("flow-alice")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Deposit")))
                .andExpect(content().string(containsString("Withdrawal")))
                .andExpect(content().string(containsString("Transfer Out to flow-bob")));
        mockMvc.perform(get("/transactions").with(user("flow-bob")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Transfer In from flow-alice")));
    }

    @Test
    void duplicateRegistrationShowsError() throws Exception {
        register("dup-user");

        mockMvc.perform(post("/register").with(csrf()).param("username", "dup-user").param("password", "other"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("User already present.")));
    }

    @Test
    void overdraftIsRejectedAndBalanceUnchanged() throws Exception {
        register("broke-user");

        mockMvc.perform(post("/withdraw").with(user("broke-user")).with(csrf()).param("amount", "1.00"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Insufficient funds")));
        assertThat(balanceOf("broke-user")).isEqualTo("0.00");
    }
}
