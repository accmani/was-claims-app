package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import java.util.List;

/**
 * EJB Remote interface for ClaimProcessingBean.
 * Used by external systems accessing this EJB over RMI-IIOP.
 *
 * Migration note:
 *   Remote EJB interfaces have NO direct Spring Boot equivalent.
 *   Remote callers must be migrated to REST API calls.
 *   THIS REQUIRES ARCHITECTURAL DECISION (Checkpoint D).
 *   Options:
 *     A) Replace with @RestController REST endpoint
 *     B) Replace with gRPC service
 *     C) Replace with Spring Integration gateway
 */
public interface ClaimProcessingRemote {
    Claim submitClaim(Claim claim, String submittedBy);
    Claim appealClaim(Long originalClaimId, String appealReason, String appealedBy);
    List<Claim> getClaimsForMember(String memberId, int page, int pageSize);
    List<Claim> findPendingClaimsOlderThan(int days);
}
