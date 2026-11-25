package com.newcrisp.identity;

import com.newcrisp.identity.AccessJwt.Access;
import com.newcrisp.identity.AccessJwt.Subject;
import com.newcrisp.identity.ChatIdentity.Side;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class WebTokens {
    private static final Duration TICKET_TTL = Duration.ofSeconds(30);
    static final Duration LAUNCH_TTL = Duration.ofSeconds(60);
    private final TokenStore store;
    private final StaffStore staff;
    private final NexusStore nexus;
    private final NexusSessionVerifier verifier;
    private final IdentityConfiguration.Settings settings;
    private final AccessJwt jwt;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    WebTokens(TokenStore store, StaffStore staff, NexusStore nexus, NexusSessionVerifier verifier,
              IdentityConfiguration.Settings settings, AccessJwt jwt, TransactionTemplate tx,
              ApplicationEventPublisher events, Clock clock) {
        this.store = store;
        this.staff = staff;
        this.nexus = nexus;
        this.verifier = verifier;
        this.settings = settings;
        this.jwt = jwt;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    Issued initial(Subject subject, Instant expiry, boolean bootstrap) {
        Instant refreshExpiry = bootstrap ? clock.instant().plus(LAUNCH_TTL) : expiry;
        String refresh = IdentityTokens.generate();
        store.insertRefresh(subject, IdentityTokens.digest(refresh), refreshExpiry, bootstrap);
        return new Issued(refresh, subject.sid(), refreshExpiry, bootstrap ? null : jwt.sign(subject, expiry));
    }

    Issued rotate(Side side, String raw, boolean bootstrap) {
        byte[] hash = refreshHash(raw);
        var prior = store.refresh(side, hash, false).orElseThrow(IdentityError::unauthorized);
        if (prior.bootstrap() != bootstrap) throw IdentityError.unauthorized();
        if (!prior.used() && !prior.expiresAt().isAfter(clock.instant())) throw IdentityError.unauthorized();

        // Network calls are outside the session lock. Replay revocation also works when Nexus is unavailable.
        Instant checkedAt = clock.instant();
        if (!prior.used()) {
            if (side == Side.CUSTOMER) {
                var session = nexus.session(prior.sid(), TokenStore.utc(checkedAt)).orElseThrow(IdentityError::unauthorized);
                if (!verifier.active(session.userId(), session.sessionId())) {
                    revoke(side, prior.sid());
                    throw IdentityError.unauthorized();
                }
            }
        }
        var result = Objects.requireNonNull(tx.execute(status -> rotateLocked(side, hash, prior.sid(), bootstrap, checkedAt)));
        if (result.replay()) {
            events.publishEvent(new ChatIdentity.SessionEnded(side, prior.sid()));
            throw new IdentityError(HttpStatus.UNAUTHORIZED, "REFRESH_REPLAY");
        }
        return result.issued();
    }

    private Rotation rotateLocked(Side side, byte[] hash, String sid, boolean bootstrap, Instant checkedAt) {
        var session = store.session(side, sid, clock.instant(), true).orElseThrow(IdentityError::unauthorized);
        Instant now = clock.instant();
        var refresh = store.refresh(side, hash, true).orElseThrow(IdentityError::unauthorized);
        if (!sid.equals(refresh.sid()) || refresh.bootstrap() != bootstrap) throw IdentityError.unauthorized();
        if (refresh.used()) {
            store.revoke(side, sid, now);
            // Return through the transaction boundary so the revocation commits.
            return new Rotation(null, true);
        }
        if (side == Side.STAFF) staff.session(sid, TokenStore.utc(now)).orElseThrow(IdentityError::unauthorized);
        else {
            nexus.session(sid, TokenStore.utc(now)).orElseThrow(IdentityError::unauthorized);
            nexus.verified(sid, TokenStore.utc(checkedAt.plus(settings.nexusVerificationTtl())));
        }
        if (!store.consumeRefresh(hash, now)) throw IdentityError.unauthorized();
        return new Rotation(initial(session.subject(), session.expiresAt(), false), false);
    }

    Access access(String authorization, Side side) {
        Access access = jwt.verify(IdentityTokens.bearer(authorization), side);
        return requireLive(access);
    }

    Access requireLive(Access access) {
        if (!clock.instant().isBefore(access.expiresAt())) throw IdentityError.unauthorized();
        Subject subject = access.subject();
        var session = store.session(subject.side(), subject.sid(), clock.instant(), false).orElseThrow(IdentityError::unauthorized);
        if (!subject.equals(session.subject())) throw IdentityError.unauthorized();
        return access;
    }

    String createTicket(Access access) {
        Instant now = clock.instant();
        Instant expires = now.plus(TICKET_TTL).isBefore(access.expiresAt()) ? now.plus(TICKET_TTL) : access.expiresAt();
        if (!expires.isAfter(now)) throw IdentityError.unauthorized();
        String ticket = IdentityTokens.generate();
        store.insertTicket(access.subject(), IdentityTokens.digest(ticket), expires, access.expiresAt());
        return ticket;
    }

    Access redeemTicket(Side side, String raw) {
        byte[] hash = refreshHash(raw);
        var result = store.ticket(side, hash, clock.instant()).orElseThrow(IdentityError::unauthorized);
        var session = store.session(side, result.sid(), clock.instant(), false).orElseThrow(IdentityError::unauthorized);
        if (!clock.instant().isBefore(result.accessExpiresAt()) || !store.consumeTicket(side, hash, clock.instant())) {
            throw IdentityError.unauthorized();
        }
        return new Access(session.subject(), result.accessExpiresAt());
    }

    void logout(Side side, String raw, boolean bootstrap) {
        if (!IdentityTokens.valid(raw)) return;
        store.refresh(side, IdentityTokens.digest(raw), false)
                .filter(refresh -> refresh.bootstrap() == bootstrap)
                .ifPresent(refresh -> revoke(side, refresh.sid()));
    }

    void revoke(Side side, String sid) {
        if (store.revoke(side, sid, clock.instant())) events.publishEvent(new ChatIdentity.SessionEnded(side, sid));
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void prune() { store.prune(clock.instant()); }

    private static byte[] refreshHash(String raw) {
        if (!IdentityTokens.valid(raw)) throw IdentityError.unauthorized();
        return IdentityTokens.digest(raw);
    }

    private record Rotation(Issued issued, boolean replay) {}
    record Issued(String refresh, String sid, Instant refreshExpiresAt, AccessJwt.Token access) {
        @Override public String toString() { return "Issued[refreshExpiresAt=" + refreshExpiresAt + "]"; }
    }
}
