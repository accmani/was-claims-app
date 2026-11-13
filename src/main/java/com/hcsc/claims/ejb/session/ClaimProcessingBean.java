package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import com.hcsc.claims.entity.ClaimLineItem;
import com.hcsc.claims.entity.Member;
import com.hcsc.claims.util.ClaimNumberGenerator;
import com.hcsc.claims.util.PHIAuditLogger;
import org.apache.log4j.Logger;

import javax.annotation.Resource;
import javax.ejb.*;
import javax.jms.*;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.UserTransaction;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * EJB 3.1 Stateless Session Bean — Claim Processing
 *
 * Core business logic for HCSC claims adjudication.
 * Deployed on WebSphere Application Server 8.5.5
 *
 * IBM WAS specific:
 *   - @Resource UserTransaction via WAS JTA transaction manager
 *   - JNDI lookups for DataSource and JMS Queue
 *   - com.ibm.websphere.security.auth.WSSubject for identity
 *   - IBM MQ via JNDI: jms/claimsNotificationQueue
 *
 * Migration targets:
 *   - @Stateless → @Service
 *   - @EJB → @Autowired
 *   - UserTransaction → @Transactional
 *   - JNDI lookups → Spring @Value / @Autowired
 *   - IBM MQ → Spring JMS
 *   - javax.persistence → jakarta.persistence
 */
