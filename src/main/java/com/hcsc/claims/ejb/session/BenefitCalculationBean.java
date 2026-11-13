package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import com.hcsc.claims.entity.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * EJB 3.1 Stateless Session Bean — Benefit Calculation
 *
 * Calculates allowed amounts and member cost share based on plan benefits.
 *
 * Uses JNDI DataSource directly (JDBC template style) for benefit table lookups.
 * This is a common WAS pattern where JPA is too heavy for simple lookups.
 *
 * Migration:
 *   - JNDI DataSource → @Autowired JdbcTemplate
 *   - Manual JDBC → NamedParameterJdbcTemplate or Spring Data
 *   - com.ibm.db2.jcc.DB2DataSource reference removed
 */
@Service
@Transactional
public class BenefitCalculationBean implements BenefitCalculationLocal {

    private static final Logger log = LoggerFactory.getLogger(BenefitCalculationBean.class);
    private static final String DATASOURCE_JNDI = "jdbc/hcscClaimsDS";

    // Standard cost share rates — in production these come from benefit tables
    private static final BigDecimal STANDARD_COINSURANCE = new BigDecimal("0.20");   // 20%
    private static final BigDecimal PREVENTIVE_COINSURANCE = BigDecimal.ZERO;        // 0% (covered 100%)
    private static final BigDecimal ANNUAL_DEDUCTIBLE_STANDARD = new BigDecimal("1500.00");
    private static final BigDecimal OUT_OF_POCKET_MAX = new BigDecimal("6000.00");

    private final JdbcTemplate jdbcTemplate;

    public BenefitCalculationBean(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public BigDecimal calculateAllowedAmount(Claim claim, String planCode) {
        log.info("Calculating allowed amount for claim: {} plan: {} procedure: {}", claim.getClaimNumber(), planCode, claim.getProcedureCode());

        // Look up allowed amount from fee schedule
        String sql =
            "SELECT ALLOWED_AMOUNT, FEE_SCHEDULE_TYPE " +
            "FROM HCSC_CLAIMS.FEE_SCHEDULE " +
            "WHERE PROCEDURE_CODE = ? " +
            "  AND PLAN_CODE = ? " +
            "  AND EFFECTIVE_DATE <= CURRENT_DATE " +
            "  AND (EXPIRATION_DATE IS NULL OR EXPIRATION_DATE >= CURRENT_DATE) " +
            "ORDER BY EFFECTIVE_DATE DESC " +
            "FETCH FIRST 1 ROWS ONLY";   // DB2 syntax — migrate to LIMIT 1

        return jdbcTemplate.query(sql, new Object[]{claim.getProcedureCode(), planCode}, rs -> {
            if (rs.next()) {
                BigDecimal allowedAmount = rs.getBigDecimal("ALLOWED_AMOUNT");
                log.info("Fee schedule lookup successful: {} for procedure: {}", allowedAmount, claim.getProcedureCode());
                return allowedAmount;
            } else {
                // No fee schedule found — use billed amount capped at UCR
                log.warn("No fee schedule found for procedure: {} plan: {} — using billed amount", claim.getProcedureCode(), planCode);
                return calculateUCRAmount(claim.getBilledAmount());
            }
        });
    }

    @Transactional(propagation = Propagation.SUPPORTS)
    public BigDecimal calculateMemberCostShare(Claim claim, Member member) {
        log.info("Calculating member cost share for claim: {}", claim.getClaimNumber());

        // Check if preventive care — $0 cost share per ACA requirement
        if (isPreventiveCare(claim.getProcedureCode())) {
            log.info("Preventive care — zero cost share for claim: {}", claim.getClaimNumber());
            return BigDecimal.ZERO;
        }

        BigDecimal allowedAmount = claim.getAllowedAmount();
        if (allowedAmount == null) {
            allowedAmount = claim.getBilledAmount();
        }

        // Calculate deductible remaining (simplified — real implementation queries accumulator)
        BigDecimal deductibleRemaining = getDeductibleRemaining(member.getMemberNumber());
        BigDecimal deductibleApplied = deductibleRemaining.min(allowedAmount);
        BigDecimal afterDeductible = allowedAmount.subtract(deductibleApplied);

        // Apply coinsurance on amount after deductible
        BigDecimal coinsurance = afterDeductible.multiply(STANDARD_COINSURANCE)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalMemberCostShare = deductibleApplied.add(coinsurance);

        // Apply out-of-pocket maximum
        BigDecimal oopRemaining = getOutOfPocketRemaining(member.getMemberNumber());
        totalMemberCostShare = totalMemberCostShare.min(oopRemaining);

        log.info("Member cost share: {} (deductible: {} coinsurance: {})", totalMemberCostShare, deductibleApplied, coinsurance);

        return totalMemberCostShare;
    }

    private boolean isPreventiveCare(String procedureCode) {
        // ACA preventive care CPT codes — simplified list
        String[] preventiveCodes = {
            "99381", "99382", "99383", "99384", "99385", "99386", "99387",  // Preventive E&M
            "99391", "99392", "99393", "99394", "99395", "99396", "99397",  // Preventive established
            "G0101", "G0123", "G0124", "G0141", "G0143", "G0144", "G0145",  // Screening
            "77067", "77066", "77065"                                         // Mammography
        };
        for (String code : preventiveCodes) {
            if (code.equals(procedureCode)) return true;
        }
        return false;
    }

    private BigDecimal getDeductibleRemaining(String memberNumber) {
        // Simplified — real implementation queries accumulator table
        return ANNUAL_DEDUCTIBLE_STANDARD;
    }

    private BigDecimal getOutOfPocketRemaining(String memberNumber) {
        // Simplified — real implementation queries accumulator table
        return OUT_OF_POCKET_MAX;
    }

    private BigDecimal calculateUCRAmount(BigDecimal billedAmount) {
        // UCR = Usual, Customary, and Reasonable — typically 80th percentile of regional fees
        // Simplified: cap at 85% of billed
        return billedAmount.multiply(new BigDecimal("0.85"))
            .setScale(2, RoundingMode.HALF_UP);
    }
}
