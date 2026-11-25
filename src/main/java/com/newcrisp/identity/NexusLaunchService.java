package com.newcrisp.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class NexusLaunchService {
    static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final Duration GRANT_TTL = Duration.ofSeconds(60);
    private final NexusStore store;
    private final NexusSessionVerifier verifier;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final IdentityConfiguration.Settings settings;

    NexusLaunchService(NexusStore store, NexusSessionVerifier verifier, TransactionTemplate transactions, Clock clock,
                       IdentityConfiguration.Settings settings) {
        this.store = store;
        this.verifier = verifier;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = settings;
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
        String credential = IdentityTokens.bearer(authorization);
        if (credential == null || !credential.matches("[a-f0-9-]{36}\\.[A-Za-z0-9_-]{43}")) {
            throw IdentityError.unauthorized();
        }
        var key = store.integrationKey(credential.substring(0, 36)).orElseThrow(IdentityError::unauthorized);
        if (!IdentityTokens.matches(credential.substring(37), key.secretHash())) throw IdentityError.unauthorized();
        verifier.requireConfigured();
        String grant = IdentityTokens.generate();
        store.createGrant(IdentityTokens.digest(grant), key, userId, sessionId, now().plus(GRANT_TTL));
        return grant;
    }

    String redeem(String token, String origin) {
        if (!IdentityTokens.valid(token)) throw IdentityError.invalidGrant();
        byte[] hash = IdentityTokens.digest(token);
        var grant = store.availableGrant(hash, now()).orElseThrow(IdentityError::invalidGrant);
        if (!grant.origin().equals(origin)) throw IdentityError.origin();
        LocalDateTime checkedAt = now();
        if (!verifier.active(grant.userId(), grant.sessionId())) throw IdentityError.invalidGrant();

        return transactions.execute(status -> {
            LocalDateTime redeemedAt = now();
            if (!store.consumeGrant(hash, origin, redeemedAt)) throw IdentityError.invalidGrant();
            String session = IdentityTokens.generate();
            store.createSession(IdentityTokens.digest(session), grant, redeemedAt.plus(SESSION_TTL),
                    checkedAt.plus(settings.nexusVerificationTtl()));
            return session;
        });
    }

    NexusStore.CustomerProfile profile(String token) {
        return requireSession(token).profile();
    }

    NexusStore.CustomerSession requireSession(String token) {
        if (!IdentityTokens.valid(token)) throw IdentityError.unauthorized();
        byte[] hash = IdentityTokens.digest(token);
        LocalDateTime checkedAt = now();
        var session = store.session(hash, checkedAt).orElseThrow(IdentityError::unauthorized);
        if (checkedAt.isBefore(session.verifiedUntil())) return session;
        if (!verifier.active(session.userId(), session.sessionId())) {
            store.deleteSession(hash);
            throw IdentityError.unauthorized();
        }
        store.verified(hash, checkedAt.plus(settings.nexusVerificationTtl()));
        // A local logout or integration disable during the upstream request invalidates the result.
        return store.session(hash, now()).orElseThrow(IdentityError::unauthorized);
    }

    void logout(String token) {
        if (IdentityTokens.valid(token)) store.deleteSession(IdentityTokens.digest(token));
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }

    record IntegrationCreated(String integrationId, String credential) {}
}
