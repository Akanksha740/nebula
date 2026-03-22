package com.nebula.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    @Value("${resend.api.key:}")
    private String resendApiKey;

    @Value("${resend.from-email:noreply@yourdomain.com}")
    private String fromEmail;

    @Value("${nebula.app.base-url:http://localhost:3000}")
    private String appBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @Async
    public void sendVerificationEmail(String toEmail, String token) {
        if (resendApiKey == null || resendApiKey.isBlank()) {
            log.warn("Resend API key not configured — skipping verification email to {}", toEmail);
            return;
        }

        String verificationLink = appBaseUrl + "/verify-email?token=" + token;

        String htmlContent = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #1a1a2e;">Welcome to PolyHistorical</h2>
                    <p>Thank you for registering. Please verify your email address by clicking the button below:</p>
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="%s"
                           style="background-color: #6c63ff; color: white; padding: 12px 30px;
                                  text-decoration: none; border-radius: 5px; font-size: 16px;">
                            Verify Email
                        </a>
                    </div>
                    <p style="color: #666;">Or copy and paste this link into your browser:</p>
                    <p style="color: #6c63ff; word-break: break-all;">%s</p>
                    <p style="color: #999; font-size: 12px;">This link expires in 24 hours. If you didn't create an account, you can safely ignore this email.</p>
                </div>
                """.formatted(verificationLink, verificationLink);

        log.info("Sending verification email to {} from {} via Resend", toEmail, fromEmail);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(resendApiKey);

            Map<String, Object> body = Map.of(
                    "from", fromEmail,
                    "to", new String[]{toEmail},
                    "subject", "Verify your PolyHistorical account",
                    "html", htmlContent
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(RESEND_API_URL, request, String.class);

            log.info("Resend API response for {}: status={}, body={}", toEmail, response.getStatusCode(), response.getBody());
        } catch (Exception e) {
            log.error("Error sending verification email to {}: {}", toEmail, e.getMessage(), e);
        }
    }

    @Async
    public void sendPasswordResetEmail(String toEmail, String token) {
        if (resendApiKey == null || resendApiKey.isBlank()) {
            log.warn("Resend API key not configured — skipping password reset email to {}", toEmail);
            return;
        }

        String resetLink = appBaseUrl + "/reset-password?token=" + token;

        String htmlContent = """
                <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #1a1a2e;">Reset your password</h2>
                    <p>We received a request to reset your PolyHistorical account password. Click the button below to set a new password:</p>
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="%s"
                           style="background-color: #6c63ff; color: white; padding: 12px 30px;
                                  text-decoration: none; border-radius: 5px; font-size: 16px;">
                            Reset Password
                        </a>
                    </div>
                    <p style="color: #666;">Or copy and paste this link into your browser:</p>
                    <p style="color: #6c63ff; word-break: break-all;">%s</p>
                    <p style="color: #999; font-size: 12px;">This link expires in 1 hour. If you didn't request a password reset, you can safely ignore this email.</p>
                </div>
                """.formatted(resetLink, resetLink);

        log.info("Sending password reset email to {} from {} via Resend", toEmail, fromEmail);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(resendApiKey);

            Map<String, Object> body = Map.of(
                    "from", fromEmail,
                    "to", new String[]{toEmail},
                    "subject", "Reset your PolyHistorical password",
                    "html", htmlContent
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(RESEND_API_URL, request, String.class);

            log.info("Resend API response for {}: status={}, body={}", toEmail, response.getStatusCode(), response.getBody());
        } catch (Exception e) {
            log.error("Error sending password reset email to {}: {}", toEmail, e.getMessage(), e);
        }
    }
}
