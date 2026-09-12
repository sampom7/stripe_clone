package com.stripeclone.payment;

import java.time.Instant;

/**
 * A customer, paired with the ledger account that holds their balance.
 *
 * <p>Splitting the two means the customer record can carry email, name and the rest without
 * the ledger having to know anything about people.
 */
public record Customer(
        String customerId,
        String email,
        String name,
        String accountId,
        Instant createdAt
) {}
