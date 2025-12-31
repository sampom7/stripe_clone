package com.stripeclone.ledger.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ledger_entries", indexes = {
    @Index(name = "idx_entry_transaction", columnList = "transaction_id"),
    @Index(name = "idx_entry_account", columnList = "account_id"),
    @Index(name = "idx_entry_type", columnList = "entry_type")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntry {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;
    
    @Column(name = "entry_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private EntryType entryType;
    
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;
    
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
    
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
    
    public enum EntryType {
        DEBIT,
        CREDIT
    }
}

