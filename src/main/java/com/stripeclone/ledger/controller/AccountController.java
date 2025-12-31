package com.stripeclone.ledger.controller;

import com.stripeclone.ledger.model.Account;
import com.stripeclone.ledger.service.AccountService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {
    
    private final AccountService accountService;
    
    @PostMapping
    public ResponseEntity<Account> createAccount(@RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(
            Account.AccountType.valueOf(request.getAccountType()),
            request.getCurrency(),
            request.getInitialBalance()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }
    
    @GetMapping("/{accountId}")
    public ResponseEntity<Account> getAccount(@PathVariable String accountId) {
        Account account = accountService.getAccount(accountId);
        return ResponseEntity.ok(account);
    }
    
    @Data
    static class CreateAccountRequest {
        private String accountType;
        private String currency;
        private BigDecimal initialBalance;
    }
}

