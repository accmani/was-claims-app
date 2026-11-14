package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import com.hcsc.claims.entity.Member;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public interface BenefitCalculationLocal {
    BigDecimal calculateAllowedAmount(Claim claim, String planCode);
    BigDecimal calculateMemberCostShare(Claim claim, Member member);
}
