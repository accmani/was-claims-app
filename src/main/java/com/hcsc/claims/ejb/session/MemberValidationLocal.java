package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Member;
import javax.ejb.Local;
import java.util.Date;

@Local
public interface MemberValidationLocal {
    Member validateMemberEligibility(String memberNumber, Date dateOfService);
    boolean hasAuthorization(String memberNumber, String procedureCode, Date serviceDate);
}
