package com.bank.platform.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer feed's filters (amount range, kind, state,
 * reference/counterparty search) are SERVER-BACKED - SQL predicates over the
 * whole account history, never a client-side filter of the loaded page. The
 * same predicates feed the keyset page and its total, validation rejects a
 * bad range/term before any query, and a search term is matched literally
 * against memo and the counter-party IBAN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Rolls the fixture back after each method: this class runs before the
// Journal* suites in a shared in-memory DB, and those suites assert GLOBAL
// journal counts - committed fixture rows would leak into them (the pattern
// every ledger MockMvc suite in this module follows).
@Transactional
class HistoryServerFiltersTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  /** Per-method fixture: distinct emails per tag (the DB persists between methods). */
  private record Seed(String aliceToken, String aliceId, String bobIban) {}

  private Seed seed(String tag) throws Exception {
    String alice = register(tag + "-a@example.com", "Hist Filters A");
    String bob = register(tag + "-b@example.com", "Hist Filters B");
    String aliceId = accountId(alice);
    String bobIban = accountIban(bob);
    deposit(alice, aliceId, "30000.00");
    // Two kinds, two states, three memos, two counterparties' worth of rows:
    //   DEPOSIT 30000.00 (seed), TRANSFER 550.00 rent, TRANSFER 15000.00
    //   kitchen (HELD - at/above the $10k review threshold, money never
    //   moved), TRANSFER 3.50 coffee, DEPOSIT 77.00.
    transfer(alice, bobIban, "550.00", "Rent for september");
    transfer(alice, bobIban, "15000.00", "Kitchen renovation"); // HELD
    transfer(alice, bobIban, "3.50", "coffee");
    deposit(alice, aliceId, "77.00");
    return new Seed(alice, aliceId, bobIban);
  }

  @Test
  void kindFilterRestrictsRowsToThatKind() throws Exception {
    Seed s = seed("hf-kind");
    // Only TRANSFER rows: deposits must not leak into the page or the total.
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("kind", "TRANSFER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.items[0].kind").value("TRANSFER"));
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("kind", "DEPOSIT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2));
  }

  @Test
  void statusFilterRestrictsToHeldOnlyAndRidesTheCursorPage() throws Exception {
    Seed s = seed("hf-state");
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("status", "HELD"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].status").value("HELD"));
  }

  @Test
  void amountRangeIsAnExactLedgerPredicateNotAPageFilter() throws Exception {
    Seed s = seed("hf-amt");
    // minAmount inclusive, maxAmount inclusive, full 4-decimal precision:
    // [77.00 .. 550.00] is exactly the 77.00 deposit + the 550.00 rent row.
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("minAmount", "77.0000")
            .param("maxAmount", "550.0000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2)); // deposit 77.00 + rent 550.00
    // A lower bound only: the 15000 HELD kitchen row AND the 30000 seed.
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("minAmount", "10000.0000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2)); // kitchen 15000 + seed 30000
  }

  @Test
  void searchMatchesMemoAndCounterpartyIbanLiterally() throws Exception {
    Seed s = seed("hf-search");
    // Memo search (case-insensitive) over the WHOLE history.
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("q", "RENT FOR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].memo").value("Rent for september"));
    // Counterparty search: the last six of bob's IBAN appear on Alice's
    // history rows, matched from the OTHER leg's account, not Alice's.
    String tail = s.bobIban.substring(s.bobIban.length() - 6);
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("q", tail))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3)); // three transfers to bob
    // LIKE metacharacters are literal: no memo contains %, so nothing matches.
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("q", "100%"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  void invalidFiltersAreRejectedBeforeAnyQuery() throws Exception {
    Seed s = seed("hf-invalid");
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("minAmount", "abc"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("minAmount", "10.0000")
            .param("maxAmount", "5.0000"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("kind", "WIRE"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + s.aliceToken)
            .param("accountId", s.aliceId)
            .param("status", "POSTED")
            .param("q", "x"))
        .andExpect(status().isBadRequest());
  }

  // ---- helpers (mirror ApiTestClient's API) ----

  private String register(String email, String name) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"secret123\",\"fullName\":\"%s\"}"
                .formatted(email, name)))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  private void transfer(String token, String toIban, String amount, String memo) throws Exception {
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"%s\",\"memo\":\"%s\"}"
                .formatted(toIban, amount, memo)))
        .andExpect(status().isCreated());
  }

  private void deposit(String token, String accountId, String amount) throws Exception {
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"%s\"}".formatted(amount)))
        .andExpect(status().isOk());
  }

  private String accountIban(String token) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get(0).get("iban").asText();
  }

  private String accountId(String token) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get(0).get("id").asText();
  }
}