@Stateless(name = "ClaimProcessingBean", mappedName = "ejb/ClaimProcessingBean")
@TransactionManagement(TransactionManagementType.BEAN)
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

    @EJB
    private MemberValidationBean memberValidationBean;

    @EJB
    private BenefitCalculationBean benefitCalculationBean;

    @EJB
    private FraudDetectionBean fraudDetectionBean;

    /**
     * Submit a new claim for processing.
     * Validates member, calculates benefits, persists, and sends notification.
     *
     * @param claim      The claim to process
     * @param submittedBy The authenticated user submitting the claim
     * @return Processed claim with assigned claim number and initial adjudication
     */
    public Claim submitClaim(Claim claim, String submittedBy) {
        log.info("Submitting claim for member: " + claim.getMemberId());
        // NOTE: Do NOT log PHI — member name, DOB, SSN must never appear in logs

        try {
            userTransaction.begin();

            // Step 1: Validate member eligibility
            Member member = memberValidationBean.validateMemberEligibility(
                claim.getMemberId(), claim.getServiceDate());
            if (member == null) {
                claim.setStatus("DENIED");
                claim.setDenialReason("Member not eligible on date of service");
                log.warn("Claim denied — member not eligible: " + claim.getMemberId());
                userTransaction.rollback();
                return claim;
            }

            // Step 2: Generate unique claim number
            claim.setClaimNumber(ClaimNumberGenerator.generate());
            claim.setCreatedBy(submittedBy);
            claim.setSubmittedDate(new Date());

            // Step 3: Fraud detection check
            boolean fraudFlag = fraudDetectionBean.checkForFraud(claim);
            if (fraudFlag) {
                claim.setStatus("PENDING_REVIEW");
                claim.setDenialReason("Flagged for fraud review");
                log.warn("Claim flagged for fraud review: " + claim.getClaimNumber());
            } else {
                // Step 4: Calculate benefits
                BigDecimal allowedAmount = benefitCalculationBean.calculateAllowedAmount(
                    claim, member.getPlanCode());
                claim.setAllowedAmount(allowedAmount);

                // Step 5: Apply member cost share
                BigDecimal memberCostShare = benefitCalculationBean.calculateMemberCostShare(
                    claim, member);
                BigDecimal paidAmount = allowedAmount.subtract(memberCostShare);
                claim.setPaidAmount(paidAmount.compareTo(BigDecimal.ZERO) < 0
                    ? BigDecimal.ZERO : paidAmount);

                claim.setStatus("APPROVED");
            }

            // Step 6: Persist claim
            entityManager.persist(claim);
            entityManager.flush();

            // Step 7: Process line items
            if (claim.getLineItems() != null) {
                for (ClaimLineItem lineItem : claim.getLineItems()) {
                    lineItem.setClaim(claim);
                    entityManager.persist(lineItem);
                }
            }

            // Step 8: Send JMS notification for large claims
            if (claim.getBilledAmount().compareTo(LARGE_CLAIM_THRESHOLD) >= 0) {
                sendClaimNotification(claim, "LARGE_CLAIM_ALERT");
            }

            // Step 9: Send standard processing notification
            sendClaimNotification(claim, "CLAIM_SUBMITTED");

            // Step 10: PHI audit log — required for HIPAA compliance
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
            throw new EJBException("Failed to submit claim", e);
        }
    }

    /**
     * Appeal a denied claim.
     * Creates a new claim linked to the original, sets status to APPEALED.
     */
    public Claim appealClaim(Long originalClaimId, String appealReason, String appealedBy) {
        log.info("Processing appeal for claimId: " + originalClaimId);

        try {
            userTransaction.begin();

            Claim originalClaim = entityManager.find(Claim.class, originalClaimId);
            if (originalClaim == null) {
                throw new EJBException("Claim not found: " + originalClaimId);
            }

            if (!"DENIED".equals(originalClaim.getStatus())
                && !"PARTIAL".equals(originalClaim.getStatus())) {
                throw new EJBException("Only DENIED or PARTIAL claims can be appealed");
            }

            // Check appeal deadline — must be within 180 days of original decision
            Calendar appealDeadline = Calendar.getInstance();
            appealDeadline.setTime(originalClaim.getSubmittedDate());
            appealDeadline.add(Calendar.DAY_OF_YEAR, 180);
            if (new Date().after(appealDeadline.getTime())) {
                throw new EJBException("Appeal deadline has passed — 180 days from original submission");
            }

            // Create appeal claim
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
            appealClaim.setSubmittedDate(new Date());
            appealClaim.setIsPhi(true);

            entityManager.persist(appealClaim);

            // Update original claim status
            originalClaim.setStatus("UNDER_APPEAL");
            entityManager.merge(originalClaim);

            // Send appeal notification
            sendClaimNotification(appealClaim, "CLAIM_APPEALED");

            PHIAuditLogger.log("CLAIM_APPEALED", appealClaim.getClaimNumber(),
                appealedBy, "Appeal filed for original claim " + originalClaim.getClaimNumber());

            userTransaction.commit();

            log.info("Appeal created: " + appealClaim.getClaimNumber()
                + " for original: " + originalClaim.getClaimNumber());
            return appealClaim;

        } catch (EJBException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error processing appeal for claimId: " + originalClaimId, e);
            try { userTransaction.rollback(); } catch (Exception re) { log.error("Rollback failed", re); }
            throw new EJBException("Failed to process appeal", e);
        }
    }

    /**
     * Retrieve claims for a member with pagination.
     */
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

    /**
     * Find claims pending adjudication older than N days.
     * Used by batch processing job.
     */
    @SuppressWarnings("unchecked")
    public List<Claim> findPendingClaimsOlderThan(int days) {
        Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.DAY_OF_YEAR, -days);

        return entityManager
            .createNamedQuery("Claim.findPendingClaims")
            .setParameter("cutoffDate", cutoff.getTime())
            .getResultList();
    }

    /**
     * Send JMS notification to downstream systems.
     * Uses IBM MQ via JNDI.
     *
     * Migration: Replace with Spring JMS + JmsTemplate
     */
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
            // Note: claim number is not PHI — safe to include in message
            message.setText(buildNotificationPayload(claim, eventType));
            message.setStringProperty("eventType", eventType);
            message.setStringProperty("claimNumber", claim.getClaimNumber());
            message.setStringProperty("status", claim.getStatus());
            // Do NOT include memberId or provider details in JMS message properties

            producer.send(message);
            log.info("JMS notification sent: " + eventType + " for claim: " + claim.getClaimNumber());

        } catch (NamingException e) {
            log.error("JNDI lookup failed for JMS resources", e);
            // Non-fatal — claim processing continues even if notification fails
        } catch (JMSException e) {
            log.error("Failed to send JMS notification for claim: " + claim.getClaimNumber(), e);
        } finally {
            try { if (session != null) session.close(); } catch (JMSException e) { /* ignore */ }
            try { if (connection != null) connection.close(); } catch (JMSException e) { /* ignore */ }
        }
    }

    private String buildNotificationPayload(Claim claim, String eventType) {
        // Build JSON-like string (pre-JSON library era code)
        // Migration: Replace with ObjectMapper or Gson
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"eventType\":\"").append(eventType).append("\",");
        sb.append("\"claimNumber\":\"").append(claim.getClaimNumber()).append("\",");
        sb.append("\"status\":\"").append(claim.getStatus()).append("\",");
        sb.append("\"billedAmount\":").append(claim.getBilledAmount()).append(",");
        sb.append("\"timestamp\":\"").append(new Date()).append("\"");
        sb.append("}");
        return sb.toString();
    }
}
