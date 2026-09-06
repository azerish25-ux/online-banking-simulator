package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * F28 - public landing numbers classify transfers by KIND and posted status,
 * never by row shape.
 *
 * <p>The pre-F28 query counted every settled row that had a from side. The
 * interest engine posts LOAN charges with a from side and no to side, so a
 * busy interest run inflated the hero's "transfers" figure with engine
 * bookkeeping. This matrix pins the policy: exactly POSTED rows the rail
 * labelled TRANSFER count toward transfers/volume - deposits, savings
 * interest credits, loan interest charges, and HELD/CANCELLED instructions
 * all stay out, whichever side they carry.
 */
@org.springframework.boot.test.context.SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class PublicStatsClassificationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TransactionRepository transactions;
  @Autowired CacheManager cacheManager;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, json);
  }

  private record Stats(long transfers, BigDecimal volume) {}

  @Test
  void onlyPostedTransferRowsCountTowardTransfersAndVolume() throws Exception {
    String alice = client.register("stats-kind-a@example.com", "Stats Kind A");
    String bob = client.register("stats-kind-b@example.com", "Stats Kind B");
    String aliceChecking = client.accountId(alice);
    String bobChecking = client.accountId(bob);
    String aliceSavings = openAccount(alice, "SAVINGS");
    String aliceLoan = openAccount(alice, "LOAN");
    String bobLoan = openAccount(bob, "LOAN");

    Stats before = stats();

    // DEPOSIT - funds the account, from-side null. Never a transfer.
    row(UUID.fromString(aliceChecking), null, "400.00", TxKind.DEPOSIT, TxStatus.POSTED, "deposit");
    // Savings interest - an engine credit, to-only. Never a transfer.
    row(null, UUID.fromString(aliceSavings), "77.77", TxKind.INTEREST, TxStatus.POSTED, "savings interest");
    // Loan interest - an engine charge WITH a from side and no to side. The
    // old "from IS NOT NULL" classifier counted this as a user transfer.
    row(UUID.fromString(aliceLoan), null, "999.99", TxKind.INTEREST, TxStatus.POSTED, "loan interest");
    // A second loan charge on another borrower for good measure.
    row(UUID.fromString(bobLoan), null, "333.33", TxKind.INTEREST, TxStatus.POSTED, "loan interest");
    // HELD instruction - a TRANSFER row that never moved money yet.
    row(UUID.fromString(aliceChecking), UUID.fromString(bobChecking), "5000.00", TxKind.TRANSFER, TxStatus.HELD, "held");
    // CANCELLED instruction - declined by operations, money never moved.
    row(UUID.fromString(aliceChecking), UUID.fromString(bobChecking), "200.00", TxKind.TRANSFER, TxStatus.CANCELLED, "cancelled");
    // The ONE genuine user transfer that actually posted.
    row(UUID.fromString(aliceChecking), UUID.fromString(bobChecking), "12.34", TxKind.TRANSFER, TxStatus.POSTED, "real transfer");

    statsCache().clear();
    Stats after = stats();

    assertEquals(before.transfers() + 1, after.transfers(),
        "exactly the POSTED TRANSFER row counts - engine interest, deposits and "
            + "HELD/CANCELLED instructions excluded");
    assertEquals(before.volume().add(new BigDecimal("12.3400")), after.volume(),
        "volume sums only POSTED TRANSFER rows - the 999.99 loan charge must not leak in");
  }

  private String openAccount(String token, String type) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"%s\"}".formatted(type)))
        .andExpect(status().isCreated())
        .andReturn();
    return json.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();
  }

  /** Inserts a ledger row in the exact shape the rail creates, without running the rail. */
  private void row(UUID from, UUID to, String amount, TxKind kind, TxStatus status, String memo) {
    Transaction tx = new Transaction();
    tx.setFromAccountId(from);
    tx.setToAccountId(to);
    tx.setAmount(new BigDecimal(amount));
    tx.setCurrency("USD");
    tx.setKind(kind);
    tx.setStatus(status);
    tx.setMemo(memo);
    tx.setFlagged(false);
    tx.setReviewed(true);
    Instant now = Instant.now();
    tx.setCreatedAt(now);
    if (status == TxStatus.POSTED) {
      tx.setPostedAt(now);
    }
    transactions.save(tx);
  }

  private Stats stats() throws Exception {
    MvcResult result = mvc.perform(get("/api/public/stats"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = json.readValue(result.getResponse().getContentAsString(), JsonNode.class);
    return new Stats(body.get("transfers").asLong(), new BigDecimal(body.get("volume").asText()));
  }

  private org.springframework.cache.Cache statsCache() {
    return cacheManager.getCache("public-stats");
  }
}
