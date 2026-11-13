package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.util.Calendar;
import java.util.Date;

/**
 * EJB 3.1 Stateless Session Bean — Member Validation
 * Validates member eligibility for claims.
 *
 * WAS Transaction: SUPPORTS — joins caller's transaction if one exists,
 * but does not require one. This needs careful mapping to Spring's
 * @Transactional(propagation = Propagation.SUPPORTS)
 */
@Transactional
@Service
public class MemberValidationBean implements MemberValidationLocal {

    private static final Logger log = LoggerFactory.getLogger(MemberValidationBean.class);

    @PersistenceContext(unitName = "claimsPU")
    private EntityManager entityManager;

    /**
     * Validate member eligibility on a given date of service.
     * Returns the Member if eligible, null if not.
     *
     * HIPAA compliance: Do NOT log member name or DOB.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public Member validateMemberEligibility(String memberNumber, Date dateOfService) {
        log.info("Validating eligibility for member: " + memberNumber
            + " on " + dateOfService);

        try {
            Member member = (Member) entityManager
                .createQuery(
                    "SELECT m FROM Member m WHERE m.memberNumber = :memberNumber")
                .setParameter("memberNumber", memberNumber)
                .getSingleResult();

            // Check status
            if (!"ACTIVE".equals(member.getStatus())
                && !"COBRA".equals(member.getStatus())) {
                log.info("Member not eligible — status: " + member.getStatus());
                return null;
            }

            // Check effective date
            if (member.getEffectiveDate().after(dateOfService)) {
                log.info("Member not eligible — coverage not yet effective");
                return null;
            }

            // Check termination date
            if (member.getTerminationDate() != null
                && member.getTerminationDate().before(dateOfService)) {
                log.info("Member not eligible — coverage terminated");
                return null;
            }

            // COBRA has 18-month limit from termination of original coverage
            if ("COBRA".equals(member.getStatus())) {
                if (!isCobraStillValid(member, dateOfService)) {
                    log.info("Member COBRA coverage has expired");
                    return null;
                }
            }

            log.info("Member eligible: " + memberNumber);
            return member;
        } catch (NoResultException e) {
            log.warn("Member not found: " + memberNumber);
            return null;
        }
    }

    /**
     * Check if COBRA coverage is within the 18-month window.
     */
    private boolean isCobraStillValid(Member member, Date dateOfService) {
        if (member.getTerminationDate() == null) return false;

        Calendar cobraExpiry = Calendar.getInstance();
        cobraExpiry.setTime(member.getTerminationDate());
        cobraExpiry.add(Calendar.MONTH, 18);

        return !dateOfService.after(cobraExpiry.getTime());
    }

    /**
     * Check if member has an active authorization for a given procedure.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean hasAuthorization(String memberNumber, String procedureCode, Date serviceDate) {
        log.info("Checking authorization for member: " + memberNumber
            + " procedure: " + procedureCode);

        Long count = (Long) entityManager
            .createQuery(
                "SELECT COUNT(a) FROM Authorization a " +
                "WHERE a.memberNumber = :memberNumber " +
                "AND a.procedureCode = :procedureCode " +
                "AND a.effectiveDate <= :serviceDate " +
                "AND (a.expirationDate IS NULL OR a.expirationDate >= :serviceDate) " +
                "AND a.status = 'APPROVED'")
            .setParameter("memberNumber", memberNumber)
            .setParameter("procedureCode", procedureCode)
            .setParameter("serviceDate", serviceDate)
            .getSingleResult();

        return count > 0;
    }
}
