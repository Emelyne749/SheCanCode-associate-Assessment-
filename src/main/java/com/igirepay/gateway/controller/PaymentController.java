package com.igirepay.gateway.controller;

import com.igirepay.gateway.model.ErrorResponse;
import com.igirepay.gateway.model.PaymentRequest;
import com.igirepay.gateway.model.PaymentResponse;
import com.igirepay.gateway.service.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for the payment processing endpoint.
 *
 * POST /process-payment
 *   Required headers: Idempotency-Key: <unique-string>
 *   Body: {"amount": 100, "currency": "RWF"}
 */
@RestController
@RequestMapping("/process-payment")
public class PaymentController {

    private final IdempotencyService idempotencyService;

    public PaymentController(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<?> processPayment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest paymentRequest) {

        // Guard: Idempotency-Key header must be present
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            ErrorResponse error = new ErrorResponse(
                    400, "Bad Request",
                    "Missing required header: Idempotency-Key");
            return ResponseEntity.badRequest().body(error);
        }

        IdempotencyService.ProcessingResult result =
                idempotencyService.handlePayment(idempotencyKey, paymentRequest);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.statusCode());

        // Attach X-Cache-Hit header for duplicate responses
        if (result.cacheHit()) {
            builder.header("X-Cache-Hit", "true");
        }

        return builder.body(result.response());
    }
}
