package com.igirepay.gateway.service;

import com.igirepay.gateway.model.PaymentRequest;
import com.igirepay.gateway.model.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Simulates the core payment processing logic.
 * In a real system this would call an external payment processor
 * (e.g. Flutterwave, MTN MoMo API, etc.).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final long PROCESSING_DELAY_MS = 2_000;

    /**
     * Processes a payment. Simulates a 2-second network/processing delay.
     *
     * @param request the validated payment request
     * @return the payment result
     */
    public PaymentResponse process(PaymentRequest request) {
        log.info("Processing payment: {} {}", request.getAmount(), request.getCurrency());

        try {
            // Simulate processing time (e.g., calling external payment gateway)
            Thread.sleep(PROCESSING_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Payment processing interrupted", e);
        }

        String transactionId = "TXN-" + UUID.randomUUID().toString().toUpperCase().replace("-", "").substring(0, 12);
        String message = "Charged " + request.getAmount().stripTrailingZeros().toPlainString()
                         + " " + request.getCurrency().toUpperCase();

        log.info("Payment processed successfully. TransactionId={}", transactionId);

        return new PaymentResponse("SUCCESS", message, transactionId, Instant.now());
    }
}
