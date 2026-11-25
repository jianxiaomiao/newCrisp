package com.newcrisp.identity;

import com.newcrisp.identity.ChatIdentity.Side;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class GuestChatService {
    private static final Duration RESUME_TTL = Duration.ofDays(30);
    private final JdbcClient jdbc;
    private final Clock clock;
    private final AccessJwt jwt;
    private final NexusLaunchService nexus;
    private final ApplicationEventPublisher events;

    GuestChatService(JdbcClient jdbc, Clock clock, AccessJwt jwt, NexusLaunchService nexus, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.jwt = jwt;
        this.nexus = nexus;
        this.events = events;
    }

    Site site(String id, boolean lock) {
        return jdbc.sql("""
                SELECT s.tenant_id, s.id, s.title, s.allowed_origin, s.enabled FROM guest_site s
                JOIN tenant t ON t.id = s.tenant_id
                WHERE s.id = ? AND s.enabled AND t.status = 'ACTIVE'
                """ + (lock ? " FOR UPDATE OF s" : ""))
                .param(id).query((rs, row) -> siteRow(rs)).optional().orElseThrow(IdentityError::notFound);
    }

    List<Site> sites(long tenant) {
        return jdbc.sql("SELECT tenant_id, id, title, allowed_origin, enabled FROM guest_site WHERE tenant_id = ? ORDER BY title, id")
                .param(tenant).query((rs, row) -> siteRow(rs)).list();
    }

    private static Site siteRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Site(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5));
    }

    Site createSite(long tenant, String origin, String title) {
        try {
            IdentityConfiguration.requireOrigin(origin);
        } catch (IllegalArgumentException error) {
            throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_GUEST_ORIGIN");
        }
        String id = UUID.randomUUID().toString();
        int rows = jdbc.sql("""
                INSERT INTO guest_site (tenant_id, id, department_id, title, allowed_origin)
                SELECT tenant_id, ?, id, ?, ? FROM department WHERE tenant_id = ? AND code = 'support'
                """).params(id, title.strip(), origin, tenant).update();
        if (rows != 1) throw IdentityError.notFound();
        return new Site(tenant, id, title.strip(), origin, true);
    }

    void disableSite(long tenant, String id) {
        if (jdbc.sql("UPDATE guest_site SET enabled = FALSE WHERE tenant_id = ? AND id = ?")
                .params(tenant, id).update() == 0) throw IdentityError.notFound();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    Connection connect(String siteId, String resume, String previousSid) {
        var site = site(siteId, resume == null);
        var now = LocalDateTime.now(clock);
        String customer;
        String newSecret = null;
        if (resume == null) {
            long recent = jdbc.sql("SELECT COUNT(*) FROM guest_resume WHERE tenant_id = ? AND site_id = ? AND created_at > ?")
                    .params(site.tenantId(), site.id(), now.minusMinutes(1)).query(Long.class).single();
            if (recent >= 100) throw new IdentityError(HttpStatus.TOO_MANY_REQUESTS, "GUEST_START_LIMIT");
            customer = UUID.randomUUID().toString();
            newSecret = IdentityTokens.generate();
            jdbc.sql("INSERT INTO customer (tenant_id, id, guest_site_id) VALUES (?, ?, ?)")
                    .params(site.tenantId(), customer, siteId).update();
            jdbc.sql("""
                    INSERT INTO guest_resume (token_hash, tenant_id, customer_id, site_id, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(IdentityTokens.digest(newSecret), site.tenantId(), customer, siteId, now, now.plus(RESUME_TTL)).update();
        } else {
            customer = guest(site.tenantId(), siteId, resume, now);
        }

        var expires = clock.instant().plus(NexusLaunchService.SESSION_TTL);
        String sid = null;
        if (resume != null && previousSid != null) {
            sid = jdbc.sql("""
                    SELECT sid FROM customer_session WHERE tenant_id = ? AND customer_id = ? AND sid = ?
                    AND revoked_at IS NULL AND expires_at > ?
                    """).params(site.tenantId(), customer, previousSid, now.plusMinutes(1)).query(String.class).optional().orElse(null);
        }
        if (sid != null) {
            expires = jdbc.sql("SELECT expires_at FROM customer_session WHERE sid = ?")
                    .param(sid).query(LocalDateTime.class).single().toInstant(java.time.ZoneOffset.UTC);
        } else {
            sid = UUID.randomUUID().toString();
            jdbc.sql("""
                    INSERT INTO customer_session (token_hash, sid, tenant_id, customer_id, external_session_id, expires_at, verified_until)
                    VALUES (?, ?, ?, ?, 0, ?, ?)
                    """).params(IdentityTokens.digest("session:" + sid), sid, site.tenantId(), customer,
                    TokenStore.utc(expires), now).update();
        }
        return new Connection(customer, site.title(), jwt.sign(new AccessJwt.Subject(Side.CUSTOMER, site.tenantId(), customer, sid), expires), newSecret);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void link(String sid, String code) {
        var signedIn = nexus.requireSession(sid);
        if (signedIn.guest()) throw IdentityError.notFound();
        String[] parts = code.split("\\.", -1);
        if (parts.length != 2 || !parts[0].matches("[a-f0-9-]{36}")) throw IdentityError.notFound();
        var now = LocalDateTime.now(clock);
        long tenant = signedIn.tenantId();
        String guest = guest(tenant, parts[0], parts[1], now);
        String customer = signedIn.profile().customerId();
        jdbc.sql("SELECT id FROM customer WHERE tenant_id = ? AND id IN (?, ?) ORDER BY id FOR UPDATE")
                .params(tenant, customer, guest).query(String.class).list();
        var active = jdbc.sql("SELECT customer_id FROM conversation WHERE tenant_id = ? AND customer_id IN (?, ?) AND active_marker = 1 FOR UPDATE")
                .params(tenant, customer, guest).query(String.class).list();
        if (active.size() > 1) throw new IdentityError(HttpStatus.CONFLICT, "GUEST_ACTIVE_CONVERSATION_CONFLICT");
        jdbc.sql("UPDATE conversation SET customer_id = ? WHERE tenant_id = ? AND customer_id = ?")
                .params(customer, tenant, guest).update();
        jdbc.sql("UPDATE guest_resume SET linked_at = ? WHERE token_hash = ?")
                .params(now, IdentityTokens.digest(parts[1])).update();
        var revoked = jdbc.sql("SELECT sid FROM customer_session WHERE tenant_id = ? AND customer_id = ? AND revoked_at IS NULL")
                .params(tenant, guest).query(String.class).list();
        jdbc.sql("UPDATE customer_session SET revoked_at = ? WHERE tenant_id = ? AND customer_id = ? AND revoked_at IS NULL")
                .params(now, tenant, guest).update();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                revoked.forEach(ended -> events.publishEvent(new ChatIdentity.SessionEnded(Side.CUSTOMER, ended)));
            }
        });
    }

    private String guest(long tenant, String site, String token, LocalDateTime now) {
        if (!IdentityTokens.valid(token)) throw IdentityError.notFound();
        return jdbc.sql("""
                SELECT customer_id FROM guest_resume
                WHERE token_hash = ? AND tenant_id = ? AND site_id = ? AND expires_at > ? AND linked_at IS NULL
                FOR UPDATE
                """).params(IdentityTokens.digest(token), tenant, site, now).query(String.class).optional()
                .orElseThrow(IdentityError::notFound);
    }

    record Site(@com.fasterxml.jackson.annotation.JsonIgnore long tenantId, String id, String title, String origin, boolean enabled) {}
    record Connection(String customerId, String tenantName, AccessJwt.Token token, String resume) {}
}
