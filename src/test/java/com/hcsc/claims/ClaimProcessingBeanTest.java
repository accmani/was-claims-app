package com.hcsc.claims;

import com.hcsc.claims.ejb.session.BenefitCalculationBean;
import com.hcsc.claims.ejb.session.ClaimProcessingBean;
import com.hcsc.claims.ejb.session.FraudDetectionBean;
import com.hcsc.claims.ejb.session.MemberValidationBean;
import com.hcsc.claims.entity.Claim;
import com.hcsc.claims.entity.Member;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.persistence.EntityManager;
import javax.transaction.UserTransaction;
import java.math.BigDecimal;
import java.util.Date;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClaimProcessingBean.
 * Uses JUnit 4 + Mockito 1.x (WAS era dependencies).
 *
 * Migration: Upgrade to JUnit 5 + Mockito 4.x + Spring Boot Test.
 * Replace @InjectMocks with Spring context / @MockBean.
 * Replace Matchers with ArgumentMatchers.
 */
public class ClaimProcessingBeanTest {

    @InjectMocks
    private ClaimProcessingBean claimProcessingBean;

    @Mock
    private EntityManager entityManager;

    @Mock
    private UserTransaction userTransaction;

    @Mock
    private MemberValidationBean memberValidationBean;

    @Mock
    private BenefitCalculationBean benefitCalculationBean;

    @Mock
    private FraudDetectionBean fraudDetectionBean;

    private Claim testClaim;
    private Member testMember;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        testClaim = new Claim();
        testClaim.setMemberId("MBR12345");
        testClaim.setProviderNpi("1234567890");
        testClaim.setServiceDate(new Date());
        testClaim.setBilledAmount(new BigDecimal("500.00"));
        testClaim.setProcedureCode("99213");
        testClaim.setDiagnosisCode("Z00.00");
        testClaim.setPlanCode("PPO-STANDARD");
        testClaim.setGroupNumber("GRP001");

        testMember = new Member();
        testMember.setMemberNumber("MBR12345");
        testMember.setStatus("ACTIVE");
        testMember.setPlanCode("PPO-STANDARD");
        testMember.setEffectiveDate(new Date(System.currentTimeMillis() - 86400000L * 365));
    }

    @Test
    public void testSubmitClaim_EligibleMember_ApprovedClaim() throws Exception {
        // Given
        when(memberValidationBean.validateMemberEligibility(anyString(), any(Date.class)))
            .thenReturn(testMember);
        when(fraudDetectionBean.checkForFraud(any(Claim.class)))
            .thenReturn(false);
        when(benefitCalculationBean.calculateAllowedAmount(any(Claim.class), anyString()))
            .thenReturn(new BigDecimal("400.00"));
        when(benefitCalculationBean.calculateMemberCostShare(any(Claim.class), any(Member.class)))
            .thenReturn(new BigDecimal("80.00"));

        // When
        Claim result = claimProcessingBean.submitClaim(testClaim, "adjuster@hcsc.com");

        // Then
        assertNotNull(result);
        assertEquals("APPROVED", result.getStatus());
        assertNotNull(result.getClaimNumber());
        assertTrue(result.getClaimNumber().startsWith("CLM-"));
        assertEquals(new BigDecimal("400.00"), result.getAllowedAmount());
        assertEquals(new BigDecimal("320.00"), result.getPaidAmount()); // 400 - 80

        verify(userTransaction).begin();
        verify(userTransaction).commit();
        verify(entityManager).persist(any(Claim.class));
    }

    @Test
    public void testSubmitClaim_IneligibleMember_DeniedClaim() throws Exception {
        // Given — member not eligible
        when(memberValidationBean.validateMemberEligibility(anyString(), any(Date.class)))
            .thenReturn(null);

        // When
        Claim result = claimProcessingBean.submitClaim(testClaim, "adjuster@hcsc.com");

        // Then
        assertNotNull(result);
        assertEquals("DENIED", result.getStatus());
        assertNotNull(result.getDenialReason());
        assertTrue(result.getDenialReason().contains("not eligible"));

        verify(userTransaction).begin();
        verify(userTransaction).rollback();
        verify(entityManager, never()).persist(any());
    }

    @Test
    public void testSubmitClaim_FraudDetected_PendingReview() throws Exception {
        // Given — fraud detected
        when(memberValidationBean.validateMemberEligibility(anyString(), any(Date.class)))
            .thenReturn(testMember);
        when(fraudDetectionBean.checkForFraud(any(Claim.class)))
            .thenReturn(true);

        // When
        Claim result = claimProcessingBean.submitClaim(testClaim, "adjuster@hcsc.com");

        // Then
        assertNotNull(result);
        assertEquals("PENDING_REVIEW", result.getStatus());

        verify(benefitCalculationBean, never()).calculateAllowedAmount(any(), any());
        verify(userTransaction).commit();
    }

    @Test
    public void testSubmitClaim_ExceptionDuringProcessing_TransactionRolledBack() throws Exception {
        // Given — exception during member validation
        when(memberValidationBean.validateMemberEligibility(anyString(), any(Date.class)))
            .thenThrow(new RuntimeException("Database connection lost"));

        // When / Then
        try {
            claimProcessingBean.submitClaim(testClaim, "adjuster@hcsc.com");
            fail("Expected EJBException");
        } catch (javax.ejb.EJBException e) {
            // Expected
        }

        verify(userTransaction).begin();
        verify(userTransaction).rollback();
        verify(userTransaction, never()).commit();
    }
}
