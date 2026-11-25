package com.newcrisp.chat;

import static com.newcrisp.chat.ChatModels.*;

import com.newcrisp.identity.ChatIdentity;
import com.newcrisp.identity.ChatIdentity.Side;
import com.newcrisp.identity.IdentityError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class AnalyticsController {
    private final ChatIdentity identity;
    private final ChatService chat;
    private final JdbcClient jdbc;
    private final Clock clock;

    AnalyticsController(ChatIdentity identity, ChatService chat, JdbcClient jdbc, Clock clock) {
        this.identity = identity;
        this.chat = chat;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/api/v1/{side:customer|staff}/conversations/{id}/rating")
    ResponseEntity<Rating> rating(@PathVariable String side, @PathVariable String id, HttpServletRequest request) {
        var actor = identity.authenticate(request, Side.fromPath(side), false);
        var row = chat.view(actor, id);
        var result = jdbc.sql("""
                SELECT score, comment, created_at FROM conversation_rating
                WHERE tenant_id = ? AND conversation_id = ?
                """).params(row.tenantId(), row.id())
                .query((rs, i) -> new Rating(rs.getInt("score"), rs.getString("comment"),
                        rs.getObject("created_at", LocalDateTime.class) + "Z")).optional().orElse(null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/api/v1/customer/conversations/{id}/rating")
    @Transactional
    ResponseEntity<Rating> submitRating(@PathVariable String id, @Valid @RequestBody RatingInput input, HttpServletRequest request) {
        var actor = identity.authenticate(request, Side.CUSTOMER, true);
        var row = chat.view(actor, id);
        if (row.state() != State.CLOSED) throw conflict("CONVERSATION_NOT_CLOSED");
        String comment = input.comment() == null || input.comment().isBlank() ? null : input.comment().strip();
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            jdbc.sql("""
                    INSERT INTO conversation_rating (tenant_id, conversation_id, score, comment, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """).params(row.tenantId(), row.id(), input.score(), comment, now).update();
        } catch (DuplicateKeyException dup) {
            throw conflict("ALREADY_RATED");
        }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(new Rating(input.score(), comment, now + "Z"));
    }

    @GetMapping("/api/v1/staff/analytics/overview")
    ResponseEntity<Overview> overview(@RequestParam(defaultValue = "7") int days, HttpServletRequest request) {
        var actor = identity.authenticate(request, Side.STAFF, false);
        boolean isAdmin = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM staff_tenant_role WHERE tenant_id = ? AND staff_id = ? AND role_code = 'TENANT_ADMIN')
                """).params(actor.tenantId(), actor.id()).query(Boolean.class).single();
        if (!isAdmin && actor.supervisorDepartments().isEmpty()) {
            throw new IdentityError(HttpStatus.FORBIDDEN, "ANALYTICS_PERMISSION_REQUIRED");
        }
        int span = Math.max(1, Math.min(days, 90));
        LocalDateTime since = LocalDateTime.now(clock).minusDays(span);

        var summary = jdbc.sql("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN c.status = 'CLOSED' THEN 1 ELSE 0 END) AS closed,
                       AVG(CASE WHEN c.first_staff_reply_at IS NOT NULL
                                THEN TIMESTAMPDIFF(SECOND, c.queued_at, c.first_staff_reply_at) END) AS avg_frt,
                       AVG(CASE WHEN c.closed_at IS NOT NULL
                                THEN TIMESTAMPDIFF(SECOND, c.queued_at, c.closed_at) END) AS avg_art,
                       COUNT(r.score) AS rating_count,
                       AVG(r.score) AS avg_score,
                       SUM(CASE WHEN r.score >= 4 THEN 1 ELSE 0 END) AS positive_count
                FROM conversation c
                LEFT JOIN conversation_rating r ON r.tenant_id = c.tenant_id AND r.conversation_id = c.id
                WHERE c.tenant_id = ? AND c.queued_at >= ?
                """).params(actor.tenantId(), since).query((rs, i) -> new SummaryStats(
                rs.getLong("total"),
                rs.getLong("closed"),
                rs.getObject("avg_frt") == null ? null : Math.round(rs.getDouble("avg_frt")),
                rs.getObject("avg_art") == null ? null : Math.round(rs.getDouble("avg_art")),
                rs.getLong("rating_count"),
                rs.getObject("avg_score") == null ? null : Math.round(rs.getDouble("avg_score") * 10.0) / 10.0,
                rs.getLong("positive_count"))).single();

        var agents = jdbc.sql("""
                SELECT s.id, s.display_name, s.email,
                       COUNT(c.id) AS total,
                       SUM(CASE WHEN c.status = 'CLOSED' THEN 1 ELSE 0 END) AS closed,
                       AVG(CASE WHEN c.first_staff_reply_at IS NOT NULL
                                THEN TIMESTAMPDIFF(SECOND, c.queued_at, c.first_staff_reply_at) END) AS avg_frt,
                       AVG(r.score) AS avg_score
                FROM conversation c
                JOIN staff_account s ON s.tenant_id = c.tenant_id AND s.id = c.assignee_id
                LEFT JOIN conversation_rating r ON r.tenant_id = c.tenant_id AND r.conversation_id = c.id
                WHERE c.tenant_id = ? AND c.queued_at >= ?
                GROUP BY s.id, s.display_name, s.email
                ORDER BY total DESC, s.display_name ASC LIMIT 50
                """).params(actor.tenantId(), since).query((rs, i) -> new AgentStats(
                rs.getString("id"), rs.getString("display_name"), rs.getString("email"),
                rs.getLong("total"), rs.getLong("closed"),
                rs.getObject("avg_frt") == null ? null : Math.round(rs.getDouble("avg_frt")),
                rs.getObject("avg_score") == null ? null : Math.round(rs.getDouble("avg_score") * 10.0) / 10.0)).list();

        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Overview(span, summary, agents));
    }

    record RatingInput(@Min(1) @Max(5) int score, @Size(max = 500) String comment) {}
    record Rating(int score, String comment, String createdAt) {}
    record SummaryStats(long total, long closed, Long avgFrtSeconds, Long avgArtSeconds,
                        long ratingCount, Double avgScore, long positiveCount) {}
    record AgentStats(String staffId, String name, String email, long total, long closed, Long avgFrtSeconds, Double avgScore) {}
    record Overview(int days, SummaryStats summary, List<AgentStats> agents) {}
}
