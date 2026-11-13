package com.hcsc.claims.entity;

import javax.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * EJB Entity Bean — ClaimLineItem
 * Individual service line within a claim.
 * One claim has 1..N line items.
 */
@Entity
@Table(name = "CLAIM_LINE_ITEMS", schema = "HCSC_CLAIMS")
public class ClaimLineItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "LINE_ITEM_SEQ")
    @SequenceGenerator(name = "LINE_ITEM_SEQ", sequenceName = "HCSC_CLAIMS.LINE_ITEM_SEQ", allocationSize = 1)
    @Column(name = "LINE_ITEM_ID")
    private Long lineItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "CLAIM_ID", nullable = false)
    private Claim claim;

    @Column(name = "LINE_NUMBER", nullable = false)
    private Integer lineNumber;

    @Column(name = "PROCEDURE_CODE", nullable = false, length = 10)
    private String procedureCode;

    @Column(name = "MODIFIER", length = 2)
    private String modifier;

    @Column(name = "UNITS", nullable = false)
    private Integer units;

    @Column(name = "BILLED_AMOUNT", nullable = false, precision = 10, scale = 2)
    private BigDecimal billedAmount;

    @Column(name = "ALLOWED_AMOUNT", precision = 10, scale = 2)
    private BigDecimal allowedAmount;

    @Column(name = "PAID_AMOUNT", precision = 10, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "STATUS", length = 20)
    private String status;

    @Column(name = "DENIAL_REASON", length = 500)
    private String denialReason;

    public ClaimLineItem() {}

    // Getters and setters
    public Long getLineItemId() { return lineItemId; }
    public void setLineItemId(Long lineItemId) { this.lineItemId = lineItemId; }
    public Claim getClaim() { return claim; }
    public void setClaim(Claim claim) { this.claim = claim; }
    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }
    public String getProcedureCode() { return procedureCode; }
    public void setProcedureCode(String procedureCode) { this.procedureCode = procedureCode; }
    public String getModifier() { return modifier; }
    public void setModifier(String modifier) { this.modifier = modifier; }
    public Integer getUnits() { return units; }
    public void setUnits(Integer units) { this.units = units; }
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
}
