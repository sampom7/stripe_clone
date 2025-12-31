package com.stripeclone.payment.repository;

import com.stripeclone.payment.model.Authorization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthorizationRepository extends JpaRepository<Authorization, Long> {
    
    Optional<Authorization> findByExternalId(String externalId);
    
    boolean existsByExternalId(String externalId);
}

