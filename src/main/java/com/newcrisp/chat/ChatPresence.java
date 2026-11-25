package com.newcrisp.chat;

import com.newcrisp.identity.ChatIdentity.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ChatPresence {
    private final ConcurrentMap<String, Confirmation> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    ChatPresence(Clock clock, @Value("${newcrisp.chat.presence-ttl:45s}") Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Presence TTL must be positive");
        this.clock = clock;
        this.ttl = ttl;
    }

    void confirm(String connection, Actor actor, String sessionMarker) {
        if (actor.customer()) sessions.put(connection,
                new Confirmation(actor.tenantId(), actor.id(), sessionMarker, clock.instant().plus(ttl)));
    }

    boolean online(long tenantId, String customerId) {
        var now = clock.instant();
        return sessions.values().stream().anyMatch(entry -> entry.tenantId == tenantId
                && entry.customerId.equals(customerId) && now.isBefore(entry.until));
    }

    void forget(String connection) { sessions.remove(connection); }

    void revoke(String sessionMarker) { sessions.values().removeIf(entry -> entry.sessionMarker.equals(sessionMarker)); }

    void forgetExpired() {
        var now = clock.instant();
        sessions.values().removeIf(entry -> !now.isBefore(entry.until));
    }

    private record Confirmation(long tenantId, String customerId, String sessionMarker, Instant until) {}
}
