package com.stripeclone.payment.repository;

import com.stripeclone.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    
    Optional<Payment> findByExternalId(String externalId);
    
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
    
    boolean existsByExternalId(String externalId);
    
    boolean existsByIdempotencyKey(String idempotencyKey);
}

