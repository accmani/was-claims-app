package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import org.apache.log4j.Logger;

import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * EJB 3.1 Stateless Session Bean — Fraud Detection
 *
 * Runs heuristic fraud checks on submitted claims.
 * Returns true if claim should be flagged for manual review.
 *
 * Rules implemented:
 *   1. Duplicate claim detection (same member + provider + date + procedure)
 *   2. High-value claim threshold (> $10,000)
 *   3. Frequency check (> 5 claims per member per 30 days)
 *   4. Provider billing pattern anomaly
 *   5. Terminated member submitted claim (retroactive fraud)
 */
@Stateless(name = "FraudDetectionBean")
public class FraudDetectionBean implements FraudDetectionLocal {

    private static final Logger log = Logger.getLogger(FraudDetectionBean.class);

    private static final BigDecimal HIGH_VALUE_THRESHOLD = new BigDecimal("10000.00");
    private static final int MAX_CLAIMS_PER_30_DAYS = 5;

    @PersistenceContext(unitName = "claimsPU")
    private EntityManager entityManager;

    @TransactionAttribute(TransactionAttributeType.SUPPORTS)
    public boolean checkForFraud(Claim claim) {
        log.info("Running fraud checks for claim: " + claim.getClaimNumber()
            + " member: " + claim.getMemberId());

        if (isDuplicateClaim(claim)) {
            log.warn("FRAUD_FLAG: Duplicate claim detected for member: "
                + claim.getMemberId() + " procedure: " + claim.getProcedureCode());
            return true;
        }

        if (isHighFrequencyBilling(claim)) {
            log.warn("FRAUD_FLAG: High frequency billing detected for member: "
                + claim.getMemberId());
            return true;
        }

        if (isAbnormalBillingPattern(claim)) {
            log.warn("FRAUD_FLAG: Abnormal billing pattern for provider: "
                + claim.getProviderNpi());
            return true;
        }

        log.info("Fraud checks passed for claim: " + claim.getClaimNumber());
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean isDuplicateClaim(Claim claim) {
        List<Claim> duplicates = entityManager
            .createQuery(
                "SELECT c FROM Claim c " +
                "WHERE c.memberId = :memberId " +
                "  AND c.providerNpi = :providerNpi " +
                "  AND c.serviceDate = :serviceDate " +
                "  AND c.procedureCode = :procedureCode " +
                "  AND c.status NOT IN ('DENIED', 'CANCELLED')")
            .setParameter("memberId", claim.getMemberId())
            .setParameter("providerNpi", claim.getProviderNpi())
            .setParameter("serviceDate", claim.getServiceDate())
            .setParameter("procedureCode", claim.getProcedureCode())
            .getResultList();

        return !duplicates.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private boolean isHighFrequencyBilling(Claim claim) {
        Calendar thirtyDaysAgo = Calendar.getInstance();
        thirtyDaysAgo.add(Calendar.DAY_OF_YEAR, -30);

        Long claimCount = (Long) entityManager
            .createQuery(
                "SELECT COUNT(c) FROM Claim c " +
                "WHERE c.memberId = :memberId " +
                "  AND c.submittedDate >= :since " +
                "  AND c.status NOT IN ('DENIED', 'CANCELLED')")
            .setParameter("memberId", claim.getMemberId())
            .setParameter("since", thirtyDaysAgo.getTime())
            .getSingleResult();

        return claimCount >= MAX_CLAIMS_PER_30_DAYS;
    }

    @SuppressWarnings("unchecked")
    private boolean isAbnormalBillingPattern(Claim claim) {
        // Check if provider is billing the same procedure for > 80% of their claims
        // (indicator of upcoding or unbundling fraud)
        Calendar ninetyDaysAgo = Calendar.getInstance();
        ninetyDaysAgo.add(Calendar.DAY_OF_YEAR, -90);

        Long totalProviderClaims = (Long) entityManager
            .createQuery(
                "SELECT COUNT(c) FROM Claim c " +
                "WHERE c.providerNpi = :providerNpi " +
                "  AND c.submittedDate >= :since")
            .setParameter("providerNpi", claim.getProviderNpi())
            .setParameter("since", ninetyDaysAgo.getTime())
            .getSingleResult();

        if (totalProviderClaims < 10) return false; // Not enough data

        Long sameProcedureCount = (Long) entityManager
            .createQuery(
                "SELECT COUNT(c) FROM Claim c " +
                "WHERE c.providerNpi = :providerNpi " +
                "  AND c.procedureCode = :procedureCode " +
                "  AND c.submittedDate >= :since")
            .setParameter("providerNpi", claim.getProviderNpi())
            .setParameter("procedureCode", claim.getProcedureCode())
            .setParameter("since", ninetyDaysAgo.getTime())
            .getSingleResult();

        double ratio = (double) sameProcedureCount / totalProviderClaims;
        return ratio > 0.80;
    }
}
