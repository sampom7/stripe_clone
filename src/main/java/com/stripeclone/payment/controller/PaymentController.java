package com.stripeclone.payment.controller;

import com.stripeclone.common.service.IdempotencyService;
import com.stripeclone.payment.model.Payment;
import com.stripeclone.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {
    
    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;
    
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        
        log.info("Received payment request with idempotency key: {}", idempotencyKey);
        
        // Check idempotency cache
        PaymentResponse cachedResponse = idempotencyService.getCachedResponse(
            idempotencyKey, 
            PaymentResponse.class
        ).orElse(null);
        
        if (cachedResponse != null) {
            log.info("Returning cached response for idempotency key: {}", idempotencyKey);
            return ResponseEntity.ok(cachedResponse);
        }
        
        // Check if already processing
        if (idempotencyService.isProcessing(idempotencyKey)) {
            log.warn("Payment already processing for idempotency key: {}", idempotencyKey);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new PaymentResponse(null, "Payment already being processed", null));
        }
        
        try {
            idempotencyService.markAsProcessing(idempotencyKey);
            
            Payment payment = paymentService.initiatePayment(
                new PaymentService.PaymentRequest(
                    idempotencyKey,
                    request.getCustomerId(),
                    request.getMerchantId(),
                    request.getAmount(),
                    request.getCurrency(),
                    request.getPaymentMethod(),
                    request.getDescription(),
                    request.getMetadata() != null ? request.getMetadata().toString() : null
                )
            );
            
            PaymentResponse response = new PaymentResponse(
                payment.getExternalId(),
                "Payment initiated successfully",
                payment.getStatus().name()
            );
            
            // Cache response
            idempotencyService.cacheResponse(idempotencyKey, response);
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Error processing payment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new PaymentResponse(null, "Payment processing failed: " + e.getMessage(), null));
        } finally {
            idempotencyService.clearProcessing(idempotencyKey);
        }
    }
    
    @PostMapping("/{paymentId}/authorize")
    public ResponseEntity<AuthorizationResponse> authorizePayment(@PathVariable String paymentId) {
        try {
            var authorization = paymentService.authorizePayment(paymentId);
            return ResponseEntity.ok(new AuthorizationResponse(
                authorization.getExternalId(),
                "Payment authorized successfully",
                authorization.getStatus().name()
            ));
        } catch (Exception e) {
            log.error("Error authorizing payment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new AuthorizationResponse(null, "Authorization failed: " + e.getMessage(), null));
        }
    }
    
    @PostMapping("/{paymentId}/capture")
    public ResponseEntity<CaptureResponse> capturePayment(
            @PathVariable String paymentId,
            @RequestBody(required = false) CaptureRequest request) {
        try {
            BigDecimal amount = request != null && request.getAmount() != null 
                ? request.getAmount() 
                : null; // Will use full authorized amount if not specified
            
            var capture = paymentService.capturePayment(paymentId, amount);
            return ResponseEntity.ok(new CaptureResponse(
                capture.getExternalId(),
                "Payment captured successfully",
                capture.getStatus().name()
            ));
        } catch (Exception e) {
            log.error("Error capturing payment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CaptureResponse(null, "Capture failed: " + e.getMessage(), null));
        }
    }
    
    @PostMapping("/{paymentId}/settle")
    public ResponseEntity<SettlementResponse> settlePayment(@PathVariable String paymentId) {
        try {
            var settlement = paymentService.settlePayment(paymentId);
            return ResponseEntity.ok(new SettlementResponse(
                settlement.getExternalId(),
                "Payment settled successfully",
                settlement.getStatus().name()
            ));
        } catch (Exception e) {
            log.error("Error settling payment", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new SettlementResponse(null, "Settlement failed: " + e.getMessage(), null));
        }
    }
    
    @GetMapping("/{paymentId}")
    public ResponseEntity<Payment> getPayment(@PathVariable String paymentId) {
        try {
            Payment payment = paymentService.getPayment(paymentId);
            return ResponseEntity.ok(payment);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
    
    // DTOs
    @Data
    static class CreatePaymentRequest {
        @NotBlank
        private String customerId;
        
        @NotBlank
        private String merchantId;
        
        @NotNull
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        private BigDecimal amount;
        
        @NotBlank
        @Size(min = 3, max = 3)
        private String currency;
        
        private String paymentMethod;
        
        private String description;
        
        private Map<String, Object> metadata;
    }
    
    @Data
    static class CaptureRequest {
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        private BigDecimal amount;
    }
    
    record PaymentResponse(String paymentId, String message, String status) {}
    record AuthorizationResponse(String authorizationId, String message, String status) {}
    record CaptureResponse(String captureId, String message, String status) {}
    record SettlementResponse(String settlementId, String message, String status) {}
}

