package com.nebula.api.service;

import com.nebula.api.repository.CustomerRepository;
import com.nebula.api.security.JwtTokenProvider;
import com.nebula.common.dto.CustomerDto;
import com.nebula.common.dto.request.LoginRequest;
import com.nebula.common.dto.request.RegisterRequest;
import com.nebula.common.dto.response.AuthResponse;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.UnauthorizedException;
import com.nebula.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (customerRepository.existsByEmail(request.getEmail())) {
            throw new ValidationException("Email already registered");
        }

        Customer customer = Customer.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .companyName(request.getCompanyName())
                .tier(Customer.SubscriptionTier.FREE)
                .isActive(true)
                .emailVerified(false)
                .build();

        customer = customerRepository.save(customer);
        log.info("New customer registered: {}", customer.getEmail());

        String token = jwtTokenProvider.generateToken(customer);
        return AuthResponse.of(token, jwtTokenProvider.getExpirationMs(), toDto(customer));
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

        String token = jwtTokenProvider.generateToken(customer);
        log.info("Customer logged in: {}", customer.getEmail());

        return AuthResponse.of(token, jwtTokenProvider.getExpirationMs(), toDto(customer));
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
