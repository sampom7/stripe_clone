package com.stripeclone.ledger;

import com.stripeclone.money.Currency;

import java.time.Instant;

public record Account(
        String accountId,
        AccountType type,
        Currency currency,
        Instant createdAt
) {}
