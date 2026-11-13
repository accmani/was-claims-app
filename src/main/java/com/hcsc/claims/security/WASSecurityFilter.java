package com.hcsc.claims.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.List;

/**
 * WAS JAAS Security Filter
 *
 * Integrates with WebSphere Application Server's built-in JAAS security framework.
 * Validates that requests are authenticated by WAS and extracts the caller identity.
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
@Component
public class WASSecurityFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(WASSecurityFilter.class);

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

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("No authenticated user — unauthenticated request to: " + httpRequest.getRequestURI());
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.getWriter().write("{\"error\":\"Authentication required\"}");
            return;
        }

        String userId = authentication.getName();
        List<String> roles = authentication.getAuthorities().stream()
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .toList();

        log.info("Authenticated request: user=" + userId + " uri=" + httpRequest.getRequestURI());

        // Store in session for downstream use
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
    }

    @Override
    public void destroy() {
        log.info("WASSecurityFilter destroyed");
    }

    private boolean hasRole(List<String> roles, String roleName) {
        if (roles == null) return false;
        return roles.contains(roleName);
    }
}
