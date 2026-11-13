package com.hcsc.claims.security;

import com.ibm.websphere.security.auth.WSSubject;
import com.ibm.websphere.security.cred.WSCredential;
import org.apache.log4j.Logger;

import javax.security.auth.Subject;
import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.security.PrivilegedAction;
import java.util.Set;

/**
 * WAS JAAS Security Filter
 *
 * Integrates with WebSphere Application Server's built-in JAAS security framework.
 * Validates that requests are authenticated by WAS and extracts the caller identity.
 *
 * WAS-specific:
 *   - com.ibm.websphere.security.auth.WSSubject — IBM proprietary, NO Spring equivalent
 *   - com.ibm.websphere.security.cred.WSCredential — IBM proprietary
 *   - WAS role mapping via ibm-application-bnd.xml
 *
 * Migration note:
 *   THIS CLASS REQUIRES ARCHITECTURAL DECISION (Checkpoint D).
 *   WSSubject and WSCredential have NO Spring Security equivalent.
 *   Must be replaced with:
 *     Option A: Spring Security + Azure AD OAuth2 (recommended for HCSC)
 *     Option B: Spring Security + LDAP (if WAS LDAP realm is preserved)
 *     Option C: Spring Security + SAML 2.0 (if WAS federation is used)
 *
 *   WAS roles (claims-adjuster, claims-supervisor, claims-readonly, medical-review)
 *   must be mapped to Spring Security authorities/roles.
 */
@WebFilter(
    filterName = "WASSecurityFilter",
    urlPatterns = {"/api/*"},
    dispatcherTypes = {DispatcherType.REQUEST, DispatcherType.FORWARD}
)
public class WASSecurityFilter implements Filter {

    private static final Logger log = Logger.getLogger(WASSecurityFilter.class);

    // WAS security roles defined in ibm-application-bnd.xml
    public static final String ROLE_CLAIMS_ADJUSTER   = "claims-adjuster";
    public static final String ROLE_CLAIMS_SUPERVISOR = "claims-supervisor";
    public static final String ROLE_CLAIMS_READONLY   = "claims-readonly";
    public static final String ROLE_MEDICAL_REVIEW    = "medical-review";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("WASSecurityFilter initialised — WAS JAAS security active");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest   = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        try {
            // Get the WAS caller subject — IBM proprietary API
            // Migration: SecurityContextHolder.getContext().getAuthentication()
            Subject callerSubject = WSSubject.getCallerSubject();

            if (callerSubject == null) {
                log.warn("No WAS caller subject — unauthenticated request to: "
                    + httpRequest.getRequestURI());
                httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                httpResponse.getWriter().write("{\"error\":\"Authentication required\"}");
                return;
            }

            // Extract WSCredential from Subject — IBM proprietary
            // Migration: use Authentication.getPrincipal() / OAuth2User / UserDetails
            Set<WSCredential> credentials = callerSubject.getPublicCredentials(WSCredential.class);
            if (credentials.isEmpty()) {
                log.warn("WAS subject has no WSCredential — invalid security state");
                httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                httpResponse.getWriter().write("{\"error\":\"Invalid security credentials\"}");
                return;
            }

            WSCredential credential = credentials.iterator().next();
            String userId = credential.getSecurityName();
            java.util.List roles = credential.getRoles();

            log.info("Authenticated request: user=" + userId
                + " uri=" + httpRequest.getRequestURI());

            // Store in session for downstream use
            // Migration: Spring Security handles this via SecurityContext
            HttpSession session = httpRequest.getSession(true);
            session.setAttribute("authenticatedUser", userId);
            session.setAttribute("userRoles", roles);

            // Role-based access control — specific endpoints
            String uri = httpRequest.getRequestURI();
            if (uri.contains("/admin/") && !hasRole(roles, ROLE_CLAIMS_SUPERVISOR)) {
                log.warn("Access denied to admin endpoint for user: " + userId);
                httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                httpResponse.getWriter().write("{\"error\":\"Insufficient privileges\"}");
                return;
            }

            if (uri.contains("/medical-review/") && !hasRole(roles, ROLE_MEDICAL_REVIEW)) {
                log.warn("Access denied to medical review endpoint for user: " + userId);
                httpResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                httpResponse.getWriter().write("{\"error\":\"Insufficient privileges\"}");
                return;
            }

            chain.doFilter(request, response);

        } catch (com.ibm.websphere.security.WSSecurityException e) {
            log.error("WAS security exception — cannot determine caller identity", e);
            httpResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            httpResponse.getWriter().write("{\"error\":\"Security framework error\"}");
        }
    }

    @Override
    public void destroy() {
        log.info("WASSecurityFilter destroyed");
    }

    private boolean hasRole(java.util.List roles, String roleName) {
        if (roles == null) return false;
        return roles.contains(roleName);
    }
}
