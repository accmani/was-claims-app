package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EJB Local interface for ClaimProcessingBean.
 * Used by other EJBs and servlets within the same JVM.
 * Migration: This interface is removed in Spring Boot — direct @Autowired injection.
 */
@Service
@Transactional
public interface ClaimProcessingLocal {
    Claim submitClaim(Claim claim, String submittedBy);
    Claim appealClaim(Long originalClaimId, String appealReason, String appealedBy);
    List<Claim> getClaimsForMember(String memberId, int page, int pageSize);
    List<Claim> findPendingClaimsOlderThan(int days);
}
