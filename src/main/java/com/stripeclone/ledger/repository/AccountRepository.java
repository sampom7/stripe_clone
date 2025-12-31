package com.stripeclone.ledger.repository;

import com.stripeclone.ledger.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    
    Optional<Account> findByExternalId(String externalId);
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.externalId = :externalId")
    Optional<Account> findByExternalIdWithLock(@Param("externalId") String externalId);
    
    boolean existsByExternalId(String externalId);
}

