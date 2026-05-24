package com.igirepay.gateway.service;

import com.igirepay.gateway.entity.IdempotencyRecordEntity;
import com.igirepay.gateway.model.IdempotencyRecord;
import com.igirepay.gateway.model.PaymentRequest;
import com.igirepay.gateway.model.PaymentResponse;
import com.igirepay.gateway.repository.IdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    private final ConcurrentHashMap<String, IdempotencyRecord> inFlightMap = new ConcurrentHashMap<>();
    private final IdempotencyRepository repository;

    @Value("${idempotency.ttl-hours:24}")
    private long ttlHours;

    public IdempotencyStore(IdempotencyRepository repository) {
        this.repository = repository;
    }

    public IdempotencyRecord putIfAbsent(String key, IdempotencyRecord newRecord) {
        IdempotencyRecord existing = inFlightMap.putIfAbsent(key, newRecord);
        if (existing != null) return existing;

        Optional<IdempotencyRecordEntity> dbRecord = repository.findByIdempotencyKey(key);
        if (dbRecord.isPresent()) {
            inFlightMap.remove(key);
            return toInMemoryRecord(dbRecord.get());
        }

        IdempotencyRecordEntity entity = toEntity(newRecord);
        entity.setState("IN_FLIGHT");
        repository.save(entity);
        return null;
    }

    public void complete(String key, PaymentResponse response, int statusCode) {
        IdempotencyRecord inMemory = inFlightMap.get(key);
        if (inMemory != null) inMemory.complete(response, statusCode);

        repository.findByIdempotencyKey(key).ifPresent(entity -> {
            entity.setState("COMPLETED");
            entity.setResponseStatus(response != null ? response.getStatus() : "FAILED");
            entity.setResponseMessage(response != null ? response.getMessage() : null);
            entity.setResponseTransactionId(response != null ? response.getTransactionId() : null);
            entity.setResponseStatusCode(statusCode);
            entity.setCompletedAt(Instant.now());
            repository.save(entity);
        });
    }

    public IdempotencyRecord get(String key) {
        IdempotencyRecord inMemory = inFlightMap.get(key);
        if (inMemory != null) return inMemory;
        return repository.findByIdempotencyKey(key).map(this::toInMemoryRecord).orElse(null);
    }

    @Scheduled(fixedRateString = "${idempotency.cleanup-interval-ms:3600000}")
    public void evictExpiredRecords() {
        Instant cutoff = Instant.now().minus(ttlHours, ChronoUnit.HOURS);
        int removed = repository.deleteByCreatedAtBefore(cutoff);
        inFlightMap.entrySet().removeIf(e -> e.getValue().getCreatedAt().isBefore(cutoff));
        if (removed > 0) log.info("TTL eviction: removed {} expired record(s) from MySQL.", removed);
    }

    private IdempotencyRecordEntity toEntity(IdempotencyRecord record) {
        IdempotencyRecordEntity entity = new IdempotencyRecordEntity();
        entity.setIdempotencyKey(record.getIdempotencyKey());
        entity.setRequestBodyHash(record.getRequestBodyHash());
        entity.setRequestAmount(record.getOriginalRequest().getAmount().toPlainString());
        entity.setRequestCurrency(record.getOriginalRequest().getCurrency());
        entity.setCreatedAt(record.getCreatedAt());
        return entity;
    }

    private IdempotencyRecord toInMemoryRecord(IdempotencyRecordEntity entity) {
        PaymentRequest req = new PaymentRequest(
                new BigDecimal(entity.getRequestAmount()),
                entity.getRequestCurrency()
        );
        IdempotencyRecord record = new IdempotencyRecord(
                entity.getIdempotencyKey(),
                entity.getRequestBodyHash(),
                req
        );
        if ("COMPLETED".equals(entity.getState())) {
            PaymentResponse response = null;
            if (entity.getResponseStatus() != null) {
                response = new PaymentResponse(
                        entity.getResponseStatus(),
                        entity.getResponseMessage(),
                        entity.getResponseTransactionId(),
                        entity.getCompletedAt()
                );
            }
            record.complete(response, entity.getResponseStatusCode() != null
                    ? entity.getResponseStatusCode() : 201);
        }
        return record;
    }
}