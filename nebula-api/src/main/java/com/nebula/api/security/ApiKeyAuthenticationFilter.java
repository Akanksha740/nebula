package com.nebula.api.security;

import com.nebula.api.service.ApiKeyService;
import com.nebula.common.entity.ApiKey;
import com.nebula.common.entity.Customer;
import com.nebula.common.util.ApiKeyGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        
        String apiKeyValue = extractApiKey(request);
        log.debug("Extracted API key: {}", apiKeyValue != null ? apiKeyValue.substring(0, Math.min(12, apiKeyValue.length())) + "..." : "null");

        if (apiKeyValue != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                String keyHash = ApiKeyGenerator.hash(apiKeyValue);
                Optional<ApiKey> apiKeyOpt = apiKeyService.findByKeyHash(keyHash);

                if (apiKeyOpt.isPresent()) {
                    ApiKey apiKey = apiKeyOpt.get();
                    if (apiKey.isValid()) {
                        Customer customer = apiKey.getCustomer();
                        if (customer.getIsActive()) {
                            ApiKeyAuthenticationToken auth = new ApiKeyAuthenticationToken(customer, apiKey);
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            apiKeyService.updateLastUsed(apiKey.getId());
                            log.debug("API key authentication successful for customer: {}", customer.getId());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("API key authentication failed: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
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
