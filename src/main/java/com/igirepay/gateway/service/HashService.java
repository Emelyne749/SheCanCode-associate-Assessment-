package com.igirepay.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.igirepay.gateway.model.PaymentRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces a deterministic SHA-256 fingerprint of a PaymentRequest body.
 * Used for the User Story 3 check: same key, different body → 409 Conflict.
 *
 * We sort keys before hashing to ensure {"amount":100,"currency":"RWF"}
 * and {"currency":"RWF","amount":100} produce the same fingerprint.
 */
@Service
public class HashService {

    private final ObjectMapper sortedMapper;

    public HashService() {
        this.sortedMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hash(PaymentRequest request) {
        try {
            // Serialize with sorted keys for determinism
            String canonical = sortedMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash payment request", e);
        }
    }
}
