package com.newcrisp.identity;

import com.newcrisp.identity.AccessJwt.Subject;
import com.newcrisp.identity.ChatIdentity.Side;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TokenStore {
    private final JdbcClient jdbc;

    TokenStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    Optional<Session> session(Side side, String sid, Instant now, boolean lock) {
        String id = side == Side.STAFF ? "staff_id" : "customer_id";
        return jdbc.sql("SELECT tenant_id, " + id + " AS subject_id, expires_at FROM " + table(side)
                + " WHERE sid = ? AND revoked_at IS NULL AND expires_at > ?" + (lock ? " FOR UPDATE" : ""))
                .params(sid, utc(now)).query((rs, row) -> new Session(new Subject(side, rs.getLong("tenant_id"),
                        rs.getString("subject_id"), sid), rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .optional();
    }

    boolean revoke(Side side, String sid, Instant now) {
        return jdbc.sql("UPDATE " + table(side) + " SET revoked_at = ? WHERE sid = ? AND revoked_at IS NULL")
                .params(utc(now), sid).update() == 1;
    }

    Optional<Refresh> refresh(Side side, byte[] hash, boolean lock) {
        String column = column(side);
        return jdbc.sql("SELECT " + column + " AS sid, bootstrap, used_at, expires_at FROM refresh_token WHERE token_hash = ? AND "
                + column + " IS NOT NULL" + (lock ? " FOR UPDATE" : ""))
                .param(hash).query((rs, row) -> new Refresh(rs.getString("sid"), rs.getBoolean("bootstrap"),
                        rs.getObject("used_at", LocalDateTime.class) != null,
                        rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC))).optional();
    }

    void insertRefresh(Subject subject, byte[] hash, Instant expires, boolean bootstrap) {
        jdbc.sql("INSERT INTO refresh_token (token_hash, " + column(subject.side()) + ", expires_at, bootstrap) VALUES (?, ?, ?, ?)")
                .params(hash, subject.sid(), utc(expires), bootstrap).update();
    }

    boolean consumeRefresh(byte[] hash, Instant now) {
        return jdbc.sql("UPDATE refresh_token SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?")
                .params(utc(now), hash, utc(now)).update() == 1;
    }

    void insertTicket(Subject subject, byte[] hash, Instant expires, Instant accessExpires, Long staffAccessVersion) {
        jdbc.sql("INSERT INTO websocket_ticket (token_hash, " + column(subject.side())
                + ", expires_at, access_expires_at, staff_access_version) VALUES (?, ?, ?, ?, ?)")
                .params(hash, subject.sid(), utc(expires), utc(accessExpires), staffAccessVersion).update();
    }

    Optional<Ticket> ticket(Side side, byte[] hash, Instant now) {
        String column = column(side);
        return jdbc.sql("SELECT " + column + " AS sid, access_expires_at, staff_access_version FROM websocket_ticket WHERE token_hash = ? "
                + "AND " + column + " IS NOT NULL AND expires_at > ?")
                .params(hash, utc(now)).query((rs, row) -> new Ticket(rs.getString("sid"),
                        rs.getObject("access_expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        rs.getObject("staff_access_version", Long.class))).optional();
    }

    boolean consumeTicket(Side side, byte[] hash, Instant now) {
        return jdbc.sql("DELETE FROM websocket_ticket WHERE token_hash = ? AND " + column(side)
                + " IS NOT NULL AND expires_at > ?").params(hash, utc(now)).update() == 1;
    }

    void prune(Instant now) {
        jdbc.sql("DELETE FROM websocket_ticket WHERE expires_at <= ? LIMIT 500").param(utc(now)).update();
        jdbc.sql("DELETE FROM refresh_token WHERE expires_at <= ? AND used_at IS NULL LIMIT 500").param(utc(now)).update();
        // A used bootstrap token is kept until its session expires, even though its launch window is shorter.
        jdbc.sql("""
                DELETE FROM refresh_token WHERE token_hash IN (
                    SELECT token_hash FROM (
                        SELECT r.token_hash FROM refresh_token r
                        LEFT JOIN staff_session a ON a.sid = r.staff_sid
                        LEFT JOIN customer_session c ON c.sid = r.customer_sid
                        WHERE r.expires_at <= ? AND COALESCE(a.expires_at, c.expires_at) <= ? LIMIT 500
                    ) expired
                )
                """).params(utc(now), utc(now)).update();
    }

    private static String table(Side side) { return side == Side.STAFF ? "staff_session" : "customer_session"; }
    private static String column(Side side) { return side == Side.STAFF ? "staff_sid" : "customer_sid"; }
    static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }

    record Session(Subject subject, Instant expiresAt) {}
    record Refresh(String sid, boolean bootstrap, boolean used, Instant expiresAt) {}
    record Ticket(String sid, Instant accessExpiresAt, Long staffAccessVersion) {}
}
