package dev.mockpay.gateway.service;

import dev.mockpay.gateway.rails.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Outbound email: invitations and password resets.
 *
 * <h2>Sending is asynchronous, and failure never breaks the caller</h2>
 *
 * <p>An SMTP handshake can take seconds and can hang. Doing it inline would mean a password-reset
 * request blocks on a third party, and an outage at the mail provider would turn into 500s on the
 * signup path. So delivery runs on a separate thread and swallows its own failures.
 *
 * <p>The consequence is deliberate: {@code POST /forgot-password} returns 200 whether or not the
 * mail actually goes out. That is already required for a different reason — the endpoint must not
 * reveal whether an address is registered — so the two constraints agree.
 *
 * <h2>No SMTP configured means log, not crash</h2>
 *
 * <p>With no {@code MOCKPAY_SMTP_HOST}, the message is written to the log in full, including the
 * link. Local development and the test suite then work with no mail server at all, and the flow
 * stays observable. This is the same trick a hosted "email sandbox" performs, done in-process.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final GatewayProperties props;
    private final boolean smtpConfigured;

    public EmailService(JavaMailSender mailSender, GatewayProperties props,
                        org.springframework.core.env.Environment environment) {
        this.mailSender = mailSender;
        this.props = props;
        String host = environment.getProperty("spring.mail.host", "");
        this.smtpConfigured = !host.isBlank();

        if (smtpConfigured) {
            log.info("SMTP configured: mail will be sent via {}", host);
        } else {
            log.info("No SMTP host set — emails will be written to the log instead of sent. "
                    + "Set MOCKPAY_SMTP_HOST to deliver for real.");
        }
    }

    @Async("mailExecutor")
    public void sendInvitation(String to, String token, String merchantName, String invitedBy,
                               String role) {
        String link = props.getPublicBaseUrl() + "/accept-invitation?token=" + token;
        send(to,
                invitedBy + " invited you to " + merchantName + " on MockPay",
                """
                %s has invited you to join %s on MockPay as a %s.

                Accept the invitation:
                %s

                This link expires in 7 days and can be used once.

                If you were not expecting this, you can ignore this email — no account
                is created until the link is opened.
                """.formatted(invitedBy, merchantName, role, link));
    }

    @Async("mailExecutor")
    public void sendPasswordReset(String to, String token) {
        String link = props.getPublicBaseUrl() + "/reset-password?token=" + token;
        send(to,
                "Reset your MockPay password",
                """
                Someone asked to reset the password for this MockPay account.

                Set a new password:
                %s

                This link expires in 1 hour and can be used once. Using it will sign you
                out everywhere.

                If it was not you, no action is needed — your password has not changed,
                and the link above is the only way to change it.
                """.formatted(link));
    }

    @Async("mailExecutor")
    public void sendPasswordChanged(String to) {
        send(to,
                "Your MockPay password was changed",
                """
                The password on this MockPay account has just been changed, and all
                active sessions were signed out.

                If this was not you, your account may be compromised. Reset the password
                immediately and review the audit log for activity you do not recognise.
                """);
        // A notification the user cannot act on to *undo* anything, but which is often the first
        // signal a real account takeover has happened. Worth sending even though nobody asked.
    }

    private void send(String to, String subject, String body) {
        if (!smtpConfigured) {
            log.info("""

                    ---------------- EMAIL (not sent - no SMTP configured) ----------------
                    To:      {}
                    Subject: {}

                    {}----------------------------------------------------------------------
                    """, to, subject, body);
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(props.getMail().getFrom());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Sent \"{}\" to {}", subject, to);
        } catch (MailException e) {
            // Never rethrown. A failed notification must not fail the operation that triggered it,
            // and the caller has already returned by the time this runs.
            log.error("Could not send \"{}\" to {} — the user will not have received it",
                    subject, to, e);
        }
    }
}
