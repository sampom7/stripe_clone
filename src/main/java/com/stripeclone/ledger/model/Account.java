package com.stripeclone.ledger.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "accounts", indexes = {
    @Index(name = "idx_account_external_id", columnList = "external_id"),
    @Index(name = "idx_account_type", columnList = "account_type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "external_id", unique = true, nullable = false, length = 50)
    private String externalId;
    
    @Column(name = "account_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private AccountType accountType;
    
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
    
    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;
    
    @Column(name = "available_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableBalance;
    
    @Column(name = "hold_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal holdBalance;
    
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private AccountStatus status;
    
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
        if (balance == null) balance = BigDecimal.ZERO;
        if (availableBalance == null) availableBalance = BigDecimal.ZERO;
        if (holdBalance == null) holdBalance = BigDecimal.ZERO;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
    
    public enum AccountType {
        CUSTOMER_WALLET,
        MERCHANT_ACCOUNT,
        PLATFORM_RESERVE,
        SETTLEMENT_ACCOUNT,
        FEE_ACCOUNT,
        REFUND_ACCOUNT
    }
    
    public enum AccountStatus {
        ACTIVE,
        SUSPENDED,
        CLOSED
    }
}

