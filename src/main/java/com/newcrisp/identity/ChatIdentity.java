package com.newcrisp.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ChatIdentity {
    private final StaffService staff;
    private final NexusLaunchService nexus;
    private final IdentityConfiguration.Settings settings;
    private final JdbcClient jdbc;

    ChatIdentity(StaffService staff, NexusLaunchService nexus, IdentityConfiguration.Settings settings, JdbcClient jdbc) {
        this.staff = staff;
        this.nexus = nexus;
        this.settings = settings;
        this.jdbc = jdbc;
    }

    public Credential credential(HttpServletRequest request, Side side, boolean write, boolean handshake) {
        if (write || handshake) IdentityHttp.requireOrigin(request, settings.publicOrigin());
        String token = IdentityHttp.token(request, side == Side.CUSTOMER ? IdentityHttp.CUSTOMER : IdentityHttp.STAFF);
        if (write) IdentityHttp.requireCsrf(request, token);
        return new Credential(side, token);
    }

    public Actor authenticate(Credential credential) {
        if (credential.side() == Side.CUSTOMER) {
            var session = nexus.requireSession(credential.token());
            return new Actor(Side.CUSTOMER, session.tenantId(), session.profile().customerId(), session.departmentId(),
                    Set.of(), Set.of());
        }
        var profile = staff.profile(credential.token());
        var agent = new HashSet<String>();
        var supervisor = new HashSet<String>();
        jdbc.sql("SELECT department_id, role_code FROM staff_department_role WHERE tenant_id = ? AND staff_id = ?")
                .params(profile.tenantId(), profile.staffId()).query(rs -> {
                    var departments = "AGENT".equals(rs.getString("role_code")) ? agent : supervisor;
                    departments.add(rs.getString("department_id"));
                });
        return new Actor(Side.STAFF, profile.tenantId(), profile.staffId(), null, Set.copyOf(agent), Set.copyOf(supervisor));
    }

    public String publicOrigin() { return settings.publicOrigin(); }

    public enum Side { CUSTOMER, STAFF }

    public record Credential(Side side, String token) {
        @Override public String toString() { return "Credential[side=" + side + "]"; }
        public String sessionMarker() { return token == null ? "" : HexFormat.of().formatHex(IdentityTokens.digest(token)); }
    }

    public record SessionEnded(Credential credential) {}

    public record Actor(Side side, long tenantId, String id, String defaultDepartment,
                        Set<String> agentDepartments, Set<String> supervisorDepartments) {
        public boolean customer() { return side == Side.CUSTOMER; }
    }
}
