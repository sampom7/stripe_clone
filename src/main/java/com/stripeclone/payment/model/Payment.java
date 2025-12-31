package com.stripeclone.payment.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "payments", indexes = {
    @Index(name = "idx_payment_external_id", columnList = "external_id"),
    @Index(name = "idx_payment_customer", columnList = "customer_id"),
    @Index(name = "idx_payment_status", columnList = "status"),
    @Index(name = "idx_payment_idempotency", columnList = "idempotency_key")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "external_id", unique = true, nullable = false, length = 50)
    private String externalId;
    
    @Column(name = "idempotency_key", unique = true, nullable = false, length = 100)
    private String idempotencyKey;
    
    @Column(name = "customer_id", nullable = false, length = 50)
    private String customerId;
    
    @Column(name = "merchant_id", nullable = false, length = 50)
    private String merchantId;
    
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
    
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;
    
    @Column(name = "description", length = 500)
    private String description;
    
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;
    
    @Column(name = "failure_reason", length = 500)
    private String failureReason;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Authorization> authorizations = new ArrayList<>();
    
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Capture> captures = new ArrayList<>();
    
    @Version
    private Long version;
    
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) {
            status = PaymentStatus.PENDING;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
    
    public enum PaymentStatus {
        PENDING,
        AUTHORIZED,
        CAPTURED,
        FAILED,
        REFUNDED,
        CANCELLED
    }
}

