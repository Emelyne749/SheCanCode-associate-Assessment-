package com.igirepay.gateway.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "idempotency_records", indexes = {
        @Index(name = "idx_idempotency_key", columnList = "idempotency_key", unique = true)
})
public class IdempotencyRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "request_body_hash", nullable = false, length = 64)
    private String requestBodyHash;

    @Column(name = "request_amount", nullable = false)
    private String requestAmount;

    @Column(name = "request_currency", nullable = false, length = 10)
    private String requestCurrency;

    @Column(name = "state", nullable = false, length = 20)
    private String state;

    @Column(name = "response_status", length = 20)
    private String responseStatus;

    @Column(name = "response_message", length = 500)
    private String responseMessage;

    @Column(name = "response_transaction_id", length = 100)
    private String responseTransactionId;

    @Column(name = "response_status_code")
    private Integer responseStatusCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public IdempotencyRecordEntity() {}

    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public String getRequestBodyHash() { return requestBodyHash; }
    public void setRequestBodyHash(String v) { this.requestBodyHash = v; }
    public String getRequestAmount() { return requestAmount; }
    public void setRequestAmount(String v) { this.requestAmount = v; }
    public String getRequestCurrency() { return requestCurrency; }
    public void setRequestCurrency(String v) { this.requestCurrency = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getResponseStatus() { return responseStatus; }
    public void setResponseStatus(String v) { this.responseStatus = v; }
    public String getResponseMessage() { return responseMessage; }
    public void setResponseMessage(String v) { this.responseMessage = v; }
    public String getResponseTransactionId() { return responseTransactionId; }
    public void setResponseTransactionId(String v) { this.responseTransactionId = v; }
    public Integer getResponseStatusCode() { return responseStatusCode; }
    public void setResponseStatusCode(Integer v) { this.responseStatusCode = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
}