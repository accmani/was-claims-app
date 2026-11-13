package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import com.hcsc.claims.entity.Member;
import javax.ejb.Local;
import java.math.BigDecimal;

@Local
public interface BenefitCalculationLocal {
    BigDecimal calculateAllowedAmount(Claim claim, String planCode);
    BigDecimal calculateMemberCostShare(Claim claim, Member member);
}
