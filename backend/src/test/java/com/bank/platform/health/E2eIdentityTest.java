package com.bank.platform.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The e2e identity endpoint must refuse any target that is
 * not THIS run before the first write:
 *
 * <ul>
 *   <li>without a configured marker the endpoint is 404 (never a public
 *       "test mode" beacon);</li>
 *   <li>a probe carrying the wrong/absent marker is 403 and learns nothing;</li>
 *   <li>the exact run marker round-trips and names the REAL database
 *       connection (product, version, URL, role) from live JDBC metadata.</li>
 * </ul>
 */
@SpringBootTest(properties = "app.e2e.marker=unit-run-42")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class E2eIdentityTest {

  @Autowired MockMvc mvc;

  @Test
  void wrongOrAbsentMarkerIsRefusedBeforeAnyDetails() throws Exception {
    mvc.perform(get("/api/e2e/identity"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/e2e/identity").header("X-E2E-Marker", "some-other-run"))
        .andExpect(status().isForbidden());
  }

  @Test
  void matchingMarkerNamesTheRunAndItsActualDatabase() throws Exception {
    mvc.perform(get("/api/e2e/identity").header("X-E2E-Marker", "unit-run-42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.marker").value("unit-run-42"))
        .andExpect(jsonPath("$.testMode").value(true))
        .andExpect(jsonPath("$.version").exists())
        // The DB identity is live JDBC metadata - the H2 test database here,
        // never an environment variable named TEST.
        .andExpect(jsonPath("$.db.product").isString())
        .andExpect(jsonPath("$.db.url").isString())
        .andExpect(jsonPath("$.db.user").isString());
  }
}

/** Production context: no marker configured - the control does not exist. */
@SpringBootTest(properties = "app.e2e.marker=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionE2eIdentityTest {

  @Autowired MockMvc mvc;

  @Test
  void productionWithoutMarkerHasNoIdentityControl() throws Exception {
    // 404 - a health scanner cannot tell the control exists, and a stale
    // leftover server would have to be booted WITH this run's marker to pass.
    mvc.perform(get("/api/e2e/identity"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/e2e/identity").header("X-E2E-Marker", "unit-run-42"))
        .andExpect(status().isNotFound());
  }
}
