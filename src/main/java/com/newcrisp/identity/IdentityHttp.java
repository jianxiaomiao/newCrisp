package com.newcrisp.identity;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Collections;
import java.util.HexFormat;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;

final class IdentityHttp {
    static final String STAFF = "__Host-newcrisp_staff";
    static final String CUSTOMER = "__Host-newcrisp_customer";
    static final String CSRF = "X-NewCrisp-CSRF";

    private IdentityHttp() {}

    static String origin(HttpServletRequest request) {
        var origins = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (origins.size() != 1) throw IdentityError.origin();
        return origins.getFirst();
    }

    static void requireOrigin(HttpServletRequest request, String expected) {
        if (!expected.equals(origin(request))) throw IdentityError.origin();
    }

    static String token(HttpServletRequest request, String name) {
        String value = null;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (!name.equals(cookie.getName())) continue;
                if (value != null) throw IdentityError.unauthorized();
                value = cookie.getValue();
            }
        }
        return value;
    }

    static String cookie(String name, String token, Duration maxAge) {
        return ResponseCookie.from(name, token).secure(true).httpOnly(true).path("/")
                .sameSite("Lax").maxAge(maxAge).build().toString();
    }

    static String csrf(String cookieToken) {
        return HexFormat.of().formatHex(IdentityTokens.digest("newcrisp-csrf:" + cookieToken));
    }

    static void requireCsrf(HttpServletRequest request, String cookieToken) {
        if (!IdentityTokens.valid(cookieToken)) return;
        var values = Collections.list(request.getHeaders(CSRF));
        if (values.size() != 1 || !values.getFirst().matches("[a-f0-9]{64}")
                || !MessageDigest.isEqual(HexFormat.of().parseHex(values.getFirst()),
                        IdentityTokens.digest("newcrisp-csrf:" + cookieToken))) {
            throw new IdentityError(HttpStatus.FORBIDDEN, "CSRF_REJECTED");
        }
    }
}
