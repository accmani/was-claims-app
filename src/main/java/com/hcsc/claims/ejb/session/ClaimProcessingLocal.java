package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import javax.ejb.Local;
import java.util.List;

/**
 * EJB Local interface for ClaimProcessingBean.
 * Used by other EJBs and servlets within the same JVM.
 * Migration: This interface is removed in Spring Boot — direct @Autowired injection.
 */
@Local
public interface ClaimProcessingLocal {
    Claim submitClaim(Claim claim, String submittedBy);
    Claim appealClaim(Long originalClaimId, String appealReason, String appealedBy);
    List<Claim> getClaimsForMember(String memberId, int page, int pageSize);
    List<Claim> findPendingClaimsOlderThan(int days);
}
