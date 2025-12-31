package com.stripeclone.payment.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PaymentInitiatedEvent.class, name = "PAYMENT_INITIATED"),
    @JsonSubTypes.Type(value = PaymentAuthorizedEvent.class, name = "PAYMENT_AUTHORIZED"),
    @JsonSubTypes.Type(value = PaymentCapturedEvent.class, name = "PAYMENT_CAPTURED"),
    @JsonSubTypes.Type(value = PaymentSettledEvent.class, name = "PAYMENT_SETTLED"),
    @JsonSubTypes.Type(value = PaymentFailedEvent.class, name = "PAYMENT_FAILED")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public abstract class PaymentEvent {
    private String eventId;
    private String paymentId;
    private Instant timestamp;
    private String idempotencyKey;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitiatedEvent extends PaymentEvent {
    private String customerId;
    private String merchantId;
    private BigDecimal amount;
    private String currency;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentAuthorizedEvent extends PaymentEvent {
    private String authorizationId;
    private BigDecimal amount;
    private String currency;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCapturedEvent extends PaymentEvent {
    private String captureId;
    private String authorizationId;
    private BigDecimal amount;
    private String currency;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSettledEvent extends PaymentEvent {
    private String settlementId;
    private String captureId;
    private BigDecimal amount;
    private String currency;
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentFailedEvent extends PaymentEvent {
    private String failureReason;
    private String stage; // INITIATION, AUTHORIZATION, CAPTURE, SETTLEMENT
}

