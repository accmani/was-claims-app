package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Member;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Date;

@Service
@Transactional
public interface MemberValidationLocal {
    Member validateMemberEligibility(String memberNumber, Date dateOfService);
    boolean hasAuthorization(String memberNumber, String procedureCode, Date serviceDate);
}
