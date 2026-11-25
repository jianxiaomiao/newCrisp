package com.newcrisp.identity;

import com.newcrisp.chat.CustomerUnread;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
class NexusLaunchService {
    static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final Duration GRANT_TTL = Duration.ofSeconds(60);
    private final NexusStore store;
    private final NexusSessionVerifier verifier;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final IdentityConfiguration.Settings settings;
    private final WebTokens tokens;
    private final CustomerUnread unread;
    private final ObjectMapper json;

    NexusLaunchService(NexusStore store, NexusSessionVerifier verifier, TransactionTemplate transactions, Clock clock,
                       IdentityConfiguration.Settings settings, WebTokens tokens, CustomerUnread unread, ObjectMapper json) {
        this.store = store;
        this.verifier = verifier;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = settings;
        this.tokens = tokens;
        this.unread = unread;
        this.json = json;
    }

    @Transactional
    IntegrationCreated configure(String tenantCode, String origin) {
        try {
            IdentityConfiguration.requireOrigin(origin);
        } catch (IllegalArgumentException invalidOrigin) {
            throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_NEXUS_ORIGIN");
        }
        String integrationId = UUID.randomUUID().toString();
        String keyId = UUID.randomUUID().toString();
        String secret = IdentityTokens.generate();
        try {
            store.createIntegration(tenantCode, origin, integrationId, keyId, IdentityTokens.digest(secret));
        } catch (DuplicateKeyException conflict) {
            throw new IdentityError(HttpStatus.CONFLICT, "INTEGRATION_ALREADY_EXISTS");
        }
        return new IntegrationCreated(integrationId, keyId + "." + secret);
    }

    String issue(String authorization, long userId, long sessionId) {
        return issue(authorization, userId, sessionId, null);
    }

    String issue(String authorization, long userId, long sessionId, CustomerProfileInput profile) {
        var key = requireIntegration(authorization);
        verifier.requireConfigured();
        var snapshot = normalizeProfile(profile);
        String grant = IdentityTokens.generate();
        store.createGrant(IdentityTokens.digest(grant), key, userId, sessionId, now().plus(GRANT_TTL), snapshot);
        return grant;
    }

    private NexusStore.ProfileSnapshot normalizeProfile(CustomerProfileInput input) {
        if (input == null) return null;
        String name = trimToNull(input.displayName(), 80);
        String email = trimToNull(input.email(), 254);
        String phone = trimToNull(input.phone(), 32);
        String avatar = sanitizeUrl(input.avatarUrl(), 512);
        String attrsJson = null;
        if (input.attributes() != null && !input.attributes().isEmpty()) {
            if (input.attributes().size() > 20) {
                throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_CUSTOMER_PROFILE");
            }
            var clean = new LinkedHashMap<String, Object>();
            for (var entry : input.attributes().entrySet()) {
                String k = trimToNull(entry.getKey(), 40);
                if (k == null || entry.getValue() == null) continue;
                Object v = entry.getValue();
                if (v instanceof String s) {
                    String trimmed = trimToNull(s, 120);
                    if (trimmed != null) clean.put(k, trimmed);
                } else if (v instanceof Number || v instanceof Boolean) {
                    clean.put(k, v);
                } else {
                    throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_CUSTOMER_PROFILE");
                }
            }
            if (!clean.isEmpty()) attrsJson = json.writeValueAsString(clean);
        }
        var snapshot = new NexusStore.ProfileSnapshot(name, email, phone, avatar, attrsJson);
        return snapshot.empty() ? null : snapshot;
    }

    void updateContext(String sid, String displayName, String email, String pageUrl, String userAgent) {
        var session = requireSession(sid);
        String name = session.guest() ? trimToNull(displayName, 80) : null;
        String mail = session.guest() ? trimToNull(email, 254) : null;
        String url = sanitizeUrl(pageUrl, 512);
        String ua = trimToNull(userAgent, 255);
        if (name == null && mail == null && url == null && ua == null) return;
        store.updateCustomerContext(session.tenantId(), session.profile().customerId(), session.guest(), name, mail, url, ua, now());
    }

    private static String trimToNull(String value, int maxLength) {
        if (value == null) return null;
        String stripped = value.strip();
        if (stripped.isEmpty()) return null;
        if (stripped.length() > maxLength) stripped = stripped.substring(0, maxLength);
        return stripped;
    }

    private static String sanitizeUrl(String value, int maxLength) {
        String trimmed = trimToNull(value, maxLength);
        if (trimmed == null) return null;
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            return trimmed;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    long unread(String authorization, long userId) {
        var key = requireIntegration(authorization);
        return unread.forNexusUser(key.tenantId(), key.integrationId(), userId);
    }

    private NexusStore.IntegrationKey requireIntegration(String authorization) {
        String credential = IdentityTokens.bearer(authorization);
        if (credential == null || !credential.matches("[a-f0-9-]{36}\\.[A-Za-z0-9_-]{43}")) {
            throw IdentityError.unauthorized();
        }
        var key = store.integrationKey(credential.substring(0, 36)).orElseThrow(IdentityError::unauthorized);
        if (!IdentityTokens.matches(credential.substring(37), key.secretHash())) throw IdentityError.unauthorized();
        return key;
    }

    WebTokens.Issued redeem(String token, String origin) {
        if (!IdentityTokens.valid(token)) throw IdentityError.invalidGrant();
        byte[] hash = IdentityTokens.digest(token);
        var grant = store.availableGrant(hash, now()).orElseThrow(IdentityError::invalidGrant);
        if (!grant.origin().equals(origin)) throw IdentityError.origin();
        LocalDateTime checkedAt = now();
        if (!verifier.active(grant.userId(), grant.sessionId())) throw IdentityError.invalidGrant();

        return transactions.execute(status -> {
            LocalDateTime redeemedAt = now();
            if (!store.consumeGrant(hash, origin, redeemedAt)) throw IdentityError.invalidGrant();
            String sid = UUID.randomUUID().toString();
            var expires = redeemedAt.plus(SESSION_TTL);
            String customer = store.createSession(sid, grant, expires,
                    checkedAt.plus(settings.nexusVerificationTtl()), redeemedAt);
            return tokens.initial(new AccessJwt.Subject(ChatIdentity.Side.CUSTOMER, grant.tenantId(), customer, sid),
                    expires.toInstant(ZoneOffset.UTC), true);
        });
    }

    NexusStore.CustomerProfile profile(String sid) {
        return requireSession(sid).profile();
    }

    NexusStore.CustomerSession requireSession(String sid) {
        LocalDateTime checkedAt = now();
        var session = store.session(sid, checkedAt).orElseThrow(IdentityError::unauthorized);
        if (session.guest() || checkedAt.isBefore(session.verifiedUntil())) return session;
        if (!verifier.active(session.userId(), session.sessionId())) {
            tokens.revoke(ChatIdentity.Side.CUSTOMER, sid);
            throw IdentityError.unauthorized();
        }
        store.verified(sid, checkedAt.plus(settings.nexusVerificationTtl()));
        // A local logout or integration disable during the upstream request invalidates the result.
        return store.session(sid, now()).orElseThrow(IdentityError::unauthorized);
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    record IntegrationCreated(String integrationId, String credential) {}
    record CustomerProfileInput(String displayName, String email, String phone, String avatarUrl, Map<String, Object> attributes) {}
}
