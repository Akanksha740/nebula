package com.nebula.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class CookieService {

    private static final String ACCESS_TOKEN_COOKIE = "access_token";

    @Value("${nebula.jwt.expiration-ms:86400000}")
    private long jwtExpirationMs;

    @Value("${nebula.cookie.secure:true}")
    private boolean secureCookie;

    @Value("${nebula.cookie.domain:}")
    private String cookieDomain;

    public ResponseCookie createAccessTokenCookie(String token) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(ACCESS_TOKEN_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(jwtExpirationMs / 1000);

        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }

        return builder.build();
    }

    public ResponseCookie createLogoutCookie() {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(0);

        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }

        return builder.build();
    }

    public String getCookieName() {
        return ACCESS_TOKEN_COOKIE;
    }
}
