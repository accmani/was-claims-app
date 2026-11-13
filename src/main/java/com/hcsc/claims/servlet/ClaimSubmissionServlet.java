package com.hcsc.claims.servlet;

import com.hcsc.claims.ejb.session.ClaimProcessingLocal;
import com.hcsc.claims.entity.Claim;
import com.hcsc.claims.util.PHIAuditLogger;
import org.apache.log4j.Logger;

import javax.ejb.EJB;
import javax.naming.InitialContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

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
@WebServlet(
    name = "ClaimSubmissionServlet",
    urlPatterns = {"/api/claims/submit"},
    loadOnStartup = 1
)
public class ClaimSubmissionServlet extends HttpServlet {

    private static final Logger log = Logger.getLogger(ClaimSubmissionServlet.class);
    private static final long serialVersionUID = 1L;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    @EJB(beanName = "ClaimProcessingBean")
    private ClaimProcessingLocal claimProcessingBean;

    @Override
    public void init() throws ServletException {
        super.init();
        // Fallback JNDI lookup if @EJB injection fails (older WAS behaviour)
        if (claimProcessingBean == null) {
            try {
                InitialContext ctx = new InitialContext();
                claimProcessingBean = (ClaimProcessingLocal)
                    ctx.lookup("ejblocal:ClaimProcessingBean");
            } catch (Exception e) {
                throw new ServletException("Failed to lookup ClaimProcessingBean via JNDI", e);
            }
        }
        log.info("ClaimSubmissionServlet initialised");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();

        // Get authenticated user from WAS session
        // Migration: replace with SecurityContextHolder.getContext().getAuthentication()
        HttpSession session = request.getSession(false);
        if (session == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            out.print("{\"error\":\"Unauthorized — no active session\"}");
            return;
        }

        String submittedBy = (String) session.getAttribute("authenticatedUser");
        if (submittedBy == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            out.print("{\"error\":\"Unauthorized — user not authenticated\"}");
            return;
        }

        try {
            // Parse request parameters — Migration: replace with @RequestBody Claim claim
            Claim claim = parseClaimFromRequest(request);

            // Validate required fields
            String validationError = validateClaimRequest(claim);
            if (validationError != null) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                out.print("{\"error\":\"" + escapeJson(validationError) + "\"}");
                return;
            }

            // Process claim
            Claim result = claimProcessingBean.submitClaim(claim, submittedBy);

            // Return response — Migration: return Claim object, Jackson serialises automatically
            response.setStatus(HttpServletResponse.SC_CREATED);
            out.print(buildClaimResponse(result));

            log.info("Claim submission successful: " + result.getClaimNumber()
                + " user: " + submittedBy);

        } catch (ParseException e) {
            log.warn("Invalid date format in claim submission", e);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            out.print("{\"error\":\"Invalid date format — expected yyyy-MM-dd\"}");
        } catch (Exception e) {
            log.error("Error processing claim submission for user: " + submittedBy, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print("{\"error\":\"Internal server error — please contact support\"}");
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        if (session == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().print("{\"error\":\"Unauthorized\"}");
            return;
        }

        String memberId = request.getParameter("memberId");
        String pageStr  = request.getParameter("page");
        String sizeStr  = request.getParameter("size");

        if (memberId == null || memberId.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().print("{\"error\":\"memberId parameter required\"}");
            return;
        }

        int page = pageStr != null ? Integer.parseInt(pageStr) : 1;
        int size = sizeStr != null ? Integer.parseInt(sizeStr) : 20;

        try {
            java.util.List<Claim> claims =
                claimProcessingBean.getClaimsForMember(memberId, page, size);

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < claims.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(buildClaimResponse(claims.get(i)));
            }
            sb.append("]");

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().print(sb.toString());

        } catch (Exception e) {
            log.error("Error retrieving claims for member: " + memberId, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().print("{\"error\":\"Internal server error\"}");
        }
    }

    private Claim parseClaimFromRequest(HttpServletRequest request) throws ParseException {
        Claim claim = new Claim();
        claim.setMemberId(request.getParameter("memberId"));
        claim.setProviderNpi(request.getParameter("providerNpi"));
        claim.setServiceDate(DATE_FORMAT.parse(request.getParameter("serviceDate")));
        claim.setBilledAmount(new BigDecimal(request.getParameter("billedAmount")));
        claim.setDiagnosisCode(request.getParameter("diagnosisCode"));
        claim.setProcedureCode(request.getParameter("procedureCode"));
        claim.setPlanCode(request.getParameter("planCode"));
        claim.setGroupNumber(request.getParameter("groupNumber"));
        return claim;
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
            "\"claimId\":" + claim.getClaimId() + "," +
            "\"claimNumber\":\"" + escapeJson(claim.getClaimNumber()) + "\"," +
            "\"status\":\"" + escapeJson(claim.getStatus()) + "\"," +
            "\"billedAmount\":" + claim.getBilledAmount() + "," +
            "\"allowedAmount\":" + (claim.getAllowedAmount() != null ? claim.getAllowedAmount() : "null") + "," +
            "\"paidAmount\":" + (claim.getPaidAmount() != null ? claim.getPaidAmount() : "null") + "," +
            "\"denialReason\":" + (claim.getDenialReason() != null
                ? "\"" + escapeJson(claim.getDenialReason()) + "\"" : "null") +
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
