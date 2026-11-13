package com.hcsc.claims.util;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

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
@Component
public class PHIAuditLogger {

    private static final Logger log = LoggerFactory.getLogger(PHIAuditLogger.class);
    private final DataSource dataSource;

    @Autowired
    public PHIAuditLogger(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Log a PHI access event.
     *
     * @param eventType    What happened (CLAIM_SUBMITTED, MEMBER_VIEWED, etc.)
     * @param resourceId   The claim number or member number accessed (NOT PHI itself)
     * @param userId       Who accessed it
     * @param description  Human readable description
     */
    @Async
    public void log(String eventType, String resourceId, String userId, String description) {
        writeAuditLog(eventType, resourceId, userId, description);
    }

    private void writeAuditLog(String eventType, String resourceId,
                                String userId, String description) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO HCSC_AUDIT.PHI_ACCESS_LOG " +
                     "(EVENT_TYPE, RESOURCE_ID, USER_ID, DESCRIPTION, EVENT_TIMESTAMP, APPLICATION) " +
                     "VALUES (?, ?, ?, ?, ?, ?)")) {
            conn.setAutoCommit(true);

            ps.setString(1, eventType);
            ps.setString(2, resourceId);
            ps.setString(3, userId);
            ps.setString(4, description);
            ps.setTimestamp(5, java.sql.Timestamp.from(Instant.now()));
            ps.setString(6, "CLAIMS-MANAGEMENT");
            ps.executeUpdate();

        } catch (SQLException e) {
            log.error("CRITICAL: PHI audit log write failed for event: "
                    + eventType + " resource: " + resourceId
                    + " user: " + userId + " — HIPAA compliance at risk", e);
        }
    }
}
