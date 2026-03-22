package com.nebula.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.api.service.ApiKeyService;
import com.nebula.common.dto.response.ApiResponse;
import com.nebula.common.entity.ApiKey;
import com.nebula.common.entity.Customer;
import com.nebula.common.exception.UnauthorizedException;
import com.nebula.common.util.ApiKeyGenerator;
import io.micrometer.common.util.StringUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKeyValue = extractApiKey(request);
        if(StringUtils.isEmpty(apiKeyValue)) {
            filterChain.doFilter(request, response);
            return;
        }
        log.debug("Extracted API key: {}", apiKeyValue.substring(0, Math.min(12, apiKeyValue.length())) + "...");

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                String keyHash = ApiKeyGenerator.hash(apiKeyValue);
                Optional<ApiKey> apiKeyOpt = apiKeyService.findByKeyHash(keyHash);

                if(!apiKeyOpt.isPresent()) {
                    String message = "Invalid API key. Please verify your key or generate a new one from your dashboard.";
                    writeErrorResponse(response, 401, message, "API_KEY_INVALID");
                    return;
                }
                else {
                    ApiKey apiKey = apiKeyOpt.get();
                    if (!apiKey.isValid()) {
                        String message = apiKey.isExpired()
                                ? "Your API key has expired. Please generate a new one from your dashboard."
                                : "Your API key has been revoked. Please generate a new one from your dashboard.";
                        writeErrorResponse(response, 401, message, "API_KEY_INVALID");
                        return;
                    }
                    Customer customer = apiKey.getCustomer();
                    if (!customer.getIsActive()) {
                        writeErrorResponse(response, 403, "Your account has been deactivated.", "ACCOUNT_DEACTIVATED");
                        return;
                    }
                    ApiKeyAuthenticationToken auth = new ApiKeyAuthenticationToken(customer, apiKey);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    apiKeyService.updateLastUsed(apiKey.getId());
                    log.debug("API key authentication successful for customer: {}", customer.getId());
                }
            } catch (Exception e) {
                log.debug("API key authentication failed: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private void writeErrorResponse(HttpServletResponse response, int status, String message, String errorCode) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(message, errorCode));
    }

    private String extractApiKey(HttpServletRequest request) {
        String apiKeyHeader = request.getHeader(API_KEY_HEADER);
        if (apiKeyHeader != null && !apiKeyHeader.isBlank()) {
            return apiKeyHeader;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            if (token.startsWith("nb_")) {
                return token;
            }
        }

        return null;
    }
}
