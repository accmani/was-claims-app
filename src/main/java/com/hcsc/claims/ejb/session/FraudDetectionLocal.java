package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import org.springframework.stereotype.Service;

@Service
public interface FraudDetectionLocal {
    boolean checkForFraud(Claim claim);
}
