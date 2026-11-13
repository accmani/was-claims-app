package com.hcsc.claims.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

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
@Component
public class ClaimNumberGenerator {

    private static final AtomicLong sequence = new AtomicLong(1);
    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static String generate() {
        String datePart = LocalDate.now().format(DATE_PART);
        long seq = sequence.getAndIncrement();
        return String.format("CLM-%s-%06d", datePart, seq);
    }
}
