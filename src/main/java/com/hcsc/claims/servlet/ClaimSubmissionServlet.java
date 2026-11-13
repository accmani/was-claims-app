package com.hcsc.claims.servlet;

import com.hcsc.claims.ejb.session.ClaimProcessingLocal;
import com.hcsc.claims.entity.Claim;
import org.apache.log4j.Logger;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * HTTP Servlet — Claim Submission
 *
 * Receives HTTP POST with claim data, invokes ClaimProcessingBean,
 * returns JSON response.
 *
 * WAS patterns:
 *   - @EJB injection directly into Servlet
 *   - HttpSession for user identity (WAS manages session replication)
 *   - Manual JSON building (pre-Jackson era)
 *   - com.ibm.websphere.security.auth.WSSubject for identity assertion
 *
 * Migration:
 *   - HttpServlet → @RestController
 *   - doPost → @PostMapping
 *   - @EJB → @Autowired service
 *   - Manual JSON → @RequestBody + @ResponseBody + Jackson
 *   - HttpSession identity → Spring Security SecurityContextHolder
 *   - web.xml mapping → @RequestMapping annotation
 */
@RestController
@RequestMapping("/api/claims")
public class ClaimSubmissionServlet {

    private static final Logger log = Logger.getLogger(ClaimSubmissionServlet.class);
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    private final ClaimProcessingLocal claimProcessingBean;

    @Autowired
    public ClaimSubmissionServlet(ClaimProcessingLocal claimProcessingBean) {
        this.claimProcessingBean = claimProcessingBean;
    }

    @PostMapping("/submit")
    @Transactional
    public ResponseEntity<String> doPost(@RequestBody Claim claim, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"Unauthorized — no active session\"}");
        }

        String submittedBy = (String) session.getAttribute("authenticatedUser");
        if (submittedBy == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"Unauthorized — user not authenticated\"}");
        }

        try {
            // Validate required fields
            String validationError = validateClaimRequest(claim);
            if (validationError != null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"error\":\"" + escapeJson(validationError) + "\"}");
            }

            // Process claim
            Claim result = claimProcessingBean.submitClaim(claim, submittedBy);

            // Return response — Migration: return Claim object, Jackson serialises automatically
            return ResponseEntity.status(HttpStatus.CREATED).body(buildClaimResponse(result));

        } catch (ParseException e) {
            log.warn("Invalid date format in claim submission", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"error\":\"Invalid date format — expected yyyy-MM-dd\"}");
        } catch (Exception e) {
            log.error("Error processing claim submission for user: " + submittedBy, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\":\"Internal server error — please contact support\"}");
        }
    }

    @GetMapping("/submit")
    public ResponseEntity<String> doGet(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"Unauthorized\"}");
        }

        String memberId = request.getParameter("memberId");
        String pageStr  = request.getParameter("page");
        String sizeStr  = request.getParameter("size");

        if (memberId == null || memberId.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"error\":\"memberId parameter required\"}");
        }

        int page = pageStr != null ? Integer.parseInt(pageStr) : 1;
        int size = sizeStr != null ? Integer.parseInt(sizeStr) : 20;

        try {
            List<Claim> claims = claimProcessingBean.getClaimsForMember(memberId, page, size);

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < claims.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(buildClaimResponse(claims.get(i)));
            }
            sb.append("]");

            return ResponseEntity.ok(sb.toString());
        } catch (Exception e) {
            log.error("Error retrieving claims for member: " + memberId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\":\"Internal server error\"}");
        }
    }

    private String validateClaimRequest(Claim claim) {
        if (claim.getMemberId() == null || claim.getMemberId().trim().isEmpty())
            return "memberId is required";
        if (claim.getProviderNpi() == null || claim.getProviderNpi().trim().isEmpty())
            return "providerNpi is required";
        if (claim.getServiceDate() == null)
            return "serviceDate is required";
        if (claim.getServiceDate().after(new Date()))
            return "serviceDate cannot be in the future";
        if (claim.getBilledAmount() == null || claim.getBilledAmount().compareTo(BigDecimal.ZERO) <= 0)
            return "billedAmount must be positive";
        if (claim.getProcedureCode() == null || claim.getProcedureCode().trim().isEmpty())
            return "procedureCode is required";
        return null;
    }

    private String buildClaimResponse(Claim claim) {
        // Manual JSON — Migration: return Claim object, Spring/Jackson handles serialisation
        return "{" +
            \"claimId\":\"" + claim.getClaimId() + "\",\" +
            \"claimNumber\":\"" + escapeJson(claim.getClaimNumber()) + "\",\" +
            \"status\":\"" + escapeJson(claim.getStatus()) + "\",\" +
            \"billedAmount\":\"" + claim.getBilledAmount() + "\",\" +
            \"allowedAmount\":\"" + (claim.getAllowedAmount() != null ? claim.getAllowedAmount() : "null") + "\",\" +
            \"paidAmount\":\"" + (claim.getPaidAmount() != null ? claim.getPaidAmount() : "null") + "\",\" +
            \"denialReason\":\"" + (claim.getDenialReason() != null ? escapeJson(claim.getDenialReason()) : "null") + "\" +
            "}";
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }
}
