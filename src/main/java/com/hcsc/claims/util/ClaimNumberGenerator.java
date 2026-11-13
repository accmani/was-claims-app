package com.hcsc.claims.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Claim Number Generator
 *
 * Generates unique claim numbers in format: CLM-YYYYMMDD-{sequence}
 * Example: CLM-20240115-000001
 *
 * WAS note: In WAS cluster, the sequence counter would be managed via
 * a database sequence (see Claim entity — CLAIM_SEQ).
 * This in-memory counter is for single-node development only.
 *
 * Migration: No change needed — this class has no WAS dependencies.
 */
public class ClaimNumberGenerator {

    private static final AtomicLong sequence = new AtomicLong(1);
    private static final SimpleDateFormat DATE_PART = new SimpleDateFormat("yyyyMMdd");

    public static String generate() {
        String datePart = DATE_PART.format(new Date());
        long seq = sequence.getAndIncrement();
        return String.format("CLM-%s-%06d", datePart, seq);
    }
}
