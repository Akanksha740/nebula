package com.nebula.api.controller;

import com.nebula.api.service.AuthService;
import com.nebula.api.service.CookieService;
import com.nebula.common.dto.CustomerDto;
import com.nebula.common.dto.request.GoogleAuthRequest;
import com.nebula.common.dto.request.LoginRequest;
import com.nebula.common.dto.request.RegisterRequest;
import com.nebula.common.dto.response.ApiResponse;
import com.nebula.common.dto.response.AuthResponse;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.UnauthorizedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
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
    private final CookieService cookieService;

    @PostMapping("/register")
    @Operation(summary = "Register a new account")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (response.getAccessToken() != null) {
            ResponseCookie cookie = cookieService.createAccessTokenCookie(response.getAccessToken());
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return builder.body(ApiResponse.success(response, "Registration successful. Please check your email to verify your account."));
    }

    @PostMapping("/login")
    @Operation(summary = "Login to existing account")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        ResponseCookie cookie = cookieService.createAccessTokenCookie(response.getAccessToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.success(response));
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

    @PostMapping("/forgot-password")
    @Operation(summary = "Request password reset email")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        authService.forgotPassword(email);
        return ResponseEntity.ok(ApiResponse.success(null, "If an account exists with this email, a reset link has been sent."));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset password using token")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@RequestBody Map<String, String> request) {
        String token = request.get("token");
        String newPassword = request.get("newPassword");
        authService.resetPassword(token, newPassword);
        return ResponseEntity.ok(ApiResponse.success(null, "Password reset successfully. You can now log in."));
    }

    @PostMapping("/google")
    @Operation(summary = "Authenticate with Google")
    public ResponseEntity<ApiResponse<AuthResponse>> googleAuth(
            @Valid @RequestBody GoogleAuthRequest request) {
        AuthResponse response = authService.googleAuth(request);
        ResponseCookie cookie = cookieService.createAccessTokenCookie(response.getAccessToken());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.success(response));
    }

    @GetMapping("/me")
    @Operation(summary = "Get current user details")
    public ResponseEntity<ApiResponse<CustomerDto>> getCurrentUser(
            @AuthenticationPrincipal Customer customer) {
        if (customer == null) {
            throw new UnauthorizedException("Authentication required");
        }
        CustomerDto dto = authService.getCurrentCustomer(customer);
        return ResponseEntity.ok(ApiResponse.success(dto));
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout and clear authentication cookie")
    public ResponseEntity<ApiResponse<Void>> logout() {
        ResponseCookie cookie = cookieService.createLogoutCookie();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.success(null, "Logged out successfully"));
    }
}
