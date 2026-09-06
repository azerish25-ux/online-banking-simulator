package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * F26 - the history feed pages with a keyset cursor over the immutable
 * (created_at, seq) ordering key, never an OFFSET.
 *
 * <p>Every deposit in these tests shares one fixed business clock, so rows tie
 * exactly on created_at and only the DB-assigned seq can order them - the
 * condition that made offset paging fragile. The acceptance cases are the
 * ones OFFSET gets wrong: rows inserted BETWEEN two page reads must not shift
 * the older pages (no duplicate, no skip), equal timestamps must page exactly
 * once each, the final page must end with a null cursor and the exact union
 * of identities, and a mutable review-state change (HELD → POSTED) must not
 * reorder the feed. A cursor is a position, not a snapshot promise - new rows
 * are visible by starting a fresh page, and a changed date window resets
 * paging to that window's newest page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HistoryKeysetPaginationTest {

  /** Shared mutable clock handed to every bean in this context. */
  private static final SettableClock CLOCK = new SettableClock();

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  ApiTestClient client;

  @BeforeEach
  void freezeClock() {
    CLOCK.set(Instant.parse("2026-05-01T12:00:00Z"));
    client = new ApiTestClient(mvc, json);
  }

  /** Twenty-two deposits at ONE instant, walked three pages of ten. */
  @Test
  void equalTimestampsPageExactlyOnceWithExactIdentityUnion() throws Exception {
    String token = client.register("keyset-a@example.com", "Keyset A");
    String accountId = client.accountId(token);
    seed(token, accountId, 22);
    // All rows share created_at; the feed is newest-inserted-first (seq DESC).
    List<String> minted = feedIds(token, accountId, 22);

    List<String> walked = walkAll(token, accountId, 10, null);
    assertEquals(22, walked.size(), "every row, exactly once");
    assertEquals(new HashSet<>(minted), new HashSet<>(walked), "exact union of identities");
    assertEquals(minted, walked,
        "equal created_at resolves newest-inserted-first (seq DESC) on every page");
  }

  @Test
  void insertionBetweenPagesDoesNotDuplicateOrSkipOlderRows() throws Exception {
    String token = client.register("keyset-b@example.com", "Keyset B");
    String accountId = client.accountId(token);
    seed(token, accountId, 15);
    // feedIds(15) is newest-first: index 0 is the last-created deposit.
    List<String> first = feedIds(token, accountId, 15);

    Page pageOne = fetch(token, accountId, 5, null);
    assertEquals(first.subList(0, 5), pageOne.ids, "page 1 is the five newest");
    assertTrue(pageOne.nextCursor != null);

    // A NEW deposit lands while the client is browsing. An OFFSET reader would
    // shift everything: page 2 (offset 5) would repeat first[4] and skip
    // first[9]. The keyset cursor is a position, so page 2 is exactly the rows
    // older than page 1's last row.
    CLOCK.set(Instant.parse("2026-05-01T13:00:00Z"));
    client.deposit(token, accountId, "1.00");
    String newcomer = feedIds(token, accountId, 1).get(0);

    Page pageTwo = fetch(token, accountId, 5, pageOne.nextCursor);
    assertEquals(first.subList(5, 10), pageTwo.ids,
        "insertion must not duplicate or skip rows across the boundary");
    Page pageThree = fetch(token, accountId, 5, pageTwo.nextCursor);
    assertEquals(first.subList(10, 15), pageThree.ids, "final boundary is the oldest five");
    assertEquals(null, pageThree.nextCursor, "no nextCursor on the final page");

    // The live model: the newcomer is visible by starting a fresh page - not
    // smuggled into an older cursor walk.
    Page fresh = fetch(token, accountId, 5, null);
    assertEquals(List.of(newcomer, first.get(0), first.get(1), first.get(2), first.get(3)),
        fresh.ids, "a fresh page leads with the newly committed row");
  }

  @Test
  void changedDateWindowRestartsAtThatWindowsNewestPage() throws Exception {
    String token = client.register("keyset-c@example.com", "Keyset C");
    String accountId = client.accountId(token);
    seed(token, accountId, 2); // May 1
    CLOCK.set(Instant.parse("2026-05-02T09:00:00Z"));
    client.deposit(token, accountId, "1.00");
    client.deposit(token, accountId, "1.00");
    Set<String> may2 = new HashSet<>(feedIds(token, accountId, 2));
    CLOCK.set(Instant.parse("2026-05-03T09:00:00Z"));
    client.deposit(token, accountId, "1.00");
    String may3 = feedIds(token, accountId, 1).get(0);

    // The cursor is bound to the window that produced it; a filter change is a
    // new browse, so the client restarts without a cursor.
    Page may2Only = fetchWindow(token, accountId, 20, null, "2026-05-02", "2026-05-02");
    assertEquals(2, may2Only.ids.size(), "window holds exactly the two May-2 deposits");
    assertEquals(may2, new HashSet<>(may2Only.ids));
    assertTrue(may2Only.nextCursor == null, "both rows fit one page");

    Page sinceMay3 = fetchWindow(token, accountId, 20, null, "2026-05-03", null);
    assertEquals(List.of(may3), sinceMay3.ids, "the newer window leads with its own newest");
  }

  /**
   * An operator resolving a HELD instruction changes mutable review state on
   * an immutable history row - the feed's identity and order must not move.
   */
  @Test
  void reviewStateChangeDoesNotReorderTheFeed() throws Exception {
    String alice = client.register("keyset-d@example.com", "Keyset D");
    String bob = client.register("keyset-e@example.com", "Keyset E");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "15000.00");
    String held = client.transferWithKey(alice, bobIban, "10000.00", "keyset-held-review");
    client.deposit(alice, aliceId, "1.00");
    client.deposit(alice, aliceId, "1.00");

    List<String> before = walkAll(alice, aliceId, 20, null);
    assertTrue(before.contains(held), "the HELD intent is in the feed");

    mvc.perform(post("/api/v1/admin/transactions/" + held + "/review")
            .header("Authorization", "Bearer " + client.adminToken())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    List<String> after = walkAll(alice, aliceId, 20, null);
    assertEquals(before, after, "resolving the hold must not reorder the immutable feed");
    JsonNode row = rowById(alice, aliceId, 20, held);
    assertEquals("POSTED", row.get("status").asText(), "reviewed row settles in place");
  }

  // --- helpers ------------------------------------------------------------

  private record Page(List<String> ids, String nextCursor) {}

  /** Seeds {@code n} deposits at the current clock instant (no id returned - deposit's body is the account). */
  private void seed(String token, String accountId, int n) throws Exception {
    for (int i = 0; i < n; i++) {
      client.deposit(token, accountId, "1.00");
    }
  }

  /** Newest-first ids of the account's feed (all deposits unless the window says otherwise). */
  private List<String> feedIds(String token, String accountId, int size) throws Exception {
    return fetch(token, accountId, size, null).ids;
  }

  private Page fetch(String token, String accountId, int size, String cursor) throws Exception {
    return fetchWindow(token, accountId, size, cursor, null, null);
  }

  private Page fetchWindow(String token, String accountId, int size, String cursor,
      String from, String to) throws Exception {
    var builder = get("/api/v1/transactions")
        .header("Authorization", "Bearer " + token)
        .param("accountId", accountId)
        .param("size", String.valueOf(size));
    if (cursor != null) {
      builder.param("cursor", cursor);
    }
    if (from != null) {
      builder.param("from", from);
    }
    if (to != null) {
      builder.param("to", to);
    }
    MvcResult result = mvc.perform(builder)
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = json.readValue(result.getResponse().getContentAsString(), JsonNode.class);
    List<String> ids = new ArrayList<>();
    for (JsonNode row : body.get("items")) {
      ids.add(row.get("id").asText());
    }
    JsonNode nc = body.get("nextCursor");
    return new Page(ids, nc == null || nc.isNull() ? null : nc.asText());
  }

  private List<String> walkAll(String token, String accountId, int size, String unusedCursor)
      throws Exception {
    List<String> all = new ArrayList<>();
    String cursor = null;
    do {
      Page page = fetch(token, accountId, size, cursor);
      assertTrue(all.addAll(page.ids), "a row repeated across pages: " + page.ids);
      cursor = page.nextCursor;
    } while (cursor != null);
    return all;
  }

  private JsonNode rowById(String token, String accountId, int size, String id) throws Exception {
    for (JsonNode row : json.readValue(mvc.perform(get("/api/v1/transactions")
            .header("Authorization", "Bearer " + token)
            .param("accountId", accountId)
            .param("size", String.valueOf(size)))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(), JsonNode.class).get("items")) {
      if (id.equals(row.get("id").asText())) {
        return row;
      }
    }
    throw new AssertionError("row " + id + " not on the first page");
  }

  /** A clock a test can wind forward - reset to May 1 in {@link #freezeClock()}. */
  private static final class SettableClock extends Clock {
    private Instant instant = Instant.parse("2026-05-01T12:00:00Z");

    void set(Instant value) {
      instant = value;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
