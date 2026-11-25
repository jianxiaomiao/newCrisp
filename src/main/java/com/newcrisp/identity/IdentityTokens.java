package com.newcrisp.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

final class IdentityTokens {
    private static final SecureRandom RANDOM = new SecureRandom();

    private IdentityTokens() {}

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static boolean valid(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }

    static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean matches(String presented, byte[] expected) {
        return valid(presented) && MessageDigest.isEqual(digest(presented), expected);
    }

    static String bearer(String authorization) {
        return authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7) : null;
    }
}
