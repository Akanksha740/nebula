package com.nebula.ingestion.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
@Slf4j
public class ApiKeyFilter implements Filter {

    private static final String API_KEY_HEADER = "X-API-Key";

    @Value("${nebula.api-key}")
    private String apiKey;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        // Only protect /api/** endpoints
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        String providedKey = httpRequest.getHeader(API_KEY_HEADER);

        if (providedKey == null || !providedKey.equals(apiKey)) {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"success\":false,\"error\":\"Missing or invalid API key\"}");
            log.warn("Unauthorized request to {} - missing/invalid API key", path);
            return;
        }

        chain.doFilter(request, response);
    }
}
