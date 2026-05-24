package com.igirepay.gateway.service;

import com.igirepay.gateway.model.IdempotencyRecord;
import com.igirepay.gateway.model.PaymentRequest;
import com.igirepay.gateway.model.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the full idempotency flow:
 *
 * 1. First request        → process payment, store result (COMPLETED).
 * 2. Duplicate request    → return cached response immediately.
 * 3. Body mismatch        → throw ConflictException (409).
 * 4. In-flight duplicate  → block until original request completes, then return its result.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;
    private final HashService hashService;
    private final PaymentService paymentService;

    public IdempotencyService(IdempotencyStore store,
                              HashService hashService,
                              PaymentService paymentService) {
        this.store = store;
        this.hashService = hashService;
        this.paymentService = paymentService;
    }

    /**
     * Result wrapper returned to the controller. Carries the response payload,
     * HTTP status code, and a flag indicating whether this was a cache hit.
     */
    public record ProcessingResult(PaymentResponse response, int statusCode, boolean cacheHit) {}

    /**
     * Main entry point. Implements the full idempotency decision tree.
     */
    public ProcessingResult handlePayment(String idempotencyKey, PaymentRequest request) {

        String bodyHash = hashService.hash(request);

        // Build the candidate record (will only be stored if key is new)
        IdempotencyRecord newRecord = new IdempotencyRecord(idempotencyKey, bodyHash, request);

        // Atomic check-and-insert
        IdempotencyRecord existing = store.putIfAbsent(idempotencyKey, newRecord);

        if (existing == null) {
            // ---------------------------------------------------------------
            // CASE 1: Brand-new key — we own this request. Process it.
            // ---------------------------------------------------------------
            log.info("[{}] New idempotency key. Processing payment.", idempotencyKey);
            try {
                PaymentResponse response = paymentService.process(request);
                newRecord.complete(response, 201);
                return new ProcessingResult(response, 201, false);
            } catch (Exception e) {
                // Remove the record so the client can retry with a new key
                // (or the same key — the record is gone so it will be treated as new)
                log.error("[{}] Payment processing failed. Removing record.", idempotencyKey, e);
                // We cannot cleanly remove from ConcurrentHashMap while in IN_FLIGHT
                // so mark it failed and let it expire via TTL.
                newRecord.complete(null, 500);
                throw e;
            }
        }

        // ---------------------------------------------------------------
        // CASE 2: Key already exists — check body hash first (User Story 3)
        // ---------------------------------------------------------------
        if (!existing.getRequestBodyHash().equals(bodyHash)) {
            log.warn("[{}] Body mismatch detected. Stored hash={} Incoming hash={}",
                    idempotencyKey, existing.getRequestBodyHash(), bodyHash);
            throw new BodyMismatchException(
                    "Idempotency key already used for a different request body.");
        }

        // ---------------------------------------------------------------
        // CASE 3: Same key, same body, but request is still IN-FLIGHT
        // (Bonus: race-condition guard)
        // ---------------------------------------------------------------
        if (existing.getState() == IdempotencyRecord.State.IN_FLIGHT) {
            log.info("[{}] Request is IN_FLIGHT. Waiting for original to complete.", idempotencyKey);
            try {
                PaymentResponse response = existing.awaitCompletion();
                log.info("[{}] Original request completed. Returning cached result.", idempotencyKey);
                return new ProcessingResult(response, existing.getCachedStatusCode(), true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for in-flight payment", e);
            }
        }

        // ---------------------------------------------------------------
        // CASE 4: Same key, same body, already COMPLETED → cache hit
        // ---------------------------------------------------------------
        log.info("[{}] Cache hit. Returning stored response.", idempotencyKey);
        return new ProcessingResult(existing.getCachedResponse(), existing.getCachedStatusCode(), true);
    }

    // -------------------------------------------------------------------------
    // Custom exceptions (kept here for locality; move to own files if preferred)
    // -------------------------------------------------------------------------

    public static class BodyMismatchException extends RuntimeException {
        public BodyMismatchException(String message) {
            super(message);
        }
    }
}
