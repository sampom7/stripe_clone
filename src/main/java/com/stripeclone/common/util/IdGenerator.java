package com.stripeclone.common.util;

import java.time.Instant;
import java.util.UUID;

public class IdGenerator {
    
    private static final String PREFIX_PAYMENT = "pay";
    private static final String PREFIX_AUTH = "auth";
    private static final String PREFIX_CAPTURE = "cap";
    private static final String PREFIX_SETTLEMENT = "set";
    private static final String PREFIX_ACCOUNT = "acc";
    private static final String PREFIX_LEDGER_TXN = "txn";
    
    public static String generatePaymentId() {
        return PREFIX_PAYMENT + "_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateAuthorizationId() {
        return PREFIX_AUTH + "_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateCaptureId() {
        return PREFIX_CAPTURE + "_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateSettlementId() {
        return PREFIX_SETTLEMENT + "_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateAccountId() {
        return PREFIX_ACCOUNT + "_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateLedgerTransactionId() {
        return PREFIX_LEDGER_TXN + "_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
}

