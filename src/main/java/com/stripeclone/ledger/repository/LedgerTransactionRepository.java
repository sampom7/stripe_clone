package com.stripeclone.ledger.repository;

import com.stripeclone.ledger.model.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {
    
    Optional<LedgerTransaction> findByExternalId(String externalId);
    
    boolean existsByExternalId(String externalId);
}

