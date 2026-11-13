package com.hcsc.claims.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * EJB Entity Bean — Member
 * HCSC health plan member. Contains PHI — handle with care.
 * PHI fields: firstName, lastName, dateOfBirth, ssn, address
 */
@Entity
@Table(name = "MEMBERS", schema = "HCSC_CLAIMS")
public class Member implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "MEMBER_SEQ")
    @SequenceGenerator(name = "MEMBER_SEQ", sequenceName = "HCSC_CLAIMS.MEMBER_SEQ", allocationSize = 1)
    @Column(name = "MEMBER_ID")
    private Long memberId;

    @Column(name = "MEMBER_NUMBER", unique = true, nullable = false, length = 15)
    private String memberNumber;

    // PHI — do NOT log
    @Column(name = "FIRST_NAME", nullable = false, length = 50)
    private String firstName;

    // PHI — do NOT log
    @Column(name = "LAST_NAME", nullable = false, length = 50)
    private String lastName;

    // PHI — do NOT log
    @Column(name = "DATE_OF_BIRTH", nullable = false)
    @Temporal(TemporalType.DATE)
    private Date dateOfBirth;

    // PHI — do NOT log — stored encrypted
    @Column(name = "SSN_ENCRYPTED", length = 256)
    private String ssnEncrypted;

    @Column(name = "PLAN_CODE", nullable = false, length = 10)
    private String planCode;

    @Column(name = "GROUP_NUMBER", length = 15)
    private String groupNumber;

    @Column(name = "EFFECTIVE_DATE", nullable = false)
    @Temporal(TemporalType.DATE)
    private Date effectiveDate;

    @Column(name = "TERMINATION_DATE")
    @Temporal(TemporalType.DATE)
    private Date terminationDate;

    @Column(name = "STATUS", nullable = false, length = 10)
    private String status;  // ACTIVE, TERMED, COBRA, SUSPENDED

    @Column(name = "EMAIL", length = 100)
    private String email;

    @Column(name = "PHONE", length = 20)
    private String phone;

    @Version
    @Column(name = "VERSION")
    private Integer version;

    public Member() {}

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getMemberNumber() { return memberNumber; }
    public void setMemberNumber(String memberNumber) { this.memberNumber = memberNumber; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public Date getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(Date dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getSsnEncrypted() { return ssnEncrypted; }
    public void setSsnEncrypted(String ssnEncrypted) { this.ssnEncrypted = ssnEncrypted; }
    public String getPlanCode() { return planCode; }
    public void setPlanCode(String planCode) { this.planCode = planCode; }
    public String getGroupNumber() { return groupNumber; }
    public void setGroupNumber(String groupNumber) { this.groupNumber = groupNumber; }
    public Date getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(Date effectiveDate) { this.effectiveDate = effectiveDate; }
    public Date getTerminationDate() { return terminationDate; }
    public void setTerminationDate(Date terminationDate) { this.terminationDate = terminationDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
