package com.newcrisp.chat;

import java.time.Clock;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import com.newcrisp.identity.ChatIdentity;
import com.newcrisp.identity.IdentityError;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class WorkbenchControls {
    private final JdbcClient jdbc;
    private final ChatStore store;
    private final ChatIdentity identity;
    private final ChatPresence presence;
    private final ServiceHours hours;
    private final Clock clock;

    WorkbenchControls(JdbcClient jdbc, ChatStore store, ChatIdentity identity, ChatPresence presence, ServiceHours hours, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.identity = identity;
        this.presence = presence;
        this.hours = hours;
        this.clock = clock;
    }

    @GetMapping("/api/v1/customer/service-hours")
    ResponseEntity<ServiceHours.Status> serviceHours(HttpServletRequest request) {
        var actor = identity.authenticate(request, ChatIdentity.Side.CUSTOMER, false);
        return ok(hours.status(actor.tenantId(), clock.instant()));
    }

    @GetMapping("/api/v1/staff/availability")
    ResponseEntity<Availability> availability(HttpServletRequest request) {
        var actor = identity.authenticate(request, ChatIdentity.Side.STAFF, false);
        return ok(jdbc.sql("SELECT accepting FROM staff_account WHERE tenant_id = ? AND id = ?")
                .params(actor.tenantId(), actor.id()).query((rs, row) -> new Availability(rs.getBoolean(1))).single());
    }

    @PutMapping("/api/v1/staff/availability")
    @Transactional
    ResponseEntity<Availability> availability(@Valid @RequestBody Availability input, HttpServletRequest request) {
        var actor = identity.authenticate(request, ChatIdentity.Side.STAFF, true);
        if (actor.agentDepartments().isEmpty()) throw ChatModels.forbidden();
        store.lockRouting(actor.tenantId());
        if (!store.lockStaffAccess(actor)) throw ChatIdentity.accessChanged();
        jdbc.sql("UPDATE staff_account SET accepting = ? WHERE tenant_id = ? AND id = ?")
                .params(input.accepting(), actor.tenantId(), actor.id()).update();
        return ok(input);
    }

    @GetMapping("/api/v1/staff/availability/team")
    ResponseEntity<List<Agent>> team(HttpServletRequest request) {
        var actor = identity.authenticate(request, ChatIdentity.Side.STAFF, false);
        if (actor.supervisorDepartments().isEmpty()) throw IdentityError.notFound();
        return ok(jdbc.sql("""
                SELECT a.id, a.display_name, a.email, a.access_version, a.accepting, a.enabled,
                       d.id AS department_id, d.name,
                       (SELECT COUNT(*) FROM conversation c WHERE c.tenant_id = a.tenant_id
                          AND c.assignee_id = a.id AND c.status = 'ACTIVE') AS active
                FROM staff_department_role r
                JOIN staff_account a ON a.tenant_id = r.tenant_id AND a.id = r.staff_id
                JOIN department d ON d.tenant_id = r.tenant_id AND d.id = r.department_id
                WHERE r.tenant_id = :tenant AND r.department_id IN (:departments) AND r.role_code = 'AGENT'
                ORDER BY d.name, a.display_name, a.email
                """).param("tenant", actor.tenantId()).param("departments", actor.supervisorDepartments())
                .query((rs, row) -> new Agent(rs.getString("id"), rs.getString("display_name"), rs.getString("email"),
                        rs.getString("name"), presence.hasOnlineAgent(actor.tenantId(), rs.getString("department_id"),
                          new ChatModels.RoutingCandidate(rs.getString("id"), rs.getLong("access_version"))) && rs.getBoolean("enabled"),
                        rs.getBoolean("accepting") && rs.getBoolean("enabled"), rs.getInt("active"))).list());
    }

    private static <T> ResponseEntity<T> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);
    }

    record Availability(@NotNull Boolean accepting) {}
    record Agent(String id, String name, String email, String department, boolean online, boolean accepting, int active) {}
}
