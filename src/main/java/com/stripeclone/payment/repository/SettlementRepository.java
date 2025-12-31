package com.stripeclone.payment.repository;

import com.stripeclone.payment.model.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    
    Optional<Settlement> findByExternalId(String externalId);
    
    boolean existsByExternalId(String externalId);
}

