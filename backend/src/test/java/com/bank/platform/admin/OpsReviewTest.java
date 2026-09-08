package com.bank.platform.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class OpsReviewTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @Test
  void largeTransferFlaggedReviewedAndReported() throws Exception {
    String admin = login("admin-test@bank.local", "admin-test-123");
    String alice = register("ops-a@example.com", "Ops Alice");
    String bob = register("ops-b@example.com", "Ops Bob");
    String aliceId = accountId(alice);
    String bobIban = accountIban(bob);

    deposit(alice, aliceId, "20000.00");

    // A five-figure transfer is auto-flagged AND HELD (money has not moved
    // yet); a small one settles instantly and is not flagged.
    String bigId = transfer(alice, bobIban, "15000.00", true);
    transfer(alice, bobIban, "10.00", false);

    // While held, the sender keeps their funds (only the $10 small transfer
    // has moved) and the recipient has just that $10.
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].balance").value("19990.0000"));
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + bob))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].balance").value("10.0000"));

    // Review queue filters to the flagged, unreviewed items (held transfer +
    // flagged deposit), newest first.
    mvc.perform(get("/api/v1/admin/transactions").header("Authorization", "Bearer " + admin)
            .param("flagged", "true")
            .param("reviewed", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(bigId))
        .andExpect(jsonPath("$.content[0].flagged").value(true))
        .andExpect(jsonPath("$.content[0].status").value("HELD"));

    // Customers cannot touch the review queue.
    mvc.perform(get("/api/v1/admin/transactions").header("Authorization", "Bearer " + alice))
        .andExpect(status().isForbidden());

    // Approving settles the held transfer: money moves and the flag clears.
    mvc.perform(post("/api/v1/admin/transactions/" + bigId + "/review")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewed").value(true))
        .andExpect(jsonPath("$.status").value("POSTED"));
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].balance").value("4990.0000"));
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + bob))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].balance").value("15010.0000"));
    mvc.perform(get("/api/v1/admin/transactions").header("Authorization", "Bearer " + admin)
            .param("flagged", "true")
            .param("reviewed", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].amount").value("20000.0000"));
    mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer " + admin)
            .param("action", "TRANSFER_APPROVED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].entityId").value(bigId));

    // Daily totals include today with real volume.
    mvc.perform(get("/api/v1/admin/reports/daily-totals").header("Authorization", "Bearer " + admin)
            .param("days", "7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(7))
        .andExpect(jsonPath("$[6].transfers").value(2));

    // PDF statement downloads as a real PDF.
    MvcResult pdf = mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.pdf")
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/pdf"))
        .andReturn();
    byte[] bytes = pdf.getResponse().getContentAsByteArray();
    assertTrue(bytes.length > 500, "PDF should have content");
    assertTrue(bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F',
        "PDF must start with %PDF");

    // An impossible window is rejected, not silently empty: a "from" in the
    // future with no "to" defaults to a window that ends before it starts
    // (the old behavior rendered a header-only CSV AND a backward "Period
    // <future> to <today>" label on the PDF). A valid dated range still works.
    mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.csv")
            .header("Authorization", "Bearer " + alice)
            .param("from", LocalDate.now().plusDays(30).toString()))
        .andExpect(status().isBadRequest());
    MvcResult csv = mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.csv")
            .header("Authorization", "Bearer " + alice)
            .param("from", LocalDate.now().minusDays(1).toString()))
        .andExpect(status().isOk())
        .andReturn();
    String csvBody = csv.getResponse().getContentAsString();
    assertTrue(csvBody.startsWith("id,posted_at"), "CSV keeps its header");
  }

  @Test
  void declineKeepsMoneyPutAndOnlyHeldRowsAreDeclinable() throws Exception {
    String admin = login("admin-test@bank.local", "admin-test-123");
    String alice = register("ops-d@example.com", "Ops Decline");
    String bob = register("ops-e@example.com", "Ops Decline B");
    String aliceId = accountId(alice);
    String bobIban = accountIban(bob);
    deposit(alice, aliceId, "20000.00");

    String heldId = transfer(alice, bobIban, "12000.00", true);

    // Decline: the row is CANCELLED, no money has moved, the queue drains to
    // the flagged deposit only, and the decision is audited.
    mvc.perform(post("/api/v1/admin/transactions/" + heldId + "/decline")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.reviewed").value(true));
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].balance").value("20000.0000"));
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + bob))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].balance").value("0"));
    mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer " + admin)
            .param("action", "TRANSFER_DECLINED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].entityId").value(heldId));

    // A second decision on the same row is a stale-decision CONFLICT (409):
    // the losing operator is told the case already moved so the queue
    // refreshes to the winning outcome - never an optimistic second toast.
    mvc.perform(post("/api/v1/admin/transactions/" + heldId + "/decline")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isConflict());
    mvc.perform(post("/api/v1/admin/transactions/" + heldId + "/review")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isConflict());
  }

  @Test
  void staleExpectedStateAnswersConflictAndMatchingStateSettles() throws Exception {
    String admin = login("admin-test@bank.local", "admin-test-123");
    String alice = register("ops-race@example.com", "Ops Race");
    String bob = register("ops-race-b@example.com", "Ops Race B");
    String aliceId = accountId(alice);
    String bobIban = accountIban(bob);
    deposit(alice, aliceId, "30000.00");
    String heldId = transfer(alice, bobIban, "12000.00", true);

    // Two operators race. The loser's console still shows the case HELD and
    // unreviewed, so its body carries the state it SAW. The winner settles
    // first with a matching expected state.
    String body = """
        {"reason":"Approved after funds verification","expectedStatus":"HELD","expectedReviewed":false}""";
    mvc.perform(post("/api/v1/admin/transactions/" + heldId + "/review")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("POSTED"))
        .andExpect(jsonPath("$.reviewed").value(true));

    // The loser's console now answers a stale decision: 409 naming the case's
    // CURRENT state, so the queue refreshes to the winning outcome instead of
    // preserving an optimistic toast.
    mvc.perform(post("/api/v1/admin/transactions/" + heldId + "/review")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Decision Conflict"));

    // A reviewed-state mismatch is equally stale (posted deposit was already
    // acknowledged by the other operator).
    mvc.perform(post("/api/v1/admin/transactions/" + heldId + "/decline")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"reason":"Late decline","expectedStatus":"HELD","expectedReviewed":false}"""))
        .andExpect(status().isConflict());

    // A decision reason is recorded on the audit trail - bounded and plain,
    // never a sensitive payload dump.
    mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer " + admin)
            .param("action", "TRANSFER_APPROVED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].entityId").value(heldId))
        .andExpect(jsonPath("$.content[0].metadata.reason")
            .value("Approved after funds verification"));
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

  private String login(String email, String password) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"%s"}""".formatted(email, password)))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  private String transfer(String token, String toIban, String amount, boolean flagged) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"%s"}""".formatted(toIban, amount)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.flagged").value(flagged))
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();
  }

  private void deposit(String token, String accountId, String amount) throws Exception {
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "dep-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"%s"}""".formatted(amount)))
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
