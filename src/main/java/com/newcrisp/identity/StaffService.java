package com.newcrisp.identity;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
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
    private final String dummyHash;

    StaffService(StaffStore store, PasswordEncoder encoder, Clock clock) {
        this.store = store;
        this.encoder = encoder;
        this.clock = clock;
        this.dummyHash = encoder.encode(IdentityTokens.generate());
    }

    @Transactional
    void provision(String code, String name, String email, String password) {
        int length = password.getBytes(StandardCharsets.UTF_8).length;
        if (length < 12 || length > 72 || password.isBlank()) {
            throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_ADMIN_PASSWORD");
        }
        try {
            store.createTenant(code, name.strip(), normalizeEmail(email), encoder.encode(password), LocalDateTime.now(clock));
        } catch (DuplicateKeyException conflict) {
            throw new IdentityError(HttpStatus.CONFLICT, "TENANT_ALREADY_EXISTS");
        }
    }

    String login(String code, String email, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw IdentityError.unauthorized();
        var account = store.loginAccount(code, normalizeEmail(email));
        String hash = account.map(StaffStore.LoginAccount::passwordHash).orElse(dummyHash);
        if (!encoder.matches(password, hash) || account.isEmpty()) throw IdentityError.unauthorized();
        String token = IdentityTokens.generate();
        if (!store.createSession(IdentityTokens.digest(token), account.get(), LocalDateTime.now(clock).plus(SESSION_TTL))) {
            throw IdentityError.unauthorized();
        }
        return token;
    }

    StaffStore.StaffProfile profile(String token) {
        if (!IdentityTokens.valid(token)) throw IdentityError.unauthorized();
        return store.session(IdentityTokens.digest(token), LocalDateTime.now(clock)).orElseThrow(IdentityError::unauthorized);
    }

    void logout(String token) {
        if (IdentityTokens.valid(token)) store.deleteSession(IdentityTokens.digest(token));
    }

    private static String normalizeEmail(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
