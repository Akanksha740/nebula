package com.nebula.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.api.security.ApiKeyAuthenticationToken;
import com.nebula.api.service.ApiAccessService;
import com.nebula.api.service.UsageTrackingService;
import com.nebula.common.config.TierConfig.TierLimits;
import com.nebula.common.dto.response.ApiResponse;
import com.nebula.common.entity.ApiKey;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.NebulaException;
import com.nebula.common.exception.RateLimitExceededException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ApiAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessFilter.class);

    private final ApiAccessService apiAccessService;
    private final UsageTrackingService usageTrackingService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String endpoint = request.getRequestURI();
        String method = request.getMethod();
        Instant startTime = Instant.now();

        AuthContext authContext = getAuthContext();

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        try {
            apiAccessService.checkAccess(authContext.customer(), authContext.apiKey(), endpoint, method);
            filterChain.doFilter(request, responseWrapper);
            if (authContext.customer() != null) {
                addRateLimitHeaders(responseWrapper, authContext.customer());
            }
            recordUsage(authContext, request, responseWrapper, startTime);
            responseWrapper.copyBodyToResponse();
        } catch (RateLimitExceededException e) {
            recordUsage(authContext, request, e.getHttpStatus(), startTime);
            handleRateLimitException(response, e, authContext.customer());
        } catch (NebulaException e) {
            recordUsage(authContext, request, e.getHttpStatus(), startTime);
            handleNebulaException(response, e);
        }
    }

    private void recordUsage(AuthContext authContext, HttpServletRequest request,
                            ContentCachingResponseWrapper response, Instant startTime) {
        if (authContext.customer() == null) {
            return;
        }

        if (shouldSkipUsageTracking(request.getRequestURI())) {
            return;
        }

        int responseTimeMs = (int) java.time.Duration.between(startTime, Instant.now()).toMillis();
        long requestBytes = request.getContentLengthLong() > 0 ? request.getContentLengthLong() : 0;
        java.util.UUID apiKeyId = authContext.apiKey() != null ? authContext.apiKey().getId() : null;

        usageTrackingService.trackRequest(
                authContext.customer().getId(),
                apiKeyId,
                request.getRequestURI(),
                request.getMethod(),
                response.getStatus(),
                responseTimeMs,
                requestBytes,
                response.getContentSize(),
                getClientIpAddress(request),
                request.getHeader("User-Agent")
        );
    }

    private void recordUsage(AuthContext authContext, HttpServletRequest request,
                            int statusCode, Instant startTime) {
        if (authContext.customer() == null) {
            return;
        }

        if (shouldSkipUsageTracking(request.getRequestURI())) {
            return;
        }

        int responseTimeMs = (int) java.time.Duration.between(startTime, Instant.now()).toMillis();
        long requestBytes = request.getContentLengthLong() > 0 ? request.getContentLengthLong() : 0;
        java.util.UUID apiKeyId = authContext.apiKey() != null ? authContext.apiKey().getId() : null;

        usageTrackingService.trackRequest(
                authContext.customer().getId(),
                apiKeyId,
                request.getRequestURI(),
                request.getMethod(),
                statusCode,
                responseTimeMs,
                requestBytes,
                0,
                getClientIpAddress(request),
                request.getHeader("User-Agent")
        );
    }

    private boolean shouldSkipUsageTracking(String endpoint) {
        return endpoint.startsWith("/v1/account") 
            || endpoint.startsWith("/v1/auth")
            || endpoint.startsWith("/health");
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    private record AuthContext(Customer customer, ApiKey apiKey) {}

    private AuthContext getAuthContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth instanceof ApiKeyAuthenticationToken apiKeyAuth) {
            return new AuthContext(apiKeyAuth.getCustomer(), apiKeyAuth.getApiKey());
        }
        
        if (auth != null && auth.getPrincipal() instanceof Customer customer) {
            return new AuthContext(customer, null);
        }
        
        return new AuthContext(null, null);
    }

    private void addRateLimitHeaders(HttpServletResponse response, Customer customer) {
        try {
            TierLimits limits = apiAccessService.getLimits(customer);
            long remaining = apiAccessService.getRemainingRequests(customer);

            response.setHeader("X-RateLimit-Limit", String.valueOf(limits.dailyRequestLimit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
            response.setHeader("X-RateLimit-Reset", getResetTime());
            response.setHeader("X-Tier", customer.getTier().name());
        } catch (Exception e) {
            log.debug("Failed to add rate limit headers: {}", e.getMessage());
        }
    }

    private String getResetTime() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
        java.time.LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return String.valueOf(midnight.toEpochSecond(java.time.ZoneOffset.UTC));
    }


    private void handleRateLimitException(HttpServletResponse response, RateLimitExceededException e,
                                          Customer customer) throws IOException {
        response.setStatus(e.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        addRateLimitHeaders(response, customer);
        ApiResponse<Void> apiResponse = ApiResponse.error(e.getMessage(), e.getErrorCode());
        objectMapper.writeValue(response.getOutputStream(), apiResponse);
    }

    private void handleNebulaException(HttpServletResponse response, NebulaException e) 
            throws IOException {
        response.setStatus(e.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        
        ApiResponse<Void> apiResponse = ApiResponse.error(e.getMessage(), e.getErrorCode());
        objectMapper.writeValue(response.getOutputStream(), apiResponse);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") 
            || path.equals("/favicon.ico");
    }
}
