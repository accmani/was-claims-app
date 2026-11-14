package com.hcsc.claims;

import com.hcsc.claims.ejb.session.BenefitCalculationBean;
import com.hcsc.claims.ejb.session.ClaimProcessingBean;
import com.hcsc.claims.ejb.session.FraudDetectionBean;
import com.hcsc.claims.ejb.session.MemberValidationBean;
import com.hcsc.claims.entity.Claim;
import com.hcsc.claims.entity.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.mock.mockito.MockBean;

import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ClaimProcessingBean.
 * Uses JUnit 5 + Mockito 4.x + Spring Boot Test.
 */
public class ClaimProcessingBeanTest {

    @InjectMocks
    private ClaimProcessingBean claimProcessingBean;

    @Mock
    private EntityManager entityManager;

    @MockBean
    private UserTransaction userTransaction;

    @Mock
    private MemberValidationBean memberValidationBean;

    @Mock
    private BenefitCalculationBean benefitCalculationBean;

    @Mock
    private FraudDetectionBean fraudDetectionBean;

    private Claim testClaim;
    private Member testMember;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        testClaim = new Claim();
        testClaim.setMemberId("MBR12345");
        testClaim.setProviderNpi("1234567890");
        testClaim.setServiceDate(Instant.now()); // Changed to use Instant
        testClaim.setBilledAmount(new BigDecimal("500.00"));
        testClaim.setProcedureCode("99213");
        testClaim.setDiagnosisCode("Z00.00");
        testClaim.setPlanCode("PPO-STANDARD");
        testClaim.setGroupNumber("GRP001");

        testMember = new Member();
        testMember.setMemberNumber("MBR12345");
        testMember.setStatus("ACTIVE");
        testMember.setPlanCode("PPO-STANDARD");
        testMember.setEffectiveDate(Instant.now().minusSeconds(86400 * 365)); // Changed to use Instant
    }

    @Test
    public void testSubmitClaim_EligibleMember_ApprovedClaim() throws Exception {
        // Given
        when(memberValidationBean.validateMemberEligibility(anyString(), any(Instant.class))) // Changed to use Instant
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
        when(memberValidationBean.validateMemberEligibility(anyString(), any(Instant.class))) // Changed to use Instant
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
        when(memberValidationBean.validateMemberEligibility(anyString(), any(Instant.class))) // Changed to use Instant
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
        when(memberValidationBean.validateMemberEligibility(anyString(), any(Instant.class))) // Changed to use Instant
            .thenThrow(new RuntimeException("Database connection lost"));

        // When / Then
        try {
            claimProcessingBean.submitClaim(testClaim, "adjuster@hcsc.com");
            fail("Expected EJBException");
        } catch (jakarta.ejb.EJBException e) {
            // Expected
        }

        verify(userTransaction).begin();
        verify(userTransaction).rollback();
        verify(userTransaction, never()).commit();
    }
}
