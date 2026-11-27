package com.newcrisp.identity;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EmailVerificationStore {
    private final JdbcClient jdbc;

    EmailVerificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long countRecentByEmail(String email, String purpose, LocalDateTime since) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM email_verification_code
                WHERE email = ? AND purpose = ? AND created_at >= ?
                """).params(email, purpose, since).query(Long.class).single();
    }

    long countRecentByIp(String remoteIp, LocalDateTime since) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM email_verification_code
                WHERE remote_ip = ? AND created_at >= ?
                """).params(remoteIp, since).query(Long.class).single();
    }

    void insert(String id, String email, String purpose, byte[] codeHash, String remoteIp,
                LocalDateTime now, LocalDateTime expiresAt) {
        jdbc.sql("""
                INSERT INTO email_verification_code (id, email, purpose, code_hash, attempts, remote_ip, created_at, expires_at)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?)
                """).params(id, email, purpose, codeHash, remoteIp, now, expiresAt).update();
    }

    Optional<CodeRecord> latestActive(String email, String purpose, LocalDateTime now) {
        return jdbc.sql("""
                SELECT id, code_hash, attempts FROM email_verification_code
                WHERE email = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > ?
                ORDER BY created_at DESC LIMIT 1
                """).params(email, purpose, now).query((rs, row) -> new CodeRecord(
                rs.getString("id"), rs.getBytes("code_hash"), rs.getInt("attempts"))).optional();
    }

    void incrementAttempts(String id) {
        jdbc.sql("UPDATE email_verification_code SET attempts = attempts + 1 WHERE id = ? AND consumed_at IS NULL")
                .param(id).update();
    }

    boolean consume(String id, LocalDateTime now, int maxAttempts) {
        return jdbc.sql("""
                UPDATE email_verification_code SET consumed_at = ?
                WHERE id = ? AND consumed_at IS NULL AND expires_at > ? AND attempts < ?
                """).params(now, id, now, maxAttempts).update() == 1;
    }

    void deleteOlderThan(LocalDateTime cutoff) {
        jdbc.sql("DELETE FROM email_verification_code WHERE created_at < ?").param(cutoff).update();
    }

    record CodeRecord(String id, byte[] codeHash, int attempts) {}
}
