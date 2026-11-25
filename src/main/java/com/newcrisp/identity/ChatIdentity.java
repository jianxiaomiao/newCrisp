package com.newcrisp.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ChatIdentity {
    private final NexusLaunchService nexus;
    private final IdentityConfiguration.Settings settings;
    private final JdbcClient jdbc;
    private final WebTokens tokens;
    private final Clock clock;

    ChatIdentity(NexusLaunchService nexus, IdentityConfiguration.Settings settings, JdbcClient jdbc, WebTokens tokens, Clock clock) {
        this.nexus = nexus;
        this.settings = settings;
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.clock = clock;
    }

    public Actor authenticate(HttpServletRequest request, Side side, boolean write) {
        if (write) requireBrowserOrigin(request);
        return browserActor(request, tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), side).subject());
    }

    public Actor authenticate(SocketAccess access) {
        var subject = new AccessJwt.Subject(access.side(), access.tenantId(), access.actorId(), access.sid());
        tokens.requireLive(new AccessJwt.Access(subject, access.expiresAt()));
        var actor = actor(subject);
        if (!actor.customer() && (access.staffAccessVersion() == null || access.staffAccessVersion() != actor.accessVersion())) {
            throw accessChanged();
        }
        return actor;
    }

    SocketTicket issueSocket(HttpServletRequest request, Side side) {
        requireBrowserOrigin(request);
        var access = tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), side);
        var actor = browserActor(request, access.subject());
        if (!actor.customer() && actor.agentDepartments().isEmpty() && actor.supervisorDepartments().isEmpty()) {
            throw new IdentityError(HttpStatus.FORBIDDEN, "CHAT_PERMISSION_REQUIRED");
        }
        return new SocketTicket(tokens.createTicket(access, actor.customer() ? null : actor.accessVersion()), access.subject().sid());
    }

    public SocketAccess redeemSocket(Side side, String ticket) {
        var result = tokens.redeemTicket(side, ticket);
        var subject = result.access().subject();
        return new SocketAccess(side, subject.sid(), subject.tenantId(), subject.id(), result.access().expiresAt(), result.staffAccessVersion());
    }

    private Actor browserActor(HttpServletRequest request, AccessJwt.Subject subject) {
        var actor = actor(subject);
        String expected = request.getHeader("X-NewCrisp-Actor");
        if (expected != null && !expected.equals(actor.id())) throw IdentityError.unauthorized();
        String version = request.getHeader("X-NewCrisp-Staff-Access");
        if (!actor.customer() && version != null && !Long.toString(actor.accessVersion()).equals(version)) {
            throw accessChanged();
        }
        return actor;
    }

    private Actor actor(AccessJwt.Subject subject) {
        if (subject.side() == Side.CUSTOMER) {
            var session = nexus.requireSession(subject.sid());
            if (session.tenantId() != subject.tenantId() || !session.profile().customerId().equals(subject.id())) {
                throw IdentityError.unauthorized();
            }
            return new Actor(Side.CUSTOMER, subject.tenantId(), subject.id(), session.departmentId(), Set.of(), Set.of(), 0);
        }
        // A single statement snapshots the role rows and their version together.
        var grants = jdbc.sql("""
                SELECT a.access_version, d.department_id, d.role_code
                FROM staff_session s JOIN staff_account a ON a.tenant_id = s.tenant_id AND a.id = s.staff_id
                JOIN tenant t ON t.id = a.tenant_id
                LEFT JOIN staff_department_role d ON d.tenant_id = a.tenant_id AND d.staff_id = a.id
                WHERE s.sid = ? AND a.tenant_id = ? AND a.id = ? AND s.revoked_at IS NULL AND s.expires_at > ?
                  AND a.enabled AND t.status = 'ACTIVE'
                """).params(subject.sid(), subject.tenantId(), subject.id(), TokenStore.utc(clock.instant()))
                .query((rs, row) -> new Grant(rs.getLong("access_version"), rs.getString("department_id"), rs.getString("role_code"))).list();
        if (grants.isEmpty()) throw IdentityError.unauthorized();
        var agent = new HashSet<String>();
        var supervisor = new HashSet<String>();
        for (Grant grant : grants) {
            if ("AGENT".equals(grant.role())) agent.add(grant.department());
            if ("SUPERVISOR".equals(grant.role())) supervisor.add(grant.department());
        }
        return new Actor(Side.STAFF, subject.tenantId(), subject.id(), null, Set.copyOf(agent), Set.copyOf(supervisor), grants.getFirst().version());
    }

    public static IdentityError accessChanged() { return new IdentityError(HttpStatus.CONFLICT, "STAFF_ACCESS_CHANGED"); }

    public String publicOrigin() { return settings.publicOrigin(); }
    public void requireBrowserOrigin(HttpServletRequest request) { IdentityHttp.requireOrigin(request, publicOrigin()); }

    public enum Side { CUSTOMER, STAFF;
        public static Side fromPath(String segment) { return "customer".equals(segment) ? CUSTOMER : STAFF; }
    }

    private record Grant(long version, String department, String role) {}
    public record SocketAccess(Side side, String sid, long tenantId, String actorId, Instant expiresAt, Long staffAccessVersion) {}
    record SocketTicket(String ticket, String sessionId) {
        @Override public String toString() { return "SocketTicket[sessionId=" + sessionId + "]"; }
    }
    public record SessionEnded(Side side, String sid) {}
    public record StaffAccessChanged(long tenantId, String staffId, long accessVersion, boolean disabled) {}

    public record Actor(Side side, long tenantId, String id, String defaultDepartment,
                        Set<String> agentDepartments, Set<String> supervisorDepartments, long accessVersion) {
        public boolean customer() { return side == Side.CUSTOMER; }
    }
}
