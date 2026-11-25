package com.newcrisp.identity;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;

final class IdentityHttp {
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

    private static String cookie(String name, String token, Duration maxAge, boolean httpOnly) {
        return ResponseCookie.from(name, token).secure(true).httpOnly(httpOnly).path("/")
                .sameSite("Lax").maxAge(maxAge).build().toString();
    }

    private static String cookiePrefix(ChatIdentity.Side side, boolean bootstrap) {
        return "__Host-newcrisp_" + (bootstrap ? "launch" : side == ChatIdentity.Side.STAFF ? "staff" : "customer");
    }

    static String refreshName(ChatIdentity.Side side, boolean bootstrap) { return cookiePrefix(side, bootstrap) + "_rt"; }

    static HttpHeaders refreshHeaders(ChatIdentity.Side side, boolean bootstrap, WebTokens.Issued issued, Instant now) {
        var maxAge = Duration.between(now, issued.refreshExpiresAt());
        if (maxAge.isNegative()) maxAge = Duration.ZERO;
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookie(refreshName(side, bootstrap), issued.refresh(), maxAge, true));
        headers.add(HttpHeaders.SET_COOKIE, cookie(cookiePrefix(side, bootstrap) + "_csrf",
                issued.sid() + "." + csrf(issued.refresh()), maxAge, false));
        return headers;
    }

    static HttpHeaders clearRefresh(ChatIdentity.Side side, boolean bootstrap) {
        var headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, cookie(refreshName(side, bootstrap), "", Duration.ZERO, true));
        headers.add(HttpHeaders.SET_COOKIE, cookie(cookiePrefix(side, bootstrap) + "_csrf", "", Duration.ZERO, false));
        return headers;
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
