package com.newcrisp.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TenantManagementStore {
    private static final String MANAGEABLE = """
            a.tenant_id = :tenant AND NOT EXISTS (
                SELECT 1 FROM staff_tenant_role r WHERE r.tenant_id = a.tenant_id AND r.staff_id = a.id
                  AND r.role_code = 'TENANT_ADMIN')
            """;
    private final JdbcClient jdbc;

    TenantManagementStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    List<Department> departments(long tenant) {
        return jdbc.sql("SELECT id, code, name, version FROM department WHERE tenant_id = ? ORDER BY code")
                .param(tenant).query(TenantManagementStore::department).list();
    }

    Optional<Department> lockDepartment(long tenant, String id) {
        return jdbc.sql("SELECT id, code, name, version FROM department WHERE tenant_id = ? AND id = ? FOR UPDATE")
                .params(tenant, id).query(TenantManagementStore::department).optional();
    }

    Department createDepartment(long tenant, String code, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO department (tenant_id, id, code, name) VALUES (?, ?, ?, ?)")
                .params(tenant, id, code, name).update();
        return new Department(id, code, name, "0");
    }

    void rename(long tenant, String id, String name) {
        jdbc.sql("UPDATE department SET name = ?, version = version + 1 WHERE tenant_id = ? AND id = ?")
                .params(name, tenant, id).update();
    }

    List<Member> members(long tenant) {
        return jdbc.sql("""
                SELECT a.id, a.email, a.display_name, a.enabled, a.access_version, d.department_id, d.role_code
                FROM staff_account a LEFT JOIN staff_department_role d ON d.tenant_id = a.tenant_id AND d.staff_id = a.id
                WHERE
                """ + MANAGEABLE + " ORDER BY a.email, d.department_id, d.role_code")
                .param("tenant", tenant).query(rs -> {
                    var people = new LinkedHashMap<String, Member>();
                    while (rs.next()) {
                        String id = rs.getString("id");
                        var person = people.get(id);
                        if (person == null) {
                            person = new Member(id, rs.getString("email"), rs.getString("display_name"), rs.getBoolean("enabled"),
                                    Long.toString(rs.getLong("access_version")), new ArrayList<>());
                            people.put(id, person);
                        }
                        if (rs.getString("department_id") != null) person.roles().add(grant(rs));
                    }
                    return List.copyOf(people.values());
                });
    }

    Optional<MemberState> lockMember(long tenant, String id) {
        return jdbc.sql("SELECT a.enabled, a.access_version FROM staff_account a WHERE " + MANAGEABLE + " AND a.id = :id FOR UPDATE")
                .param("tenant", tenant).param("id", id)
                .query((rs, row) -> new MemberState(rs.getBoolean("enabled"), rs.getLong("access_version"))).optional();
    }

    Set<RoleGrant> roles(long tenant, String staff) {
        return Set.copyOf(jdbc.sql("SELECT department_id, role_code FROM staff_department_role WHERE tenant_id = ? AND staff_id = ?")
                .params(tenant, staff).query((rs, row) -> grant(rs)).list());
    }

    boolean containsDepartments(long tenant, Set<RoleGrant> roles) {
        var ids = roles.stream().map(RoleGrant::departmentId).collect(Collectors.toSet());
        return ids.isEmpty() || jdbc.sql("SELECT COUNT(*) FROM department WHERE tenant_id = :tenant AND id IN (:ids)")
                .param("tenant", tenant).param("ids", ids).query(Long.class).single() == ids.size();
    }

    void replaceRoles(long tenant, String staff, Set<RoleGrant> roles) {
        jdbc.sql("DELETE FROM staff_department_role WHERE tenant_id = ? AND staff_id = ?").params(tenant, staff).update();
        for (RoleGrant role : roles) {
            jdbc.sql("INSERT INTO staff_department_role (tenant_id, staff_id, department_id, role_code) VALUES (?, ?, ?, ?)")
                    .params(tenant, staff, role.departmentId(), role.role().name()).update();
        }
        jdbc.sql("UPDATE staff_account SET access_version = access_version + 1 WHERE tenant_id = ? AND id = ?")
                .params(tenant, staff).update();
    }

    void disable(long tenant, String staff, LocalDateTime now) {
        jdbc.sql("UPDATE staff_account SET enabled = FALSE, access_version = access_version + 1 WHERE tenant_id = ? AND id = ?")
                .params(tenant, staff).update();
        jdbc.sql("UPDATE staff_session SET revoked_at = ? WHERE tenant_id = ? AND staff_id = ? AND revoked_at IS NULL")
                .params(now, tenant, staff).update();
    }

    private static Department department(ResultSet rs, int row) throws SQLException {
        return new Department(rs.getString("id"), rs.getString("code"), rs.getString("name"), Long.toString(rs.getLong("version")));
    }

    private static RoleGrant grant(ResultSet rs) throws SQLException {
        return new RoleGrant(rs.getString("department_id"), Role.valueOf(rs.getString("role_code")));
    }

    enum Role { AGENT, SUPERVISOR }
    record Department(String id, String code, String name, String version) {}
    record RoleGrant(String departmentId, Role role) {}
    record Member(String id, String email, String displayName, boolean enabled, String version, List<RoleGrant> roles) {}
    record MemberState(boolean enabled, long version) {}
}
