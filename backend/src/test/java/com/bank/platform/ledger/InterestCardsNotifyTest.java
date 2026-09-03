package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InterestCardsNotifyTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired InterestService interestService;

  @Test
  void savingsEarnLoanChargesAndSecondRunIsNoOp() throws Exception {
    String token = register("p8a@example.com", "P Eight");
    String savingsId = openAccount(token, "SAVINGS");
    String loanId = openAccount(token, "LOAN");

    deposit(token, savingsId, "1200.00");
    // Spend the loan into overdraft: -200.
    String savingsIban = accountIban(token, savingsId);
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"200.00","fromAccountId":"%s"}"""
                .formatted(savingsIban, loanId)))
        .andExpect(status().isCreated());

    // Beyond the 1000 limit is rejected.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"900.00","fromAccountId":"%s"}"""
                .formatted(savingsIban, loanId)))
        .andExpect(status().isUnprocessableEntity());

    assertEquals(2, interestService.accrueMonthly().get("accrued"));
    // Savings: 1200 deposit + 200 transfer in, then 1400*0.04/12 interest.
    // Loan: -200 overdraft, then -200*0.12/12 charge.
    expectBalance(token, savingsId, "1404.6667");
    expectBalance(token, loanId, "-202.0000");
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
    expectBalance(token, savingsId, "1404.6667");
  }

  @Test
  void cardsIssueOnceListMaskedAndFreeze() throws Exception {
    String token = register("p8b@example.com", "P Eight B");
    String checkingId = accountId(token);

    MvcResult issued = mvc.perform(post("/api/v1/accounts/" + checkingId + "/cards")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.pan").isNotEmpty())
        .andExpect(jsonPath("$.cvv").isNotEmpty())
        .andReturn();
    JsonNode node = objectMapper.readValue(issued.getResponse().getContentAsString(), JsonNode.class);
    assertTrue(luhnValid(node.get("pan").asText()), "issued PAN must be Luhn-valid");
    String cardId = node.get("id").asText();

    // Listing never leaks the PAN.
    mvc.perform(get("/api/v1/accounts/" + checkingId + "/cards")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].last4").value(node.get("pan").asText().substring(12)))
        .andExpect(jsonPath("$[0].pan").doesNotExist());

    mvc.perform(post("/api/v1/cards/" + cardId + "/freeze")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"));
  }

  @Test
  void transferNotifiesRecipientAndReadClears() throws Exception {
    String alice = register("p8c-a@example.com", "P Eight CA");
    String bob = register("p8c-b@example.com", "P Eight CB");
    String aliceId = accountId(alice);
    String bobIban = accountIban(bob);

    deposit(alice, aliceId, "50.00");
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"5.00"}""".formatted(bobIban)))
        .andExpect(status().isCreated());

    MvcResult unread = mvc.perform(get("/api/v1/notifications/unread-count")
            .header("Authorization", "Bearer " + bob))
        .andExpect(status().isOk())
        .andReturn();
    int before = objectMapper.readValue(unread.getResponse().getContentAsString(), JsonNode.class)
        .get("unread").asInt();
    assertTrue(before >= 1, "recipient should have notifications");

    MvcResult list = mvc.perform(get("/api/v1/notifications?size=5")
            .header("Authorization", "Bearer " + bob))
        .andExpect(status().isOk())
        .andReturn();
    String notifId = objectMapper.readValue(list.getResponse().getContentAsString(), JsonNode.class)
        .get("content").get(0).get("id").asText();
    mvc.perform(post("/api/v1/notifications/" + notifId + "/read")
            .header("Authorization", "Bearer " + bob))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.read").value(true));
  }

  private static boolean luhnValid(String pan) {
    int sum = 0;
    boolean doubleIt = false;
    for (int i = pan.length() - 1; i >= 0; i--) {
      int digit = pan.charAt(i) - '0';
      if (doubleIt) {
        digit *= 2;
        if (digit > 9) digit -= 9;
      }
      sum += digit;
      doubleIt = !doubleIt;
    }
    return pan.length() == 16 && sum % 10 == 0;
  }

  private String register(String email, String name) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"secret123","fullName":"%s"}""".formatted(email, name)))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  private String openAccount(String token, String type) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"%s"}""".formatted(type)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value(type))
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();
  }

  private void deposit(String token, String accountId, String amount) throws Exception {
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"%s"}""".formatted(amount)))
        .andExpect(status().isOk());
  }

  private void expectBalance(String token, String accountId, String expected) throws Exception {
    mvc.perform(get("/api/v1/accounts/" + accountId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(expected));
  }

  private String accountIban(String token) throws Exception {
    return accountField(token, null, "iban");
  }

  private String accountIban(String token, String accountId) throws Exception {
    return accountField(token, accountId, "iban");
  }

  private String accountId(String token) throws Exception {
    return accountField(token, null, "id");
  }

  private String accountField(String token, String accountId, String field) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode arr = objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class);
    for (JsonNode node : arr) {
      if (accountId == null || node.get("id").asText().equals(accountId)) {
        return node.get(field).asText();
      }
    }
    throw new IllegalStateException("account not found");
  }
}
