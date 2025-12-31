package com.stripeclone.payment.service;

import com.stripeclone.common.service.IdempotencyService;
import com.stripeclone.common.util.IdGenerator;
import com.stripeclone.ledger.model.Account;
import com.stripeclone.ledger.model.LedgerEntry;
import com.stripeclone.ledger.model.LedgerTransaction;
import com.stripeclone.ledger.repository.AccountRepository;
import com.stripeclone.ledger.service.LedgerService;
import com.stripeclone.payment.event.*;
import com.stripeclone.payment.model.*;
import com.stripeclone.payment.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    
    private final PaymentRepository paymentRepository;
    private final AuthorizationRepository authorizationRepository;
    private final CaptureRepository captureRepository;
    private final SettlementRepository settlementRepository;
    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
    
    private static final String PAYMENT_EVENTS_TOPIC = "payment-events";
    
    /**
     * Initiates a payment request
     */
    @Transactional
    public Payment initiatePayment(PaymentRequest request) {
        // Check idempotency
        if (paymentRepository.existsByIdempotencyKey(request.idempotencyKey())) {
            return paymentRepository.findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Payment with idempotency key exists but not found"));
        }
        
        // Create payment
        Payment payment = Payment.builder()
            .externalId(IdGenerator.generatePaymentId())
            .idempotencyKey(request.idempotencyKey())
            .customerId(request.customerId())
            .merchantId(request.merchantId())
            .amount(request.amount())
            .currency(request.currency())
            .status(Payment.PaymentStatus.PENDING)
            .paymentMethod(request.paymentMethod())
            .description(request.description())
            .metadata(request.metadata())
            .build();
        
        payment = paymentRepository.save(payment);
        
        // Publish event
        publishEvent(new PaymentInitiatedEvent(
            UUID.randomUUID().toString(),
            payment.getExternalId(),
            Instant.now(),
            payment.getIdempotencyKey(),
            payment.getCustomerId(),
            payment.getMerchantId(),
            payment.getAmount(),
            payment.getCurrency()
        ));
        
        log.info("Initiated payment: {}", payment.getExternalId());
        return payment;
    }
    
    /**
     * Authorizes a payment (holds funds)
     */
    @Transactional
    public Authorization authorizePayment(String paymentId) {
        Payment payment = paymentRepository.findByExternalId(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        
        if (payment.getStatus() != Payment.PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment is not in PENDING status");
        }
        
        // Get customer account
        Account customerAccount = accountRepository.findByExternalId(payment.getCustomerId())
            .orElseThrow(() -> new IllegalArgumentException("Customer account not found"));
        
        // Hold funds
        ledgerService.holdFunds(customerAccount.getExternalId(), payment.getAmount());
        
        // Create authorization
        Authorization authorization = Authorization.builder()
            .externalId(IdGenerator.generateAuthorizationId())
            .payment(payment)
            .amount(payment.getAmount())
            .currency(payment.getCurrency())
            .status(Authorization.AuthorizationStatus.APPROVED)
            .authorizedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(7 * 24 * 60 * 60)) // 7 days
            .build();
        
        authorization = authorizationRepository.save(authorization);
        payment.setStatus(Payment.PaymentStatus.AUTHORIZED);
        paymentRepository.save(payment);
        
        // Publish event
        publishEvent(new PaymentAuthorizedEvent(
            UUID.randomUUID().toString(),
            payment.getExternalId(),
            Instant.now(),
            payment.getIdempotencyKey(),
            authorization.getExternalId(),
            authorization.getAmount(),
            authorization.getCurrency()
        ));
        
        log.info("Authorized payment: {} with authorization: {}", paymentId, authorization.getExternalId());
        return authorization;
    }
    
    /**
     * Captures an authorized payment
     */
    @Transactional
    public Capture capturePayment(String paymentId, BigDecimal amount) {
        Payment payment = paymentRepository.findByExternalId(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        
        if (payment.getStatus() != Payment.PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException("Payment is not in AUTHORIZED status");
        }
        
        Authorization authorization = payment.getAuthorizations().stream()
            .filter(a -> a.getStatus() == Authorization.AuthorizationStatus.APPROVED)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No approved authorization found"));
        
        // If amount is null, use full authorized amount
        if (amount == null) {
            amount = authorization.getAmount();
        }
        
        if (amount.compareTo(authorization.getAmount()) > 0) {
            throw new IllegalArgumentException("Capture amount exceeds authorized amount");
        }
        
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Capture amount must be greater than zero");
        }
        
        // Get accounts
        Account customerAccount = accountRepository.findByExternalId(payment.getCustomerId())
            .orElseThrow(() -> new IllegalArgumentException("Customer account not found"));
        Account merchantAccount = accountRepository.findByExternalId(payment.getMerchantId())
            .orElseThrow(() -> new IllegalArgumentException("Merchant account not found"));
        
        // Complete hold and transfer funds
        ledgerService.completeHold(customerAccount.getExternalId(), amount);
        
        // Create double-entry transaction: debit customer, credit merchant
        ledgerService.createTransaction(
            LedgerTransaction.TransactionType.CAPTURE,
            List.of(
                new LedgerService.EntryRequest(
                    customerAccount.getExternalId(),
                    LedgerEntry.EntryType.DEBIT,
                    amount,
                    payment.getCurrency()
                ),
                new LedgerService.EntryRequest(
                    merchantAccount.getExternalId(),
                    LedgerEntry.EntryType.CREDIT,
                    amount,
                    payment.getCurrency()
                )
            ),
            "Payment capture: " + payment.getExternalId(),
            null
        );
        
        // Create capture
        Capture capture = Capture.builder()
            .externalId(IdGenerator.generateCaptureId())
            .payment(payment)
            .authorization(authorization)
            .amount(amount)
            .currency(payment.getCurrency())
            .status(Capture.CaptureStatus.COMPLETED)
            .capturedAt(Instant.now())
            .build();
        
        capture = captureRepository.save(capture);
        payment.setStatus(Payment.PaymentStatus.CAPTURED);
        paymentRepository.save(payment);
        
        // Publish event
        publishEvent(new PaymentCapturedEvent(
            UUID.randomUUID().toString(),
            payment.getExternalId(),
            Instant.now(),
            payment.getIdempotencyKey(),
            capture.getExternalId(),
            authorization.getExternalId(),
            capture.getAmount(),
            capture.getCurrency()
        ));
        
        log.info("Captured payment: {} with capture: {}", paymentId, capture.getExternalId());
        return capture;
    }
    
    /**
     * Settles a captured payment (moves funds to settlement account)
     */
    @Transactional
    public Settlement settlePayment(String paymentId) {
        Payment payment = paymentRepository.findByExternalId(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        
        if (payment.getStatus() != Payment.PaymentStatus.CAPTURED) {
            throw new IllegalStateException("Payment is not in CAPTURED status");
        }
        
        Capture capture = payment.getCaptures().stream()
            .filter(c -> c.getStatus() == Capture.CaptureStatus.COMPLETED)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No completed capture found"));
        
        // Get accounts
        Account merchantAccount = accountRepository.findByExternalId(payment.getMerchantId())
            .orElseThrow(() -> new IllegalArgumentException("Merchant account not found"));
        Account settlementAccount = accountRepository.findByExternalId("settlement_account_" + payment.getCurrency())
            .orElseThrow(() -> new IllegalArgumentException("Settlement account not found"));
        
        // Create settlement transaction
        ledgerService.createTransaction(
            LedgerTransaction.TransactionType.SETTLEMENT,
            List.of(
                new LedgerService.EntryRequest(
                    merchantAccount.getExternalId(),
                    LedgerEntry.EntryType.DEBIT,
                    capture.getAmount(),
                    payment.getCurrency()
                ),
                new LedgerService.EntryRequest(
                    settlementAccount.getExternalId(),
                    LedgerEntry.EntryType.CREDIT,
                    capture.getAmount(),
                    payment.getCurrency()
                )
            ),
            "Payment settlement: " + payment.getExternalId(),
            null
        );
        
        // Create settlement
        Settlement settlement = Settlement.builder()
            .externalId(IdGenerator.generateSettlementId())
            .capture(capture)
            .amount(capture.getAmount())
            .currency(payment.getCurrency())
            .status(Settlement.SettlementStatus.COMPLETED)
            .settlementDate(Instant.now())
            .build();
        
        settlement = settlementRepository.save(settlement);
        
        // Publish event
        publishEvent(new PaymentSettledEvent(
            UUID.randomUUID().toString(),
            payment.getExternalId(),
            Instant.now(),
            payment.getIdempotencyKey(),
            settlement.getExternalId(),
            capture.getExternalId(),
            settlement.getAmount(),
            settlement.getCurrency()
        ));
        
        log.info("Settled payment: {} with settlement: {}", paymentId, settlement.getExternalId());
        return settlement;
    }
    
    private void publishEvent(PaymentEvent event) {
        try {
            kafkaTemplate.send(PAYMENT_EVENTS_TOPIC, event.getPaymentId(), event);
            log.debug("Published payment event: {} for payment: {}", event.getClass().getSimpleName(), event.getPaymentId());
        } catch (Exception e) {
            log.error("Failed to publish payment event", e);
            throw new RuntimeException("Failed to publish event", e);
        }
    }
    
    public Payment getPayment(String paymentId) {
        return paymentRepository.findByExternalId(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
    }
    
    public record PaymentRequest(
        String idempotencyKey,
        String customerId,
        String merchantId,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        String description,
        String metadata
    ) {}
}

