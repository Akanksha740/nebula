package com.nebula.api.controller;

import com.nebula.api.service.AuthService;
import com.nebula.common.dto.CustomerDto;
import com.nebula.common.dto.request.GoogleAuthRequest;
import com.nebula.common.dto.request.LoginRequest;
import com.nebula.common.dto.request.RegisterRequest;
import com.nebula.common.dto.response.ApiResponse;
import com.nebula.common.dto.response.AuthResponse;
import com.nebula.common.entity.Customer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "User authentication endpoints")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Register a new account")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(ApiResponse.success(response, "Registration successful. Please check your email to verify your account."));
    }

    @PostMapping("/login")
    @Operation(summary = "Login to existing account")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/verify-email")
    @Operation(summary = "Verify email address using token")
    public ResponseEntity<ApiResponse<Void>> verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok(ApiResponse.success(null, "Email verified successfully. You can now log in."));
    }

    @PostMapping("/resend-verification")
    @Operation(summary = "Resend email verification link")
    public ResponseEntity<ApiResponse<Void>> resendVerification(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        authService.resendVerificationEmail(email);
        return ResponseEntity.ok(ApiResponse.success(null, "Verification email sent. Please check your inbox."));
    }

    @PostMapping("/google")
    @Operation(summary = "Authenticate with Google")
    public ResponseEntity<ApiResponse<AuthResponse>> googleAuth(
            @Valid @RequestBody GoogleAuthRequest request) {
        AuthResponse response = authService.googleAuth(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user details")
    public ResponseEntity<ApiResponse<CustomerDto>> getCurrentUser(
            @AuthenticationPrincipal Customer customer) {
        CustomerDto dto = authService.getCurrentCustomer(customer);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }
}
