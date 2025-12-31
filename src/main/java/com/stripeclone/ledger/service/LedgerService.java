package com.stripeclone.ledger.service;

import com.stripeclone.ledger.model.Account;
import com.stripeclone.ledger.model.LedgerEntry;
import com.stripeclone.ledger.model.LedgerTransaction;
import com.stripeclone.ledger.repository.AccountRepository;
import com.stripeclone.ledger.repository.LedgerTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerService {
    
    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    
    /**
     * Creates a double-entry ledger transaction ensuring debits equal credits
     * 
     * @param transactionType Type of transaction
     * @param entries List of ledger entries (must balance)
     * @param description Transaction description
     * @param metadata Optional metadata
     * @return Created ledger transaction
     * @throws IllegalArgumentException if entries don't balance
     */
    @Transactional
    public LedgerTransaction createTransaction(
            LedgerTransaction.TransactionType transactionType,
            List<EntryRequest> entries,
            String description,
            String metadata) {
        
        // Validate that debits equal credits
        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        
        for (EntryRequest entry : entries) {
            if (entry.entryType() == LedgerEntry.EntryType.DEBIT) {
                totalDebits = totalDebits.add(entry.amount());
            } else {
                totalCredits = totalCredits.add(entry.amount());
            }
        }
        
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new IllegalArgumentException(
                String.format("Transaction entries do not balance. Debits: %s, Credits: %s", 
                    totalDebits, totalCredits));
        }
        
        // Create ledger transaction
        LedgerTransaction transaction = LedgerTransaction.builder()
            .externalId(com.stripeclone.common.util.IdGenerator.generateLedgerTransactionId())
            .transactionType(transactionType)
            .amount(totalDebits) // Use debit amount as transaction amount
            .currency(entries.get(0).currency())
            .status(LedgerTransaction.TransactionStatus.PROCESSING)
            .description(description)
            .metadata(metadata)
            .build();
        
        // Create ledger entries and update account balances
        List<LedgerEntry> ledgerEntries = new ArrayList<>();
        
        for (EntryRequest entryRequest : entries) {
            Account account = accountRepository.findByExternalIdWithLock(entryRequest.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + entryRequest.accountId()));
            
            // Update account balance
            if (entryRequest.entryType() == LedgerEntry.EntryType.DEBIT) {
                if (account.getAvailableBalance().compareTo(entryRequest.amount()) < 0) {
                    throw new IllegalArgumentException(
                        String.format("Insufficient balance in account %s. Available: %s, Required: %s",
                            account.getExternalId(), account.getAvailableBalance(), entryRequest.amount()));
                }
                account.setBalance(account.getBalance().subtract(entryRequest.amount()));
                account.setAvailableBalance(account.getAvailableBalance().subtract(entryRequest.amount()));
            } else {
                account.setBalance(account.getBalance().add(entryRequest.amount()));
                account.setAvailableBalance(account.getAvailableBalance().add(entryRequest.amount()));
            }
            
            // Create ledger entry
            LedgerEntry ledgerEntry = LedgerEntry.builder()
                .transaction(transaction)
                .account(account)
                .entryType(entryRequest.entryType())
                .amount(entryRequest.amount())
                .currency(entryRequest.currency())
                .balanceAfter(account.getBalance())
                .build();
            
            ledgerEntries.add(ledgerEntry);
        }
        
        transaction.setEntries(ledgerEntries);
        transaction.setStatus(LedgerTransaction.TransactionStatus.COMPLETED);
        
        LedgerTransaction saved = ledgerTransactionRepository.save(transaction);
        log.info("Created ledger transaction: {} with {} entries", saved.getExternalId(), ledgerEntries.size());
        
        return saved;
    }
    
    /**
     * Holds funds in an account (moves from available to hold balance)
     */
    @Transactional
    public void holdFunds(String accountId, BigDecimal amount) {
        Account account = accountRepository.findByExternalIdWithLock(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
        
        if (account.getAvailableBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient available balance");
        }
        
        account.setAvailableBalance(account.getAvailableBalance().subtract(amount));
        account.setHoldBalance(account.getHoldBalance().add(amount));
        
        accountRepository.save(account);
        log.debug("Held {} in account {}", amount, accountId);
    }
    
    /**
     * Releases held funds (moves from hold to available balance)
     */
    @Transactional
    public void releaseHold(String accountId, BigDecimal amount) {
        Account account = accountRepository.findByExternalIdWithLock(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
        
        if (account.getHoldBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient hold balance");
        }
        
        account.setHoldBalance(account.getHoldBalance().subtract(amount));
        account.setAvailableBalance(account.getAvailableBalance().add(amount));
        
        accountRepository.save(account);
        log.debug("Released hold of {} in account {}", amount, accountId);
    }
    
    /**
     * Moves funds from hold balance to actual debit (completes a hold)
     */
    @Transactional
    public void completeHold(String accountId, BigDecimal amount) {
        Account account = accountRepository.findByExternalIdWithLock(accountId)
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
        
        if (account.getHoldBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Insufficient hold balance");
        }
        
        account.setHoldBalance(account.getHoldBalance().subtract(amount));
        account.setBalance(account.getBalance().subtract(amount));
        
        accountRepository.save(account);
        log.debug("Completed hold of {} in account {}", amount, accountId);
    }
    
    public record EntryRequest(
        String accountId,
        LedgerEntry.EntryType entryType,
        BigDecimal amount,
        String currency
    ) {}
}

