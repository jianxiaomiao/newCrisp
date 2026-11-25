package com.newcrisp.chat;

import com.newcrisp.identity.ChatIdentity.Actor;
import com.newcrisp.identity.ChatIdentity.SocketAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
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

    void confirm(String connection, Actor actor, SocketAccess access) {
        if (!actor.customer() && actor.agentDepartments().isEmpty()) return;
        var until = clock.instant().plus(ttl);
        if (!actor.customer() && access.expiresAt().isBefore(until)) until = access.expiresAt();
        sessions.put(connection, new Confirmation(actor, access.sid(), until));
    }

    boolean online(long tenantId, String customerId) {
        var now = clock.instant();
        return sessions.values().stream().anyMatch(entry -> entry.actor.customer() && entry.actor.tenantId() == tenantId
                && entry.actor.id().equals(customerId) && now.isBefore(entry.until));
    }

    Set<String> onlineAgentSessions(long tenantId, String department) {
        var now = clock.instant();
        return sessions.values().stream().filter(entry -> entry.agentIn(tenantId, department, now))
                .map(Confirmation::sessionMarker).collect(Collectors.toSet());
    }

    boolean hasOnlineAgent(long tenantId, String department, ChatModels.RoutingCandidate candidate) {
        var now = clock.instant();
        return sessions.values().stream().anyMatch(entry -> entry.agentIn(tenantId, department, now)
                && entry.actor.id().equals(candidate.id()) && entry.actor.accessVersion() == candidate.accessVersion());
    }

    boolean agentOnline(long tenantId, String department, String id) {
        var now = clock.instant();
        return sessions.values().stream().anyMatch(entry -> entry.agentIn(tenantId, department, now)
                && entry.actor.id().equals(id));
    }

    void forget(String connection) { sessions.remove(connection); }

    void revoke(String sessionMarker) { sessions.values().removeIf(entry -> entry.sessionMarker.equals(sessionMarker)); }

    void forgetExpired() {
        var now = clock.instant();
        sessions.values().removeIf(entry -> !now.isBefore(entry.until));
    }

    private record Confirmation(Actor actor, String sessionMarker, Instant until) {
        boolean agentIn(long tenantId, String department, Instant now) {
            return actor.tenantId() == tenantId && actor.agentDepartments().contains(department) && now.isBefore(until);
        }
    }
}
