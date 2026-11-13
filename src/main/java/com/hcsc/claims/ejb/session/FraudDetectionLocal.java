package com.hcsc.claims.ejb.session;

import com.hcsc.claims.entity.Claim;
import javax.ejb.Local;

@Local
public interface FraudDetectionLocal {
    boolean checkForFraud(Claim claim);
}
