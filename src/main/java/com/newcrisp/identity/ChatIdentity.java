package com.newcrisp.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ChatIdentity {
    private final StaffService staff;
    private final NexusLaunchService nexus;
    private final IdentityConfiguration.Settings settings;
    private final JdbcClient jdbc;
    private final WebTokens tokens;

    ChatIdentity(StaffService staff, NexusLaunchService nexus, IdentityConfiguration.Settings settings, JdbcClient jdbc,
                 WebTokens tokens) {
        this.staff = staff;
        this.nexus = nexus;
        this.settings = settings;
        this.jdbc = jdbc;
        this.tokens = tokens;
    }

    public Actor authenticate(HttpServletRequest request, Side side, boolean write) {
        if (write) requireBrowserOrigin(request);
        return actor(tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), side).subject());
    }

    public Actor authenticate(SocketAccess access) {
        var subject = new AccessJwt.Subject(access.side(), access.tenantId(), access.actorId(), access.sid());
        tokens.requireLive(new AccessJwt.Access(subject, access.expiresAt()));
        return actor(subject);
    }

    SocketTicket issueSocket(HttpServletRequest request, Side side) {
        requireBrowserOrigin(request);
        var access = tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), side);
        var actor = actor(access.subject());
        if (!actor.customer() && actor.agentDepartments().isEmpty() && actor.supervisorDepartments().isEmpty()) {
            throw new IdentityError(org.springframework.http.HttpStatus.FORBIDDEN, "CHAT_PERMISSION_REQUIRED");
        }
        return new SocketTicket(tokens.createTicket(access), access.subject().sid());
    }

    public SocketAccess redeemSocket(Side side, String ticket) {
        var result = tokens.redeemTicket(side, ticket);
        var subject = result.subject();
        return new SocketAccess(side, subject.sid(), subject.tenantId(), subject.id(), result.expiresAt());
    }

    private Actor actor(AccessJwt.Subject subject) {
        if (subject.side() == Side.CUSTOMER) {
            var session = nexus.requireSession(subject.sid());
            if (session.tenantId() != subject.tenantId() || !session.profile().customerId().equals(subject.id())) {
                throw IdentityError.unauthorized();
            }
            return new Actor(Side.CUSTOMER, subject.tenantId(), subject.id(), session.departmentId(), Set.of(), Set.of());
        }
        var profile = staff.profile(subject.sid());
        if (profile.tenantId() != subject.tenantId() || !profile.staffId().equals(subject.id())) throw IdentityError.unauthorized();
        var agent = new HashSet<String>();
        var supervisor = new HashSet<String>();
        jdbc.sql("SELECT department_id, role_code FROM staff_department_role WHERE tenant_id = ? AND staff_id = ?")
                .params(subject.tenantId(), subject.id()).query(rs -> {
                    var departments = "AGENT".equals(rs.getString("role_code")) ? agent : supervisor;
                    departments.add(rs.getString("department_id"));
                });
        return new Actor(Side.STAFF, subject.tenantId(), subject.id(), null, Set.copyOf(agent), Set.copyOf(supervisor));
    }

    public String publicOrigin() { return settings.publicOrigin(); }
    public void requireBrowserOrigin(HttpServletRequest request) { IdentityHttp.requireOrigin(request, publicOrigin()); }

    public enum Side { CUSTOMER, STAFF;
        public static Side fromPath(String segment) { return "customer".equals(segment) ? CUSTOMER : STAFF; }
    }

    public record SocketAccess(Side side, String sid, long tenantId, String actorId, Instant expiresAt) {}
    record SocketTicket(String ticket, String sessionId) {
        @Override public String toString() { return "SocketTicket[sessionId=" + sessionId + "]"; }
    }
    public record SessionEnded(Side side, String sid) {}

    public record Actor(Side side, long tenantId, String id, String defaultDepartment,
                        Set<String> agentDepartments, Set<String> supervisorDepartments) {
        public boolean customer() { return side == Side.CUSTOMER; }
    }
}
