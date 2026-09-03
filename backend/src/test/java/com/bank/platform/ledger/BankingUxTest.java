package com.bank.platform.ledger;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class BankingUxTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @Test
  void beneficiariesCrud() throws Exception {
    String token = register("ux1@example.com", "Ux One");

    // Create.
    MvcResult created = mvc.perform(post("/api/v1/beneficiaries")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Landlord","iban":"DE44500105175407324931"}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.iban").value("DE44500105175407324931"))
        .andReturn();
    String id = objectMapper.readValue(created.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();

    // Duplicate IBAN is a conflict.
    mvc.perform(post("/api/v1/beneficiaries")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Landlord 2","iban":"de44500105175407324931"}"""))
        .andExpect(status().isConflict());

    // Bad IBAN is rejected.
    mvc.perform(post("/api/v1/beneficiaries")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"nickname":"Bogus","iban":"nope"}"""))
        .andExpect(status().isBadRequest());

    // List shows it.
    mvc.perform(get("/api/v1/beneficiaries").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].nickname").value("Landlord"));

    // Delete, then deleting again is 404.
    mvc.perform(delete("/api/v1/beneficiaries/" + id).header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/api/v1/beneficiaries/" + id).header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  @Test
  void statementCsvAndSummary() throws Exception {
    String alice = register("ux2a@example.com", "Ux Two A");
    String bob = register("ux2b@example.com", "Ux Two B");
    String aliceId = accountId(alice);
    String bobIban = accountIban(bob);

    mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"1000.00"}"""))
        .andExpect(status().isOk());

    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"250.00","memo":"Hello, \\"Bob\\"","fromAccountId":"%s"}"""
                .formatted(bobIban, aliceId)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value("250.0000"));

    // CSV statement downloads with header + rows (memo quoting survives the comma/quotes).
    mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.csv")
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", containsString("attachment")))
        .andExpect(content().string(containsString("id,created_at,from_iban,to_iban,amount,currency,memo,status")))
        .andExpect(content().string(containsString("\"Hello, \"\"Bob\"\"\"")))
        .andExpect(content().string(containsString("250.0000")));

    // Bob cannot download Alice's statement.
    mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.csv")
            .header("Authorization", "Bearer " + bob))
        .andExpect(status().isForbidden());

    // Monthly summary reflects deposit inflow and transfer outflow.
    mvc.perform(get("/api/v1/accounts/" + aliceId + "/summary")
            .header("Authorization", "Bearer " + alice)
            .param("months", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[1].inflow").value("1000.0000"))
        .andExpect(jsonPath("$[1].outflow").value("250.0000"));
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
