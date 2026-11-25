package com.newcrisp.identity;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StaffService {
    static final Duration SESSION_TTL = Duration.ofHours(8);
    private final StaffStore store;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final WebTokens tokens;
    private final String dummyHash;

    StaffService(StaffStore store, PasswordEncoder encoder, Clock clock, WebTokens tokens) {
        this.store = store;
        this.encoder = encoder;
        this.clock = clock;
        this.tokens = tokens;
        this.dummyHash = encoder.encode(IdentityTokens.generate());
    }

    @Transactional
    void provision(String code, String name, String email, String password) {
        try {
            store.createTenant(code, name.strip(), normalizeEmail(email), encodeNewPassword(password), LocalDateTime.now(clock));
        } catch (DuplicateKeyException conflict) {
            throw new IdentityError(HttpStatus.CONFLICT, "TENANT_ALREADY_EXISTS");
        }
    }

    @Transactional
    String createMember(String sid, String departmentCode, String email, String password, String role, String displayName) {
        var profile = profile(sid);
        if (!"TENANT_ADMIN".equals(profile.role())) throw new IdentityError(HttpStatus.FORBIDDEN, "TENANT_ADMIN_REQUIRED");
        try {
            String name = displayName == null || displayName.isBlank() ? "客服" : displayName.strip();
            return store.createMember(profile.tenantId(), departmentCode, normalizeEmail(email), encodeNewPassword(password), role, name);
        } catch (DuplicateKeyException conflict) {
            throw new IdentityError(HttpStatus.CONFLICT, "STAFF_ALREADY_EXISTS");
        }
    }

    private String encodeNewPassword(String password) {
        int length = password.getBytes(StandardCharsets.UTF_8).length;
        if (length < 12 || length > 72 || password.isBlank()) {
            throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_PASSWORD");
        }
        return encoder.encode(password);
    }

    @Transactional
    WebTokens.Issued login(String code, String email, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw IdentityError.unauthorized();
        var account = store.loginAccount(code, normalizeEmail(email));
        String hash = account.map(StaffStore.LoginAccount::passwordHash).orElse(dummyHash);
        if (!encoder.matches(password, hash) || account.isEmpty()) throw IdentityError.unauthorized();
        var principal = account.orElseThrow();
        String sid = UUID.randomUUID().toString();
        var expiry = clock.instant().plus(SESSION_TTL);
        if (!store.createSession(sid, principal, TokenStore.utc(expiry))) {
            throw IdentityError.unauthorized();
        }
        return tokens.initial(new AccessJwt.Subject(ChatIdentity.Side.STAFF, principal.tenantId(), principal.id(), sid), expiry, false);
    }

    StaffStore.StaffProfile profile(String sid) {
        return store.session(sid, LocalDateTime.now(clock)).orElseThrow(IdentityError::unauthorized);
    }

    private static String normalizeEmail(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
