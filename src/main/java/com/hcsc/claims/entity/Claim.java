package com.hcsc.claims.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * EJB Entity Bean — Claim
 * Represents a healthcare insurance claim submitted to HCSC.
 *
 * WAS deployment: Container-managed persistence via WAS JPA provider.
 * JNDI DataSource: jdbc/hcscClaimsDS
 *
 * Migration note:
 *   - Remove WAS-specific persistence.xml config
 *   - Replace JNDI DataSource with spring.datasource.* config
 *   - Replace IBM JPA provider with Hibernate
 *   - fetchType.LAZY requires open-session-in-view consideration
 */
@Entity
@Table(name = "CLAIMS", schema = "HCSC_CLAIMS")
@NamedQueries({
    @NamedQuery(
        name = "Claim.findByMemberId",
        query = "SELECT c FROM Claim c WHERE c.memberId = :memberId ORDER BY c.submittedDate DESC"
    ),
    @NamedQuery(
        name = "Claim.findPendingClaims",
        query = "SELECT c FROM Claim c WHERE c.status = 'PENDING' AND c.submittedDate < :cutoffDate"
    ),
    @NamedQuery(
        name = "Claim.findByClaimNumber",
        query = "SELECT c FROM Claim c WHERE c.claimNumber = :claimNumber"
    )
})
public class Claim implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "CLAIM_SEQ")
    @SequenceGenerator(name = "CLAIM_SEQ", sequenceName = "HCSC_CLAIMS.CLAIM_SEQ", allocationSize = 1)
    @Column(name = "CLAIM_ID")
    private Long claimId;

    @Column(name = "CLAIM_NUMBER", unique = true, nullable = false, length = 20)
    private String claimNumber;

    @Column(name = "MEMBER_ID", nullable = false, length = 15)
    private String memberId;

    @Column(name = "PROVIDER_NPI", nullable = false, length = 10)
    private String providerNpi;

    @Column(name = "SERVICE_DATE", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "SUBMITTED_DATE", nullable = false)
    private LocalDateTime submittedDate;

    @Column(name = "BILLED_AMOUNT", nullable = false, precision = 12, scale = 2)
    private BigDecimal billedAmount;

    @Column(name = "ALLOWED_AMOUNT", precision = 12, scale = 2)
    private BigDecimal allowedAmount;

    @Column(name = "PAID_AMOUNT", precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "STATUS", nullable = false, length = 20)
    private String status;  // PENDING, APPROVED, DENIED, PARTIAL, APPEALED

    @Column(name = "DENIAL_REASON", length = 500)
    private String denialReason;

    @Column(name = "DIAGNOSIS_CODE", length = 10)
    private String diagnosisCode;  // ICD-10

    @Column(name = "PROCEDURE_CODE", length = 10)
    private String procedureCode;  // CPT

    @Column(name = "PLAN_CODE", nullable = false, length = 10)
    private String planCode;

    @Column(name = "GROUP_NUMBER", length = 15)
    private String groupNumber;

    @Column(name = "IS_PHI", nullable = false)
    private Boolean isPhi = true;  // All claims contain PHI

    // One claim has many line items
    @OneToMany(mappedBy = "claim", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<ClaimLineItem> lineItems = new ArrayList<>();

    // Many claims can be appealed — self-referential for appeal chain
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ORIGINAL_CLAIM_ID")
    private Claim originalClaim;

    @Column(name = "CREATED_BY", length = 50)
    private String createdBy;

    @Column(name = "UPDATED_DATE")
    private LocalDateTime updatedDate;

    @Version
    @Column(name = "VERSION")
    private Integer version;

    // Default constructor required by JPA spec
    public Claim() {}

    public Claim(String memberId, String providerNpi, LocalDate serviceDate, BigDecimal billedAmount) {
        this.memberId = memberId;
        this.providerNpi = providerNpi;
        this.serviceDate = serviceDate;
        this.billedAmount = billedAmount;
        this.submittedDate = LocalDateTime.now();
        this.status = "PENDING";
        this.isPhi = true;
    }

    @PrePersist
    public void prePersist() {
        this.submittedDate = LocalDateTime.now();
        this.status = "PENDING";
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedDate = LocalDateTime.now();
    }

    // Getters and setters
    public Long getClaimId() { return claimId; }
    public void setClaimId(Long claimId) { this.claimId = claimId; }

    public String getClaimNumber() { return claimNumber; }
    public void setClaimNumber(String claimNumber) { this.claimNumber = claimNumber; }

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }

    public String getProviderNpi() { return providerNpi; }
    public void setProviderNpi(String providerNpi) { this.providerNpi = providerNpi; }

    public LocalDate getServiceDate() { return serviceDate; }
    public void setServiceDate(LocalDate serviceDate) { this.serviceDate = serviceDate; }

    public LocalDateTime getSubmittedDate() { return submittedDate; }
    public void setSubmittedDate(LocalDateTime submittedDate) { this.submittedDate = submittedDate; }

    public BigDecimal getBilledAmount() { return billedAmount; }
    public void setBilledAmount(BigDecimal billedAmount) { this.billedAmount = billedAmount; }

    public BigDecimal getAllowedAmount() { return allowedAmount; }
    public void setAllowedAmount(BigDecimal allowedAmount) { this.allowedAmount = allowedAmount; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDenialReason() { return denialReason; }
    public void setDenialReason(String denialReason) { this.denialReason = denialReason; }

    public String getDiagnosisCode() { return diagnosisCode; }
    public void setDiagnosisCode(String diagnosisCode) { this.diagnosisCode = diagnosisCode; }

    public String getProcedureCode() { return procedureCode; }
    public void setProcedureCode(String procedureCode) { this.procedureCode = procedureCode; }

    public String getPlanCode() { return planCode; }
    public void setPlanCode(String planCode) { this.planCode = planCode; }

    public String getGroupNumber() { return groupNumber; }
    public void setGroupNumber(String groupNumber) { this.groupNumber = groupNumber; }

    public Boolean getIsPhi() { return isPhi; }
    public void setIsPhi(Boolean isPhi) { this.isPhi = isPhi; }

    public List<ClaimLineItem> getLineItems() { return lineItems; }
    public void setLineItems(List<ClaimLineItem> lineItems) { this.lineItems = lineItems; }

    public Claim getOriginalClaim() { return originalClaim; }
    public void setOriginalClaim(Claim originalClaim) { this.originalClaim = originalClaim; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getUpdatedDate() { return updatedDate; }
    public void setUpdatedDate(LocalDateTime updatedDate) { this.updatedDate = updatedDate; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
