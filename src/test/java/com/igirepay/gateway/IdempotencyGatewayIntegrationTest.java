package com.igirepay.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igirepay.gateway.model.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyGatewayIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ENDPOINT = "/process-payment";

    // ──────────────────────────────────────────────────────────────────────────
    // User Story 1: Happy Path — first request is processed and returns 201
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void firstRequest_shouldReturn201AndChargeMessage() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = toJson(new PaymentRequest(new BigDecimal("100"), "RWF"));

        mockMvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Charged 100 RWF"))
                .andExpect(jsonPath("$.transactionId").exists());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // User Story 2: Duplicate request — returns same response with X-Cache-Hit
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void duplicateRequest_shouldReturnCachedResponseWithCacheHitHeader() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = toJson(new PaymentRequest(new BigDecimal("250"), "RWF"));

        // First request
        MvcResult first = mockMvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String firstBody = first.getResponse().getContentAsString();

        // Second (duplicate) request — must be fast and identical
        MvcResult second = mockMvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Cache-Hit", "true"))
                .andReturn();

        String secondBody = second.getResponse().getContentAsString();
        assertThat(secondBody).isEqualTo(firstBody);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // User Story 3: Same key, different body → 409 Conflict
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void sameKeyDifferentBody_shouldReturn409() throws Exception {
        String key = UUID.randomUUID().toString();

        // First request: 100 RWF
        mockMvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated());

        // Second request: same key, different amount
        mockMvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("500"), "RWF"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Idempotency key already used for a different request body."));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Missing Idempotency-Key header → 400 Bad Request
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void missingIdempotencyKey_shouldReturn400() throws Exception {
        String body = toJson(new PaymentRequest(new BigDecimal("100"), "RWF"));

        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required header: Idempotency-Key"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Invalid request body → 400 Validation error
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void invalidBody_missingAmount_shouldReturn400() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"RWF\"}"))
                .andExpect(status().isBadRequest());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private String toJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }
}
