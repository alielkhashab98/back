package com.mtsaas.backend.infrastructure.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Async;

@Service
@Lazy
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final Resend resend;
    private final FallbackEmailService fallbackEmailService;

    @Value("${resend.from.email:support@swiftmxbridge.com}")
    private String senderEmail;

    @Value("${app.support.email:support@swiftmxbridge.com}")
    private String supportEmail;

    @PostConstruct
    public void init() {
        log.info("✓ EmailService initialized using Resend API - Sender Email: {}, Support Email: {}",
                senderEmail, supportEmail);

        // Validate configuration
        if (senderEmail == null || senderEmail.isEmpty()) {
            log.warn("⚠ resend.from.email is not configured (RESEND_FROM_EMAIL in .env)");
        }
        if (supportEmail == null || supportEmail.isEmpty()) {
            log.warn("⚠ SUPPORT_EMAIL is not configured");
        }
        log.info("Email configuration loaded from properties for Resend");
    }

    private void sendEmail(String to, String subject, String htmlContent) {
        log.info("Attempting to send email to: {}, Subject: {}", to, subject);

        if (to == null || to.isEmpty() || to.contains("placeholder")) {
            log.error("❌ Aborting email send: Invalid recipient address: '{}'", to);
            return;
        }

        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(senderEmail)
                .to(to)
                .subject(subject)
                .html(htmlContent)
                .build();

        try {
            CreateEmailResponse response = resend.emails().send(params);
            log.info("✓ Email sent successfully to {}. ID: {}", to, response.getId());
        } catch (ResendException ex) {
            log.error("❌ Resend Error for {}: {}", to, ex.getMessage());
            throw new RuntimeException("Resend API error", ex);
        }
    }

    /**
     * Send feedback email notification with error handling
     */
    @Async
    public void sendFeedbackNotification(String userEmail, String message) {
        log.info("Triggering Feedback notification for user: {}", userEmail);
        try {
            log.debug("Sending feedback email from {} to support ({})", userEmail, supportEmail);

            String htmlContent = String.format(
                    "<html>" +
                            "<body style='font-family: Arial, sans-serif;'>" +
                            "<h2 style='color: #333;'>New Feedback Received</h2>" +
                            "<p><strong>From Customer:</strong> %s</p>" +
                            "<p><strong>Message:</strong></p>" +
                            "<p style='background-color: #f5f5f5; padding: 15px; border-left: 4px solid #007bff;'>%s</p>"
                            +
                            "<p style='color: #666; font-size: 12px; margin-top: 20px;'>" +
                            "This is an automated notification from Swift MX Bridge." +
                            "</p>" +
                            "</body>" +
                            "</html>",
                    userEmail,
                    message.replace("\n", "<br/>"));

            sendEmail(supportEmail, "New Feedback from Swift MX Bridge: " + userEmail, htmlContent);

        } catch (Exception e) {
            log.error("❌ Unexpected error in sendFeedbackNotification", e);
            fallbackEmailService.logFeedbackEmail(userEmail, message);
        }
    }

    /**
     * Send contact us email notification with error handling
     */
    @Async
    public void sendContactUsNotification(String userName, String userEmail, String subject, String message) {
        log.info("Triggering Contact Us notification from user: {} ({})", userName, userEmail);
        try {
            log.debug("Sending contact us email from {} to support ({})", userEmail, supportEmail);

            String htmlContent = String.format(
                    "<html>" +
                            "<body style='font-family: Arial, sans-serif;'>" +
                            "<h2 style='color: #333;'>New Contact Us Message</h2>" +
                            "<p><strong>From:</strong> %s (%s)</p>" +
                            "<p><strong>Subject:</strong> %s</p>" +
                            "<p><strong>Message:</strong></p>" +
                            "<p style='background-color: #f5f5f5; padding: 15px; border-left: 4px solid #007bff;'>%s</p>"
                            +
                            "<hr style='margin: 20px 0; border: none; border-top: 1px solid #ddd;'>" +
                            "<p style='color: #666; font-size: 12px;'>" +
                            "This is an automated notification from Swift MX Bridge. Reply to: %s" +
                            "</p>" +
                            "</body>" +
                            "</html>",
                    userName,
                    userEmail,
                    subject,
                    message.replace("\n", "<br/>"),
                    userEmail);

            sendEmail(supportEmail, "New Contact Us Message: " + subject, htmlContent);

        } catch (Exception e) {
            log.error("❌ Unexpected error sending contact us email", e);
            fallbackEmailService.logContactUsEmail(userName, userEmail, subject, message);
        }
    }

    /**
     * Send credit purchase confirmation email
     */
    @Async
    public void sendCreditPurchaseConfirmation(String userEmail, long creditsAmount, String packageName,
            java.math.BigDecimal amount) {
        log.info("Triggering Purchase Confirmation for user: {}", userEmail);
        try {
            log.debug("Sending purchase confirmation email to customer {} and support notification ({})", userEmail,
                    supportEmail);

            String htmlContent = String.format(
                    "<html>" +
                            "<body style='font-family: Arial, sans-serif;'>" +
                            "<div style='max-width: 600px; margin: 0 auto;'>" +
                            "<h2 style='color: #007bff;'>✓ Credit Purchase Confirmed</h2>" +
                            "<p><strong>Customer Email:</strong> %s</p>" +
                            "<div style='background-color: #f0f8ff; padding: 20px; border-radius: 8px; margin: 20px 0;'>"
                            +
                            "<h3 style='margin: 0 0 15px 0;'>Purchase Summary</h3>" +
                            "<table style='width: 100%%; border-collapse: collapse;'>" +
                            "<tr style='border-bottom: 1px solid #ddd;'>" +
                            "<td style='padding: 10px 0;'><strong>Package:</strong></td>" +
                            "<td style='padding: 10px 0; text-align: right;'>%s</td>" +
                            "</tr>" +
                            "<tr style='border-bottom: 1px solid #ddd;'>" +
                            "<td style='padding: 10px 0;'><strong>Credits:</strong></td>" +
                            "<td style='padding: 10px 0; text-align: right;'>%d conversions</td>" +
                            "</tr>" +
                            "<tr>" +
                            "<td style='padding: 10px 0;'><strong>Amount Paid:</strong></td>" +
                            "<td style='padding: 10px 0; text-align: right; font-size: 16px; color: #28a745;'><strong>$%s</strong></td>"
                            +
                            "</tr>" +
                            "</table>" +
                            "</div>" +
                            "<p><strong>Valid for 30 days from purchase date.</strong></p>" +
                            "<p>Credits have been added to customer account.</p>" +
                            "<hr style='margin: 30px 0; border: none; border-top: 1px solid #ddd;'>" +
                            "<p style='color: #666; font-size: 12px;'>Automated notification from Swift MX Bridge system</p>"
                            +
                            "</div>" +
                            "</body>" +
                            "</html>",
                    userEmail, packageName, creditsAmount, amount);

            sendEmail(supportEmail, "Purchase Confirmation - " + userEmail, htmlContent);

        } catch (Exception e) {
            log.error("❌ Unexpected error sending purchase confirmation email", e);
        }
    }

    /**
     * Send credit purchase notification to admin
     */
    @Async
    public void sendAdminPurchaseNotification(String userEmail, long creditsAmount, String packageName,
            java.math.BigDecimal amount) {
        try {
            log.debug("Sending admin purchase notification to support for user {}", userEmail);

            String htmlContent = String.format(
                    "<html>" +
                            "<body style='font-family: Arial, sans-serif;'>" +
                            "<h2 style='color: #28a745;'>✓ Purchase Successfully Processed</h2>" +
                            "<table style='width: 100%%; border-collapse: collapse; margin: 20px 0;'>" +
                            "<tr style='background-color: #f5f5f5;'>" +
                            "<td style='padding: 10px; border: 1px solid #ddd;'><strong>Customer Email</strong></td>" +
                            "<td style='padding: 10px; border: 1px solid #ddd;'>%s</td>" +
                            "</tr>" +
                            "<tr>" +
                            "<td style='padding: 10px; border: 1px solid #ddd;'><strong>Package</strong></td>" +
                            "<td style='padding: 10px; border: 1px solid #ddd;'>%s</td>" +
                            "</tr>" +
                            "<tr style='background-color: #f5f5f5;'>" +
                            "<td style='padding: 10px; border: 1px solid #ddd;'><strong>Credits Purchased</strong></td>"
                            +
                            "<td style='padding: 10px; border: 1px solid #ddd;'>%d</td>" +
                            "</tr>" +
                            "<tr>" +
                            "<td style='padding: 10px; border: 1px solid #ddd;'><strong>Amount</strong></td>" +
                            "<td style='padding: 10px; border: 1px solid #ddd; color: #28a745;'><strong>$%s</strong></td>"
                            +
                            "</tr>" +
                            "<tr style='background-color: #f5f5f5;'>" +
                            "<td style='padding: 10px; border: 1px solid #ddd;'><strong>Timestamp</strong></td>" +
                            "<td style='padding: 10px; border: 1px solid #ddd;'>%s</td>" +
                            "</tr>" +
                            "</table>" +
                            "<p style='color: #666; font-size: 12px;'>Customer can now use their credits. Credits expire 30 days from purchase.</p>"
                            +
                            "</body>" +
                            "</html>",
                    userEmail, packageName, creditsAmount, amount,
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            sendEmail(supportEmail, "[ALERT] Purchase Completed - " + userEmail, htmlContent);

        } catch (Exception e) {
            log.error("❌ Unexpected error sending admin notification email", e);
        }
    }

    /**
     * Send email verification email
     */
    @Async
    public void sendVerificationEmail(String userEmail, String verificationUrl) {
        try {
            String htmlContent = String.format(
                    "<!DOCTYPE html>" +
                            "<html lang='en'>" +
                            "<head>" +
                            "    <meta charset='UTF-8'>" +
                            "    <meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                            "    <title>Verify Your Account</title>" +
                            "    <style>" +
                            "        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&display=swap');"
                            +
                            "        body { font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.5; color: #1f2937; background-color: #f3f4f6; margin: 0; padding: 0; }"
                            +
                            "        .wrapper { background-color: #f3f4f6; padding: 40px 20px; }" +
                            "        .container { max-width: 560px; margin: 0 auto; background: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06); }"
                            +
                            "        .header { background-color: #2563eb; background: linear-gradient(135deg, #2563eb 0%%, #4f46e5 100%%); padding: 40px 30px; text-align: center; }"
                            +
                            "        .header h1 { color: #ffffff; margin: 0; font-size: 24px; font-weight: 700; letter-spacing: -0.025em; }"
                            +
                            "        .content { padding: 40px 30px; }" +
                            "        .content h2 { color: #111827; margin-top: 0; font-size: 20px; font-weight: 600; }"
                            +
                            "        .content p { color: #4b5563; font-size: 16px; margin-bottom: 24px; }" +
                            "        .perks { background-color: #f9fafb; border-radius: 12px; padding: 20px; margin-bottom: 30px; }"
                            +
                            "        .perks-title { font-weight: 600; color: #374151; font-size: 14px; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 12px; }"
                            +
                            "        .perk-item { display: flex; align-items: center; margin-bottom: 8px; color: #4b5563; font-size: 15px; }"
                            +
                            "        .button-wrapper { text-align: center; margin-top: 30px; }" +
                            "        .button { display: inline-block; background-color: #2563eb; color: #ffffff !important; padding: 14px 32px; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 16px; transition: background-color 0.2s; }"
                            +
                            "        .footer { text-align: center; padding: 30px; font-size: 14px; color: #9ca3af; }" +
                            "        .expiry { font-size: 13px; color: #9ca3af; margin-top: 24px; font-style: italic; }"
                            +
                            "    </style>" +
                            "</head>" +
                            "<body>" +
                            "    <div class='wrapper'>" +
                            "        <div class='container'>" +
                            "            <div class='header'>" +
                            "                <h1>Swift MX Bridge</h1>" +
                            "            </div>" +
                            "            <div class='content'>" +
                            "                <h2>Confirm your email address</h2>" +
                            "                <p>Thanks for joining Swift MX Bridge. We're excited to help you streamline your MT to MX conversion workflow.</p>"
                            +
                            "                <div class='perks'>" +
                            "                    <div class='perks-title'>Unlock your account to get:</div>" +
                            "                    <div class='perk-item'>• 5 Welcome Credits (instantly)</div>" +
                            "                    <div class='perk-item'>• Access to MT103, 202, and 940 converters</div>"
                            +
                            "                    <div class='perk-item'>• ISO 20022 compliant MX generation</div>" +
                            "                </div>" +
                            "                <div class='button-wrapper'>" +
                            "                    <a href='%s' class='button'>Verify My Email</a>" +
                            "                </div>" +
                            "                <p class='expiry'>This link expires in 30 minutes for security reasons. If you didn't create an account, you can safely ignore this email.</p>"
                            +
                            "            </div>" +
                            "            <div class='footer'>" +
                            "                <p>&copy; 2026 Swift MX Bridge. All rights reserved.</p>" +
                            "                <p>Built for professionals by MtSaas Team</p>" +
                            "            </div>" +
                            "        </div>" +
                            "    </div>" +
                            "</body>" +
                            "</html>",
                    verificationUrl);

            sendEmail(userEmail, "Confirm your account - Swift MX Bridge", htmlContent);

        } catch (Exception e) {
            log.error("❌ Unexpected error sending verification email", e);
            fallbackEmailService.logVerificationEmail(userEmail, verificationUrl);
        }
    }

}
