package com.nebula.api.service;

import com.nebula.api.repository.CustomerRepository;
import com.nebula.api.security.JwtTokenProvider;
import com.nebula.common.dto.CustomerDto;
import com.nebula.common.dto.request.GoogleAuthRequest;
import com.nebula.common.dto.request.LoginRequest;
import com.nebula.common.dto.request.RegisterRequest;
import com.nebula.common.dto.response.AuthResponse;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.UnauthorizedException;
import com.nebula.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.nebula.common.util.EmailValidator;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final EmailService emailService;

    @Value("${google.client-id:}")
    private String googleClientId;

    @Value("${nebula.email-verification.token-expiry-hours:24}")
    private int tokenExpiryHours;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        Customer existing = customerRepository.findByEmail(request.getEmail()).orElse(null);

        if (existing != null) {
            if (existing.getEmailVerified()) {
                throw new ValidationException("Email already registered");
            }

            // Unverified account — update password, refresh token, resend verification
            String verificationToken = UUID.randomUUID().toString();
            existing.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            existing.setCompanyName(request.getCompanyName());
            existing.setEmailVerificationToken(verificationToken);
            existing.setEmailVerificationTokenExpiry(Instant.now().plus(tokenExpiryHours, ChronoUnit.HOURS));
            customerRepository.save(existing);

            emailService.sendVerificationEmail(existing.getEmail(), verificationToken);
            log.info("Resent verification email for unverified account: {}", existing.getEmail());

            return AuthResponse.of(null, 0, toDto(existing));
        }

        String verificationToken = UUID.randomUUID().toString();

        Customer customer = Customer.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .companyName(request.getCompanyName())
                .tier(Customer.SubscriptionTier.STARTER)
                .isActive(true)
                .emailVerified(false)
                .emailVerificationToken(verificationToken)
                .emailVerificationTokenExpiry(Instant.now().plus(tokenExpiryHours, ChronoUnit.HOURS))
                .build();

        customer = customerRepository.save(customer);
        log.info("New customer registered: {}", customer.getEmail());

        emailService.sendVerificationEmail(customer.getEmail(), verificationToken);

        return AuthResponse.of(null, 0, toDto(customer));
    }

    public AuthResponse login(LoginRequest request) {
        Customer customer = customerRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), customer.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        if (!customer.getIsActive()) {
            throw new UnauthorizedException("Account is disabled");
        }

        if (!customer.getEmailVerified()) {
            throw new UnauthorizedException("Email not verified. Please check your inbox for the verification link.");
        }

        String token = jwtTokenProvider.generateToken(customer);
        log.info("Customer logged in: {}", customer.getEmail());

        return AuthResponse.of(token, jwtTokenProvider.getExpirationMs(), toDto(customer));
    }

    @Transactional
    public void verifyEmail(String token) {
        Customer customer = customerRepository.findByEmailVerificationToken(token)
                .orElseThrow(() -> new ValidationException("Invalid verification token"));

        if (customer.getEmailVerified()) {
            return; // already verified
        }

        if (customer.getEmailVerificationTokenExpiry() != null
                && Instant.now().isAfter(customer.getEmailVerificationTokenExpiry())) {
            throw new ValidationException("Verification token has expired. Please request a new one.");
        }

        customer.setEmailVerified(true);
        customerRepository.save(customer);

        log.info("Email verified for customer: {}", customer.getEmail());
    }

    @Transactional
    public void resendVerificationEmail(String email) {
        EmailValidator.validate(email);
        Customer customer = customerRepository.findByEmail(email)
                .orElseThrow(() -> new ValidationException("No account found with this email"));

        if (customer.getEmailVerified()) {
            throw new ValidationException("Email is already verified");
        }

        String verificationToken = UUID.randomUUID().toString();
        customer.setEmailVerificationToken(verificationToken);
        customer.setEmailVerificationTokenExpiry(Instant.now().plus(tokenExpiryHours, ChronoUnit.HOURS));
        customerRepository.save(customer);

        emailService.sendVerificationEmail(customer.getEmail(), verificationToken);
        log.info("Resent verification email to: {}", customer.getEmail());
    }

    @Transactional
    public AuthResponse googleAuth(GoogleAuthRequest request) {
        Map<String, Object> payload = verifyGoogleToken(request.getCredential());

        String email = (String) payload.get("email");
        String name = (String) payload.get("name");

        if (email == null || email.isBlank()) {
            throw new ValidationException("Could not retrieve email from Google");
        }

        EmailValidator.validate(email);

        // Find existing or create new customer
        Customer customer = customerRepository.findByEmail(email).orElse(null);

        if (customer == null) {
            customer = Customer.builder()
                    .email(email)
                    .passwordHash(passwordEncoder.encode(UUID.randomUUID().toString()))
                    .companyName(name)
                    .tier(Customer.SubscriptionTier.STARTER)
                    .isActive(true)
                    .emailVerified(true)
                    .build();
            customer = customerRepository.save(customer);
            log.info("New customer registered via Google: {}", email);
        } else {
            if (!customer.getIsActive()) {
                throw new UnauthorizedException("Account is disabled");
            }
            log.info("Existing customer logged in via Google: {}", email);
        }

        String token = jwtTokenProvider.generateToken(customer);
        return AuthResponse.of(token, jwtTokenProvider.getExpirationMs(), toDto(customer));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verifyGoogleToken(String credential) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            String url = "https://oauth2.googleapis.com/tokeninfo?id_token=" + credential;
            Map<String, Object> payload = restTemplate.getForObject(url, Map.class);

            if (payload == null) {
                throw new UnauthorizedException("Invalid Google token");
            }

            String aud = (String) payload.get("aud");
            if (!googleClientId.equals(aud)) {
                throw new UnauthorizedException("Google token audience mismatch");
            }

            return payload;
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Google token verification failed", e);
            throw new UnauthorizedException("Invalid Google token");
        }
    }

    @Transactional
    public void changePassword(Customer customer, String currentPassword, String newPassword) {
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new ValidationException("Current password is required");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new ValidationException("New password must be at least 8 characters");
        }
        if (!passwordEncoder.matches(currentPassword, customer.getPasswordHash())) {
            throw new UnauthorizedException("Current password is incorrect");
        }
        customer.setPasswordHash(passwordEncoder.encode(newPassword));
        customerRepository.save(customer);
        log.info("Password changed for customer: {}", customer.getEmail());
    }

    @Transactional
    public void forgotPassword(String email) {
        EmailValidator.validate(email);
        Customer customer = customerRepository.findByEmail(email).orElse(null);

        // Always return success to prevent email enumeration
        if (customer == null || !customer.getIsActive()) {
            return;
        }

        String resetToken = UUID.randomUUID().toString();
        customer.setPasswordResetToken(resetToken);
        customer.setPasswordResetTokenExpiry(Instant.now().plus(1, ChronoUnit.HOURS));
        customerRepository.save(customer);

        emailService.sendPasswordResetEmail(customer.getEmail(), resetToken);
        log.info("Password reset requested for: {}", customer.getEmail());
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ValidationException("Password must be at least 8 characters");
        }

        Customer customer = customerRepository.findByPasswordResetToken(token)
                .orElseThrow(() -> new ValidationException("Invalid or expired reset link"));

        if (customer.getPasswordResetTokenExpiry() != null
                && Instant.now().isAfter(customer.getPasswordResetTokenExpiry())) {
            throw new ValidationException("Reset link has expired. Please request a new one.");
        }

        customer.setPasswordHash(passwordEncoder.encode(newPassword));
        customer.setPasswordResetToken(null);
        customer.setPasswordResetTokenExpiry(null);
        customerRepository.save(customer);

        log.info("Password reset completed for: {}", customer.getEmail());
    }

    public CustomerDto getCurrentCustomer(Customer customer) {
        return toDto(customer);
    }

    private CustomerDto toDto(Customer customer) {
        return CustomerDto.builder()
                .id(customer.getId())
                .email(customer.getEmail())
                .companyName(customer.getCompanyName())
                .tier(customer.getTier())
                .isActive(customer.getIsActive())
                .emailVerified(customer.getEmailVerified())
                .createdAt(customer.getCreatedAt())
                .build();
    }
}
