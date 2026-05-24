package com.igirepay.gateway.service;

import com.igirepay.gateway.model.IdempotencyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for idempotency records.
 *
 * Developer's Choice Feature: TTL-based automatic expiry.
 * Records are evicted after a configurable TTL (default 24 hours).
 * A scheduled job runs every hour to purge stale entries, preventing
 * unbounded memory growth — critical for a long-running fintech service.
 */
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private final ConcurrentHashMap<String, IdempotencyRecord> store = new ConcurrentHashMap<>();

    @Value("${idempotency.ttl-hours:24}")
    private long ttlHours;

    /**
     * Atomically inserts a new record only if the key is absent.
     *
     * @return the existing record if key was already present, otherwise null
     *         (meaning insertion succeeded — this is the first request).
     */
    public IdempotencyRecord putIfAbsent(String key, IdempotencyRecord record) {
        return store.putIfAbsent(key, record);
    }

    /**
     * Retrieves a record by key.
     */
    public IdempotencyRecord get(String key) {
        return store.get(key);
    }

    /**
     * Returns the total number of records currently held.
     */
    public int size() {
        return store.size();
    }

    /**
     * Scheduled TTL eviction job — runs every hour.
     * Removes records older than the configured TTL.
     * This is the Developer's Choice safety mechanism: without this,
     * the in-memory store would grow indefinitely in production.
     */
    @Scheduled(fixedRateString = "${idempotency.cleanup-interval-ms:3600000}")
    public void evictExpiredRecords() {
        Instant cutoff = Instant.now().minus(ttlHours, ChronoUnit.HOURS);
        int before = store.size();

        store.entrySet().removeIf(entry -> entry.getValue().getCreatedAt().isBefore(cutoff));

        int removed = before - store.size();
        if (removed > 0) {
            log.info("TTL eviction: removed {} expired idempotency record(s). Store size: {}", removed, store.size());
        }
    }
}
