package com.stripeclone.payment.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "settlements", indexes = {
    @Index(name = "idx_settlement_external_id", columnList = "external_id"),
    @Index(name = "idx_settlement_capture", columnList = "capture_id"),
    @Index(name = "idx_settlement_status", columnList = "status"),
    @Index(name = "idx_settlement_date", columnList = "settlement_date")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Settlement {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "external_id", unique = true, nullable = false, length = 50)
    private String externalId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "capture_id", nullable = false)
    private Capture capture;
    
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
    
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SettlementStatus status;
    
    @Column(name = "settlement_date")
    private Instant settlementDate;
    
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
            status = SettlementStatus.PENDING;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
    
    public enum SettlementStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}

