package com.nebula.api.config;

import com.nebula.common.dto.response.ApiResponse;
import com.nebula.common.exception.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimitException(RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
        headers.set("X-RateLimit-Remaining", "0");
        return ResponseEntity
                .status(ex.getHttpStatus())
                .headers(headers)
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(TierAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleTierAccessException(TierAccessException ex) {
        log.warn("Tier access denied: {} - required: {}, current: {}", 
                ex.getMessage(), ex.getRequiredTier(), ex.getCurrentTier());
        Map<String, String> details = new HashMap<>();
        details.put("required_tier", ex.getRequiredTier());
        if (ex.getCurrentTier() != null) {
            details.put("current_tier", ex.getCurrentTier());
        }
        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode(), details));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedException(UnauthorizedException ex) {
        log.warn("Unauthorized access: {}", ex.getMessage());
        HttpHeaders headers = new HttpHeaders();
        headers.set("WWW-Authenticate", "ApiKey realm=\"nebula-api\"");
        return ResponseEntity
                .status(ex.getHttpStatus())
                .headers(headers)
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(NebulaException.class)
    public ResponseEntity<ApiResponse<Void>> handleNebulaException(NebulaException ex) {
        log.warn("Nebula exception: {} - {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Validation failed", "VALIDATION_ERROR"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getParameterName());
        String message = "Required query parameter '" + ex.getParameterName() + "' is missing.";
        if ("coin".equals(ex.getParameterName())) {
            message += " Please specify a coin (BTC, ETH, or SOL), e.g. ?coin=btc.";
        }
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message, "BAD_REQUEST"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Parameter type mismatch: {} = {}", ex.getName(), ex.getValue());
        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "valid value";
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        "Invalid value for parameter '" + ex.getName() + "': expected " + expected + ".",
                        "BAD_REQUEST"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex) {
        log.warn("Request error: {}", ex.getReason());
        return ResponseEntity
                .status(ex.getStatusCode())
                .body(ApiResponse.error(ex.getReason(), "BAD_REQUEST"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("Resource not found: {}", ex.getResourcePath());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Resource not found", "NOT_FOUND"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred", "INTERNAL_ERROR"));
    }
}
