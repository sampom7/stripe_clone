package com.stripeclone.payment.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "authorizations", indexes = {
    @Index(name = "idx_auth_external_id", columnList = "external_id"),
    @Index(name = "idx_auth_payment", columnList = "payment_id"),
    @Index(name = "idx_auth_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Authorization {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "external_id", unique = true, nullable = false, length = 50)
    private String externalId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;
    
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
    
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AuthorizationStatus status;
    
    @Column(name = "authorized_at")
    private Instant authorizedAt;
    
    @Column(name = "expires_at")
    private Instant expiresAt;
    
    @Column(name = "failure_reason", length = 500)
    private String failureReason;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    @Version
    private Long version;
    
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) {
            status = AuthorizationStatus.PENDING;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
    
    public enum AuthorizationStatus {
        PENDING,
        APPROVED,
        DECLINED,
        EXPIRED,
        REVERSED
    }
}

