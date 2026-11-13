package com.hcsc.claims.jms;

import org.apache.log4j.Logger;
import jakarta.jms.*;
import jakarta.naming.InitialContext;
import jakarta.naming.NamingException;
import org.springframework.stereotype.Component;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * Message Driven Bean — Claim Notification Listener
 *
 * Consumes claim processing events from IBM MQ queue.
 * Dispatches to downstream systems: payment processor, member portal, provider portal.
 *
 * WAS deployment:
 *   - Queue: jms/claimsNotificationQueue
 *   - ConnectionFactory: jms/ClaimsConnectionFactory
 *   - IBM MQ broker: mq.hcsc.internal:1414
 *   - Acknowledgement: AUTO_ACKNOWLEDGE
 *
 * Migration:
 *   - @MessageDriven → @JmsListener
 *   - Remove activation config properties
 *   - Replace with @Component + @JmsListener(destination = "${app.jms.claims-notification-queue}")
 *   - IBM MQ Spring Boot starter handles connection factory
 *   - jakarta.jms → jakarta.jms
 */
@Component
public class ClaimNotificationListener {

    private static final Logger log = Logger.getLogger(ClaimNotificationListener.class);

    private static final String PAYMENT_QUEUE_JNDI   = "jms/paymentProcessingQueue";
    private static final String PORTAL_QUEUE_JNDI    = "jms/memberPortalQueue";
    private static final String JMS_FACTORY_JNDI     = "jms/ClaimsConnectionFactory";

    @JmsListener(destination = "${app.jms.claims-notification-queue}")
    @Transactional
    public void onMessage(Message message) {
        if (!(message instanceof TextMessage)) {
            log.warn("Received non-text JMS message — ignoring. Type: " + message.getClass().getName());
            return;
        }

        TextMessage textMessage = (TextMessage) message;

        try {
            String eventType    = message.getStringProperty("eventType");
            String claimNumber  = message.getStringProperty("claimNumber");
            String status       = message.getStringProperty("status");
            String payload      = textMessage.getText();

            log.info("Processing JMS notification: eventType=" + eventType + " claimNumber=" + claimNumber + " status=" + status);

            switch (eventType) {
                case "CLAIM_SUBMITTED":
                case "CLAIM_APPROVED":
                    notifyMemberPortal(claimNumber, status, payload);
                    if ("APPROVED".equals(status)) {
                        triggerPaymentProcessing(claimNumber, payload);
                    }
                    break;

                case "CLAIM_DENIED":
                    notifyMemberPortal(claimNumber, status, payload);
                    sendDenialLetter(claimNumber, payload);
                    break;

                case "LARGE_CLAIM_ALERT":
                    notifyMedicalReview(claimNumber, payload);
                    break;

                case "CLAIM_APPEALED":
                    notifyAppealsDepartment(claimNumber, payload);
                    break;

                default:
                    log.warn("Unknown event type: " + eventType + " for claim: " + claimNumber);
            }
        } catch (JMSException e) {
            log.error("JMS error processing claim notification", e);
            throw new RuntimeException("JMS processing failed — triggering rollback", e);
        } catch (Exception e) {
            log.error("Error processing claim notification", e);
            throw new RuntimeException("Claim notification processing failed", e);
        }
    }

    private void notifyMemberPortal(String claimNumber, String status, String payload) {
        forwardToQueue(PORTAL_QUEUE_JNDI, claimNumber, "MEMBER_PORTAL_UPDATE", payload);
    }

    private void triggerPaymentProcessing(String claimNumber, String payload) {
        forwardToQueue(PAYMENT_QUEUE_JNDI, claimNumber, "PAYMENT_TRIGGER", payload);
    }

    private void sendDenialLetter(String claimNumber, String payload) {
        log.info("Triggering denial letter for claim: " + claimNumber);
        // In real system: call letter generation service
    }

    private void notifyMedicalReview(String claimNumber, String payload) {
        log.info("Notifying medical review for large claim: " + claimNumber);
        // In real system: create medical review task
    }

    private void notifyAppealsDepartment(String claimNumber, String payload) {
        log.info("Notifying appeals department for claim: " + claimNumber);
        // In real system: create appeals task in work queue
    }

    private void forwardToQueue(String queueJndi, String claimNumber,
                                String eventType, String payload) {
        Connection conn = null;
        Session session = null;
        try {
            InitialContext ctx    = new InitialContext();
            ConnectionFactory cf  = (ConnectionFactory) ctx.lookup(JMS_FACTORY_JNDI);
            Queue queue           = (Queue) ctx.lookup(queueJndi);

            conn    = cf.createConnection();
            session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(queue);

            TextMessage outMsg = session.createTextMessage(payload);
            outMsg.setStringProperty("eventType", eventType);
            outMsg.setStringProperty("claimNumber", claimNumber);

            producer.send(outMsg);
            log.info("Forwarded " + eventType + " for claim " + claimNumber + " to queue: " + queueJndi);
        } catch (Exception e) {
            log.error("Failed to forward message to queue: " + queueJndi, e);
            throw new RuntimeException("Queue forwarding failed", e);
        } finally {
            try { if (session != null) session.close(); }   catch (JMSException e) { /* ignore */ }
            try { if (conn != null) conn.close(); }         catch (JMSException e) { /* ignore */ }
        }
    }
}
