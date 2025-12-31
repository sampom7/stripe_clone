package com.stripeclone.payment.repository;

import com.stripeclone.payment.model.Capture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CaptureRepository extends JpaRepository<Capture, Long> {
    
    Optional<Capture> findByExternalId(String externalId);
    
    boolean existsByExternalId(String externalId);
}

