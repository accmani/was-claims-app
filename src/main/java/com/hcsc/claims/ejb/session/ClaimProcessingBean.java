package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import com.hcsc.claims.entity.ClaimLineItem;
import com.hcsc.claims.entity.Member;
import com.hcsc.claims.util.ClaimNumberGenerator;
import com.hcsc.claims.util.PHIAuditLogger;
import org.apache.log4j.Logger;

import jakarta.annotation.Resource;
import jakarta.ejb.*;
import jakarta.jms.*;
import jakarta.naming.InitialContext;
import jakarta.naming.NamingException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.List;

@Service
public class ClaimProcessingBean implements ClaimProcessingLocal, ClaimProcessingRemote {

    private static final Logger log = Logger.getLogger(ClaimProcessingBean.class);

    private static final String CLAIM_QUEUE_JNDI       = "jms/claimsNotificationQueue";
    private static final String JMS_FACTORY_JNDI        = "jms/ClaimsConnectionFactory";
    private static final String DATASOURCE_JNDI         = "jdbc/hcscClaimsDS";
    private static final BigDecimal LARGE_CLAIM_THRESHOLD = new BigDecimal("10000.00");
    private static final int     MAX_RETRY_ATTEMPTS      = 3;

    @PersistenceContext(unitName = "claimsPU")
    private EntityManager entityManager;

    @Resource
    private UserTransaction userTransaction;

    private final MemberValidationBean memberValidationBean;

    private final BenefitCalculationBean benefitCalculationBean;

    private final FraudDetectionBean fraudDetectionBean;

    public ClaimProcessingBean(MemberValidationBean memberValidationBean, BenefitCalculationBean benefitCalculationBean, FraudDetectionBean fraudDetectionBean) {
        this.memberValidationBean = memberValidationBean;
        this.benefitCalculationBean = benefitCalculationBean;
        this.fraudDetectionBean = fraudDetectionBean;
    }

    public Claim submitClaim(Claim claim, String submittedBy) {
        log.info("Submitting claim for member: " + claim.getMemberId());

        try {
            userTransaction.begin();

            Member member = memberValidationBean.validateMemberEligibility(
                claim.getMemberId(), claim.getServiceDate());
            if (member == null) {
                claim.setStatus("DENIED");
                claim.setDenialReason("Member not eligible on date of service");
                log.warn("Claim denied — member not eligible: " + claim.getMemberId());
                userTransaction.rollback();
                return claim;
            }

            claim.setClaimNumber(ClaimNumberGenerator.generate());
            claim.setCreatedBy(submittedBy);
            claim.setSubmittedDate(LocalDate.now()); // Changed to LocalDate

            boolean fraudFlag = fraudDetectionBean.checkForFraud(claim);
            if (fraudFlag) {
                claim.setStatus("PENDING_REVIEW");
                claim.setDenialReason("Flagged for fraud review");
                log.warn("Claim flagged for fraud review: " + claim.getClaimNumber());
            } else {
                BigDecimal allowedAmount = benefitCalculationBean.calculateAllowedAmount(
                    claim, member.getPlanCode());
                claim.setAllowedAmount(allowedAmount);

                BigDecimal memberCostShare = benefitCalculationBean.calculateMemberCostShare(
                    claim, member);
                BigDecimal paidAmount = allowedAmount.subtract(memberCostShare);
                claim.setPaidAmount(paidAmount.compareTo(BigDecimal.ZERO) < 0
                    ? BigDecimal.ZERO : paidAmount);

                claim.setStatus("APPROVED");
            }

            entityManager.persist(claim);
            entityManager.flush();

            if (claim.getLineItems() != null) {
                for (ClaimLineItem lineItem : claim.getLineItems()) {
                    lineItem.setClaim(claim);
                    entityManager.persist(lineItem);
                }
            }

            if (claim.getBilledAmount().compareTo(LARGE_CLAIM_THRESHOLD) >= 0) {
                sendClaimNotification(claim, "LARGE_CLAIM_ALERT");
            }

            sendClaimNotification(claim, "CLAIM_SUBMITTED");

            PHIAuditLogger.log("CLAIM_SUBMITTED", claim.getClaimNumber(),
                submittedBy, "Claim submitted and " + claim.getStatus());

            userTransaction.commit();

            log.info("Claim submitted successfully: " + claim.getClaimNumber()
                + " status=" + claim.getStatus());
            return claim;
        } catch (Exception e) {
            log.error("Error submitting claim for member: " + claim.getMemberId(), e);
            try {
                userTransaction.rollback();
            } catch (Exception rollbackEx) {
                log.error("Rollback failed", rollbackEx);
            }
            throw new RuntimeException("Failed to submit claim", e);
        }
    }

