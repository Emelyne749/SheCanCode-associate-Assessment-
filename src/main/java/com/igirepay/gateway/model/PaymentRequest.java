package com.igirepay.gateway.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Represents an incoming payment request from a client system.
 */
public class PaymentRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than 0")
    private final BigDecimal amount;

    @NotBlank(message = "Currency is required")
    private final String currency;

    @JsonCreator
    public PaymentRequest(
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    /**
     * Used for idempotency body comparison. Two requests with the same
     * amount + currency are considered identical.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentRequest that)) return false;
        return Objects.equals(amount, that.amount) &&
               Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    @Override
    public String toString() {
        return "PaymentRequest{amount=" + amount + ", currency='" + currency + "'}";
    }
}
