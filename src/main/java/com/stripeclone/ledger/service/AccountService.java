package com.stripeclone.ledger.service;

import com.stripeclone.common.util.IdGenerator;
import com.stripeclone.ledger.model.Account;
import com.stripeclone.ledger.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {
    
    private final AccountRepository accountRepository;
    
    @Transactional
    public Account createAccount(
            Account.AccountType accountType,
            String currency,
            BigDecimal initialBalance) {
        
        Account account = Account.builder()
            .externalId(IdGenerator.generateAccountId())
            .accountType(accountType)
            .currency(currency)
            .balance(initialBalance != null ? initialBalance : BigDecimal.ZERO)
            .availableBalance(initialBalance != null ? initialBalance : BigDecimal.ZERO)
            .holdBalance(BigDecimal.ZERO)
            .status(Account.AccountStatus.ACTIVE)
            .build();
        
        account = accountRepository.save(account);
        log.info("Created account: {} of type: {} with balance: {}", 
            account.getExternalId(), accountType, account.getBalance());
        
        return account;
    }
    
    public Account getAccount(String externalId) {
        return accountRepository.findByExternalId(externalId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + externalId));
    }
}