    public Claim appealClaim(Long originalClaimId, String appealReason, String appealedBy) {
        log.info("Processing appeal for claimId: " + originalClaimId);

        try {
            userTransaction.begin();

            Claim originalClaim = entityManager.find(Claim.class, originalClaimId);
            if (originalClaim == null) {
                throw new RuntimeException("Claim not found: " + originalClaimId);
            }

            if (!"DENIED".equals(originalClaim.getStatus())
                && !"PARTIAL".equals(originalClaim.getStatus())) {
                throw new RuntimeException("Only DENIED or PARTIAL claims can be appealed");
            }

            Calendar appealDeadline = Calendar.getInstance();
            appealDeadline.setTime(java.sql.Date.valueOf(originalClaim.getSubmittedDate())); // Adjusted to LocalDate
            appealDeadline.add(Calendar.DAY_OF_YEAR, 180);
            if (java.sql.Date.valueOf(LocalDate.now()).after(appealDeadline.getTime())) {
                throw new RuntimeException("Appeal deadline has passed — 180 days from original submission");
            }

            Claim appealClaim = new Claim();
            appealClaim.setMemberId(originalClaim.getMemberId());
            appealClaim.setProviderNpi(originalClaim.getProviderNpi());
            appealClaim.setServiceDate(originalClaim.getServiceDate());
            appealClaim.setBilledAmount(originalClaim.getBilledAmount());
            appealClaim.setDiagnosisCode(originalClaim.getDiagnosisCode());
            appealClaim.setProcedureCode(originalClaim.getProcedureCode());
            appealClaim.setPlanCode(originalClaim.getPlanCode());
            appealClaim.setGroupNumber(originalClaim.getGroupNumber());
            appealClaim.setOriginalClaim(originalClaim);
            appealClaim.setStatus("APPEALED");
            appealClaim.setClaimNumber(ClaimNumberGenerator.generate());
            appealClaim.setCreatedBy(appealedBy);
            appealClaim.setSubmittedDate(LocalDate.now()); // Changed to LocalDate
            appealClaim.setIsPhi(true);

            entityManager.persist(appealClaim);

            originalClaim.setStatus("UNDER_APPEAL");
            entityManager.merge(originalClaim);

            sendClaimNotification(appealClaim, "CLAIM_APPEALED");

            PHIAuditLogger.log("CLAIM_APPEALED", appealClaim.getClaimNumber(),
                appealedBy, "Appeal filed for original claim " + originalClaim.getClaimNumber());

            userTransaction.commit();

            log.info("Appeal created: " + appealClaim.getClaimNumber()
                + " for original: " + originalClaim.getClaimNumber());
            return appealClaim;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error processing appeal for claimId: " + originalClaimId, e);
            try { userTransaction.rollback(); } catch (Exception re) { log.error("Rollback failed", re); }
            throw new RuntimeException("Failed to process appeal", e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Claim> getClaimsForMember(String memberId, int page, int pageSize) {
        log.info("Retrieving claims for member: " + memberId
            + " page=" + page + " pageSize=" + pageSize);

        return entityManager
            .createNamedQuery("Claim.findByMemberId")
            .setParameter("memberId", memberId)
            .setFirstResult((page - 1) * pageSize)
            .setMaxResults(pageSize)
            .getResultList();
    }

    @SuppressWarnings("unchecked")
    public List<Claim> findPendingClaimsOlderThan(int days) {
        Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.DAY_OF_YEAR, -days);

        return entityManager
            .createNamedQuery("Claim.findPendingClaims")
            .setParameter("cutoffDate", cutoff.getTime())
            .getResultList();
    }

    private void sendClaimNotification(Claim claim, String eventType) {
        Connection connection = null;
        Session session = null;
        try {
            InitialContext ctx = new InitialContext();
            ConnectionFactory factory = (ConnectionFactory) ctx.lookup(JMS_FACTORY_JNDI);
            Queue queue = (Queue) ctx.lookup(CLAIM_QUEUE_JNDI);

            connection = factory.createConnection();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(queue);

            TextMessage message = session.createTextMessage();
            message.setText(buildNotificationPayload(claim, eventType));
            message.setStringProperty("eventType", eventType);
            message.setStringProperty("claimNumber", claim.getClaimNumber());
            message.setStringProperty("status", claim.getStatus());

            producer.send(message);
            log.info("JMS notification sent: " + eventType + " for claim: " + claim.getClaimNumber());
        } catch (NamingException e) {
            log.error("JNDI lookup failed for JMS resources", e);
        } catch (JMSException e) {
            log.error("Failed to send JMS notification for claim: " + claim.getClaimNumber(), e);
        } finally {
            try { if (session != null) session.close(); } catch (JMSException e) { /* ignore */ }
            try { if (connection != null) connection.close(); } catch (JMSException e) { /* ignore */ }
        }
    }

    private String buildNotificationPayload(Claim claim, String eventType) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"eventType\":\"").append(eventType).append("\",");
        sb.append("\"claimNumber\":\"").append(claim.getClaimNumber()).append("\",");
        sb.append("\"status\":\"").append(claim.getStatus()).append("\",");
        sb.append("\"billedAmount\":").append(claim.getBilledAmount()).append(",");
        sb.append("\"timestamp\":\"").append(LocalDateTime.now()).append("\"");
        sb.append("}");
        return sb.toString();
    }
}
