package com.hcsc.claims.util;

import org.apache.log4j.Logger;
import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Date;

/**
 * PHI Audit Logger — HIPAA Compliance
 *
 * Every access to Protected Health Information must be logged
 * to the immutable audit table. 7-year retention requirement.
 *
 * Uses direct JNDI DataSource — audit log must be in a separate
 * transaction from the business operation (audit always commits
 * even if business operation rolls back).
 *
 * Migration:
 *   - JNDI DataSource → @Autowired JdbcTemplate (secondary datasource)
 *   - Use @Async for non-blocking audit writes
 *   - Consider Elasticsearch for audit log (better for SIEM integration)
 */
public class PHIAuditLogger {

    private static final Logger log = Logger.getLogger(PHIAuditLogger.class);
    private static final String AUDIT_DATASOURCE_JNDI = "jdbc/hcscAuditDS";

    /**
     * Log a PHI access event.
     *
     * @param eventType    What happened (CLAIM_SUBMITTED, MEMBER_VIEWED, etc.)
     * @param resourceId   The claim number or member number accessed (NOT PHI itself)
     * @param userId       Who accessed it
     * @param description  Human readable description
     */
    public static void log(String eventType, String resourceId, String userId, String description) {
        // Write to audit log asynchronously — do NOT block business operation
        // In WAS: use @Asynchronous EJB or WorkManager
        // Migration: @Async Spring method
        writeAuditLog(eventType, resourceId, userId, description);
    }

    private static void writeAuditLog(String eventType, String resourceId,
                                       String userId, String description) {
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            InitialContext ctx = new InitialContext();
            DataSource ds = (DataSource) ctx.lookup(AUDIT_DATASOURCE_JNDI);

            // Use a new connection outside current transaction
            // so audit log commits even if business TX rolls back
            conn = ds.getConnection();
            conn.setAutoCommit(true);

            String sql =
                "INSERT INTO HCSC_AUDIT.PHI_ACCESS_LOG " +
                "(EVENT_TYPE, RESOURCE_ID, USER_ID, DESCRIPTION, EVENT_TIMESTAMP, APPLICATION) " +
                "VALUES (?, ?, ?, ?, ?, ?)";

            ps = conn.prepareStatement(sql);
            ps.setString(1, eventType);
            ps.setString(2, resourceId);
            ps.setString(3, userId);
            ps.setString(4, description);
            ps.setTimestamp(5, new java.sql.Timestamp(new Date().getTime()));
            ps.setString(6, "CLAIMS-MANAGEMENT");
            ps.executeUpdate();

        } catch (Exception e) {
            // Audit failure must NOT fail the business operation
            // but must be logged to system log for investigation
            log.error("CRITICAL: PHI audit log write failed for event: "
                + eventType + " resource: " + resourceId
                + " user: " + userId + " — HIPAA compliance at risk", e);
        } finally {
            try { if (ps != null) ps.close(); }   catch (Exception e) { /* ignore */ }
            try { if (conn != null) conn.close(); } catch (Exception e) { /* ignore */ }
        }
    }
}
