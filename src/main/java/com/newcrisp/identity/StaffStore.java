package com.newcrisp.identity;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class StaffStore {
    private final JdbcClient jdbc;

    StaffStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void createTenant(String code, String name, String email, String passwordHash, LocalDateTime now) {
        jdbc.sql("INSERT INTO tenant (code, name, created_at, updated_at) VALUES (?, ?, ?, ?)")
                .params(code, name, now, now).update();
        long tenantId = jdbc.sql("SELECT id FROM tenant WHERE code = ?").param(code).query(Long.class).single();
        String staffId = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO staff_account (tenant_id, id, email, password_hash) VALUES (?, ?, ?, ?)")
                .params(tenantId, staffId, email, passwordHash).update();
        jdbc.sql("INSERT INTO staff_tenant_role (tenant_id, staff_id, role_code) VALUES (?, ?, 'TENANT_ADMIN')")
                .params(tenantId, staffId).update();
        jdbc.sql("INSERT INTO department (tenant_id, id, code, name) VALUES (?, ?, 'support', '客户支持')")
                .params(tenantId, UUID.randomUUID().toString()).update();
    }

    Optional<LoginAccount> loginAccount(String tenantCode, String email) {
        return jdbc.sql("""
                SELECT a.tenant_id, a.id, a.password_hash
                FROM staff_account a JOIN tenant t ON t.id = a.tenant_id
                WHERE t.code = ? AND a.email = ? AND a.enabled AND t.status = 'ACTIVE'
                """).params(tenantCode, email).query((rs, row) -> new LoginAccount(
                rs.getLong("tenant_id"), rs.getString("id"), rs.getString("password_hash"))).optional();
    }

    boolean createSession(String sid, LoginAccount account, LocalDateTime expiresAt) {
        return jdbc.sql("""
                INSERT INTO staff_session (token_hash, sid, tenant_id, staff_id, expires_at)
                SELECT ?, ?, a.tenant_id, a.id, ? FROM staff_account a
                JOIN tenant t ON t.id = a.tenant_id
                WHERE a.tenant_id = ? AND a.id = ? AND a.password_hash = ? AND a.enabled AND t.status = 'ACTIVE'
                """).params(IdentityTokens.digest("session:" + sid), sid, expiresAt,
                account.tenantId(), account.id(), account.passwordHash()).update() == 1;
    }

    Optional<StaffProfile> session(String sid, LocalDateTime now) {
        return jdbc.sql("""
                SELECT t.code, t.name, a.email, a.id, a.tenant_id,
                    IF(r.role_code IS NULL, 'STAFF', 'TENANT_ADMIN') AS role_code,
                    EXISTS(SELECT 1 FROM staff_department_role d WHERE d.tenant_id = a.tenant_id
                           AND d.staff_id = a.id AND d.role_code = 'AGENT') AS can_queue,
                    EXISTS(SELECT 1 FROM staff_department_role d WHERE d.tenant_id = a.tenant_id
                           AND d.staff_id = a.id AND d.role_code = 'SUPERVISOR') AS can_supervise
                FROM staff_session s JOIN staff_account a ON a.tenant_id = s.tenant_id AND a.id = s.staff_id
                JOIN tenant t ON t.id = s.tenant_id
                LEFT JOIN staff_tenant_role r ON r.tenant_id = a.tenant_id AND r.staff_id = a.id
                    AND r.role_code = 'TENANT_ADMIN'
                WHERE s.sid = ? AND s.revoked_at IS NULL AND s.expires_at > ? AND a.enabled AND t.status = 'ACTIVE'
                """).params(sid, now).query((rs, row) -> new StaffProfile(rs.getString("code"),
                rs.getString("name"), rs.getString("email"), rs.getString("role_code"),
                rs.getString("id"), rs.getLong("tenant_id"), rs.getBoolean("can_queue"), rs.getBoolean("can_supervise"))).optional();
    }

    String createMember(long tenantId, String departmentCode, String email, String passwordHash, String role, String displayName) {
        String departmentId = jdbc.sql("SELECT id FROM department WHERE tenant_id = ? AND code = ?")
                .params(tenantId, departmentCode).query(String.class).optional().orElseThrow(IdentityError::notFound);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO staff_account (tenant_id, id, email, password_hash, display_name) VALUES (?, ?, ?, ?, ?)")
                .params(tenantId, id, email, passwordHash, displayName).update();
        jdbc.sql("INSERT INTO staff_department_role (tenant_id, department_id, staff_id, role_code) VALUES (?, ?, ?, ?)")
                .params(tenantId, departmentId, id, role).update();
        return id;
    }

    record LoginAccount(long tenantId, String id, String passwordHash) {}
    record StaffProfile(String tenantCode, String tenantName, String email, String role, String staffId,
                        @com.fasterxml.jackson.annotation.JsonIgnore long tenantId, boolean canQueue, boolean canSupervise) {}
}
