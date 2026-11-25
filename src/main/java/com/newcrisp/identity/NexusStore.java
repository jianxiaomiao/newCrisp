package com.newcrisp.identity;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class NexusStore {
    private final JdbcClient jdbc;

    NexusStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    void createIntegration(String tenantCode, String nexusOrigin, String integrationId, String keyId, byte[] hash) {
        int inserted = jdbc.sql("""
                INSERT INTO integration (tenant_id, id, department_id, nexus_origin)
                SELECT t.id, ?, d.id, ? FROM tenant t JOIN department d ON d.tenant_id = t.id
                WHERE t.code = ? AND t.status = 'ACTIVE' AND d.code = 'support'
                """).params(integrationId, nexusOrigin, tenantCode).update();
        if (inserted != 1) throw IdentityError.notFound();
        int credentials = jdbc.sql("""
                INSERT INTO integration_credential (key_id, tenant_id, integration_id, secret_hash)
                SELECT ?, i.tenant_id, i.id, ? FROM integration i JOIN tenant t ON t.id = i.tenant_id
                WHERE t.code = ? AND i.id = ?
                """).params(keyId, hash, tenantCode, integrationId).update();
        if (credentials != 1) throw new IllegalStateException("Integration credential was not created");
    }

    Optional<IntegrationKey> integrationKey(String keyId) {
        return jdbc.sql("""
                SELECT k.tenant_id, k.integration_id, k.secret_hash FROM integration_credential k
                JOIN integration i ON i.tenant_id = k.tenant_id AND i.id = k.integration_id
                JOIN tenant t ON t.id = k.tenant_id
                WHERE k.key_id = ? AND k.enabled AND i.enabled AND t.status = 'ACTIVE'
                """).param(keyId).query((rs, row) -> new IntegrationKey(rs.getLong("tenant_id"),
                rs.getString("integration_id"), rs.getBytes("secret_hash"))).optional();
    }

    void createGrant(byte[] hash, IntegrationKey key, long userId, long sessionId, LocalDateTime expiresAt) {
        jdbc.sql("""
                INSERT INTO launch_grant (token_hash, tenant_id, integration_id, external_user_id, external_session_id, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(hash, key.tenantId(), key.integrationId(), userId, sessionId, expiresAt).update();
    }

    Optional<Grant> availableGrant(byte[] hash, LocalDateTime now) {
        return jdbc.sql("""
                SELECT g.tenant_id, g.integration_id, g.external_user_id, g.external_session_id, i.nexus_origin
                FROM launch_grant g JOIN integration i ON i.tenant_id = g.tenant_id AND i.id = g.integration_id
                JOIN tenant t ON t.id = g.tenant_id
                WHERE g.token_hash = ? AND g.consumed_at IS NULL AND g.expires_at > ? AND i.enabled AND t.status = 'ACTIVE'
                """).params(hash, now).query((rs, row) -> new Grant(rs.getLong("tenant_id"),
                rs.getString("integration_id"), rs.getLong("external_user_id"), rs.getLong("external_session_id"),
                rs.getString("nexus_origin"))).optional();
    }

    boolean consumeGrant(byte[] hash, String origin, LocalDateTime now) {
        return jdbc.sql("""
                UPDATE launch_grant g JOIN integration i ON i.tenant_id = g.tenant_id AND i.id = g.integration_id
                JOIN tenant t ON t.id = g.tenant_id SET g.consumed_at = ?
                WHERE g.token_hash = ? AND g.consumed_at IS NULL AND g.expires_at > ?
                  AND i.nexus_origin = ? AND i.enabled AND t.status = 'ACTIVE'
                """).params(now, hash, now, origin).update() == 1;
    }

    String createSession(String sid, Grant grant, LocalDateTime expiresAt, LocalDateTime verifiedUntil) {
        jdbc.sql("""
                INSERT INTO customer (tenant_id, id, integration_id, external_user_id) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE external_user_id = customer.external_user_id
                """).params(grant.tenantId(), UUID.randomUUID().toString(), grant.integrationId(), grant.userId()).update();
        String customerId = jdbc.sql("""
                SELECT id FROM customer WHERE tenant_id = ? AND integration_id = ? AND external_user_id = ?
                """).params(grant.tenantId(), grant.integrationId(), grant.userId()).query(String.class).single();
        jdbc.sql("""
                INSERT INTO customer_session (token_hash, sid, tenant_id, customer_id, external_session_id, expires_at, verified_until)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(IdentityTokens.digest("session:" + sid), sid, grant.tenantId(), customerId,
                grant.sessionId(), expiresAt, verifiedUntil).update();
        return customerId;
    }

    Optional<CustomerSession> session(String sid, LocalDateTime now) {
        return jdbc.sql("""
                SELECT c.id, c.external_user_id, s.external_session_id, s.verified_until, t.code, t.name,
                    c.tenant_id, COALESCE(i.department_id, g.department_id) AS department_id, c.guest_site_id
                FROM customer_session s JOIN customer c ON c.tenant_id = s.tenant_id AND c.id = s.customer_id
                LEFT JOIN integration i ON i.tenant_id = c.tenant_id AND i.id = c.integration_id
                LEFT JOIN guest_site g ON g.tenant_id = c.tenant_id AND g.id = c.guest_site_id
                JOIN tenant t ON t.id = c.tenant_id
                WHERE s.sid = ? AND s.revoked_at IS NULL AND s.expires_at > ?
                  AND (i.enabled OR g.enabled) AND t.status = 'ACTIVE'
                """).params(sid, now).query((rs, row) -> new CustomerSession(
                new CustomerProfile(rs.getString("id"), rs.getString("code"), rs.getString("name")),
                rs.getLong("external_user_id"), rs.getLong("external_session_id"),
                rs.getObject("verified_until", LocalDateTime.class), rs.getLong("tenant_id"),
                rs.getString("department_id"), rs.getString("guest_site_id") != null)).optional();
    }

    void verified(String sid, LocalDateTime until) {
        jdbc.sql("UPDATE customer_session SET verified_until = GREATEST(verified_until, ?) WHERE sid = ? AND revoked_at IS NULL")
                .params(until, sid).update();
    }

    record IntegrationKey(long tenantId, String integrationId, byte[] secretHash) {}
    record Grant(long tenantId, String integrationId, long userId, long sessionId, String origin) {}
    record CustomerProfile(String customerId, String tenantCode, String tenantName) {}
    record CustomerSession(CustomerProfile profile, long userId, long sessionId, LocalDateTime verifiedUntil,
                           long tenantId, String departmentId, boolean guest) {
        CustomerSession(CustomerProfile profile, long userId, long sessionId, LocalDateTime verifiedUntil,
                        long tenantId, String departmentId) {
            this(profile, userId, sessionId, verifiedUntil, tenantId, departmentId, false);
        }
    }
}
