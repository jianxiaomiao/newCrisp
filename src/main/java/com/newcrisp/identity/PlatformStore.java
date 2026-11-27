package com.newcrisp.identity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class PlatformStore {
    private final JdbcClient jdbc;

    PlatformStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    PlatformOverview overview(LocalDateTime since24h) {
        return jdbc.sql("""
                SELECT
                    (SELECT COUNT(*) FROM tenant) AS total_tenants,
                    (SELECT COUNT(*) FROM tenant WHERE status = 'ACTIVE') AS active_tenants,
                    (SELECT COUNT(*) FROM tenant WHERE status = 'DISABLED') AS disabled_tenants,
                    (SELECT COUNT(*) FROM staff_account WHERE enabled = 1) AS total_staff,
                    (SELECT COUNT(*) FROM conversation) AS total_conversations,
                    (SELECT COUNT(*) FROM conversation WHERE status IN ('WAITING', 'ACTIVE')) AS open_conversations,
                    (SELECT COUNT(*) FROM conversation WHERE queued_at >= ?) AS conversations_last_24h
                """).param(since24h).query((rs, row) -> new PlatformOverview(
                rs.getLong("total_tenants"),
                rs.getLong("active_tenants"),
                rs.getLong("disabled_tenants"),
                rs.getLong("total_staff"),
                rs.getLong("total_conversations"),
                rs.getLong("open_conversations"),
                rs.getLong("conversations_last_24h")
        )).single();
    }

    List<TenantSummary> listTenants(String query, String status) {
        String term = query == null ? "" : query.strip();
        String like = term.isEmpty() ? null : "%" + term + "%";
        String statusFilter = status == null || status.isBlank() ? null : status.strip().toUpperCase();
        return jdbc.sql("""
                SELECT t.id, t.code, t.name, t.status, t.created_at,
                    (SELECT COUNT(*) FROM staff_account s WHERE s.tenant_id = t.id AND s.enabled = 1) AS staff_count,
                    (SELECT COUNT(*) FROM conversation c WHERE c.tenant_id = t.id) AS conversation_count,
                    (SELECT COUNT(*) FROM conversation c WHERE c.tenant_id = t.id AND c.status IN ('WAITING', 'ACTIVE')) AS open_conversations,
                    (SELECT COUNT(*) FROM guest_site g WHERE g.tenant_id = t.id AND g.enabled = 1) AS guest_site_count,
                    (SELECT n.nexus_origin FROM integration n WHERE n.tenant_id = t.id AND n.enabled = 1 ORDER BY n.id DESC LIMIT 1) AS nexus_origin,
                    (SELECT a.email FROM staff_account a
                        JOIN staff_tenant_role r ON r.tenant_id = a.tenant_id AND r.staff_id = a.id
                        WHERE a.tenant_id = t.id AND r.role_code = 'TENANT_ADMIN'
                        ORDER BY a.id ASC LIMIT 1) AS admin_email
                FROM tenant t
                WHERE (:status IS NULL OR t.status = :status)
                  AND (:like IS NULL OR t.code LIKE :like OR t.name LIKE :like)
                ORDER BY t.created_at DESC, t.id DESC
                LIMIT 200
                """)
                .param("status", statusFilter)
                .param("like", like)
                .query((rs, row) -> new TenantSummary(
                        Long.toString(rs.getLong("id")),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getLong("staff_count"),
                        rs.getLong("conversation_count"),
                        rs.getLong("open_conversations"),
                        rs.getLong("guest_site_count"),
                        rs.getString("nexus_origin"),
                        rs.getString("admin_email")
                )).list();
    }

    @Transactional
    boolean updateTenantStatus(String tenantCode, String status, LocalDateTime now) {
        var tenantId = jdbc.sql("SELECT id FROM tenant WHERE code = ?").param(tenantCode).query(Long.class).optional();
        if (tenantId.isEmpty()) return false;
        long id = tenantId.get();
        jdbc.sql("UPDATE tenant SET status = ?, updated_at = ? WHERE id = ?").params(status, now, id).update();
        if ("DISABLED".equals(status)) {
            jdbc.sql("UPDATE staff_session SET revoked_at = ? WHERE tenant_id = ? AND revoked_at IS NULL")
                    .params(now, id).update();
        }
        return true;
    }

    @Transactional
    boolean resetAdminPassword(String tenantCode, String email, String passwordHash, LocalDateTime now) {
        var staffId = jdbc.sql("""
                SELECT a.id FROM staff_account a
                JOIN tenant t ON t.id = a.tenant_id
                JOIN staff_tenant_role r ON r.tenant_id = a.tenant_id AND r.staff_id = a.id
                WHERE t.code = ? AND a.email = ? AND r.role_code = 'TENANT_ADMIN'
                """).params(tenantCode, email).query(String.class).optional();
        if (staffId.isEmpty()) return false;
        int updated = jdbc.sql("""
                UPDATE staff_account a JOIN tenant t ON t.id = a.tenant_id
                SET a.password_hash = ?, a.access_version = a.access_version + 1
                WHERE t.code = ? AND a.id = ?
                """).params(passwordHash, tenantCode, staffId.get()).update();
        if (updated == 1) {
            jdbc.sql("""
                    UPDATE staff_session s JOIN tenant t ON t.id = s.tenant_id
                    SET s.revoked_at = ?
                    WHERE t.code = ? AND s.staff_id = ? AND s.revoked_at IS NULL
                    """).params(now, tenantCode, staffId.get()).update();
            return true;
        }
        return false;
    }

    record PlatformOverview(long totalTenants, long activeTenants, long disabledTenants, long totalStaff,
                            long totalConversations, long openConversations, long conversationsLast24h) {}

    record TenantSummary(String id, String code, String name, String status, Instant createdAt,
                         long staffCount, long conversationCount, long openConversations,
                         long guestSiteCount, String nexusOrigin, String adminEmail) {}
}
