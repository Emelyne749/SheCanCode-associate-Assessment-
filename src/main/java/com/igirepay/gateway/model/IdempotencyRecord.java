package com.igirepay.gateway.model;

import java.time.Instant;

/**
 * Internal record stored for each unique Idempotency-Key seen by the gateway.
 * Tracks the request fingerprint, processing state, and cached response.
 */
public class IdempotencyRecord {

    public enum State {
        IN_FLIGHT,  // Currently being processed
        COMPLETED   // Processing finished; cached response is available
    }

    private final String idempotencyKey;
    private final String requestBodyHash;   // SHA-256 of the original request body
    private final PaymentRequest originalRequest;
    private final Instant createdAt;

    private volatile State state;
    private volatile PaymentResponse cachedResponse;
    private volatile int cachedStatusCode;

    public IdempotencyRecord(String idempotencyKey, String requestBodyHash, PaymentRequest originalRequest) {
        this.idempotencyKey = idempotencyKey;
        this.requestBodyHash = requestBodyHash;
        this.originalRequest = originalRequest;
        this.createdAt = Instant.now();
        this.state = State.IN_FLIGHT;
    }

    public synchronized void complete(PaymentResponse response, int statusCode) {
        this.cachedResponse = response;
        this.cachedStatusCode = statusCode;
        this.state = State.COMPLETED;
        this.notifyAll(); // wake up any threads waiting on this record
    }

    /**
     * Blocks the calling thread until the record transitions to COMPLETED.
     * Used to handle concurrent duplicate requests (race-condition guard).
     */
    public synchronized PaymentResponse awaitCompletion() throws InterruptedException {
        while (state == State.IN_FLIGHT) {
            this.wait(100);
        }
        return cachedResponse;
    }

    // --- Getters ---

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestBodyHash() {
        return requestBodyHash;
    }

    public PaymentRequest getOriginalRequest() {
        return originalRequest;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public State getState() {
        return state;
    }

    public PaymentResponse getCachedResponse() {
        return cachedResponse;
    }

    public int getCachedStatusCode() {
        return cachedStatusCode;
    }
}
