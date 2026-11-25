package com.newcrisp.chat;

import static com.newcrisp.chat.ChatModels.*;

import com.newcrisp.identity.ChatIdentity;
import com.newcrisp.identity.ChatIdentity.Actor;
import com.newcrisp.identity.ChatIdentity.Side;
import com.newcrisp.identity.IdentityError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff")
class CollaborationController {
    private final ChatIdentity identity;
    private final ChatService chat;
    private final JdbcClient jdbc;
    private final Clock clock;

    CollaborationController(ChatIdentity identity, ChatService chat, JdbcClient jdbc, Clock clock) {
        this.identity = identity;
        this.chat = chat;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/conversations/{id}/notes")
    ResponseEntity<List<Note>> notes(@PathVariable String id, HttpServletRequest request) {
        var actor = identity.authenticate(request, Side.STAFF, false);
        var row = chat.view(actor, id);
        return ok(jdbc.sql("""
                SELECT n.id, n.staff_id, s.display_name, n.body, n.created_at
                FROM conversation_note n
                JOIN staff_account s ON s.tenant_id = n.tenant_id AND s.id = n.staff_id
                WHERE n.tenant_id = ? AND n.conversation_id = ?
                ORDER BY n.created_at ASC, n.id ASC
                """).params(row.tenantId(), row.id())
                .query((rs, i) -> new Note(rs.getString("id"), rs.getString("staff_id"),
                        rs.getString("display_name"), rs.getString("body"),
                        rs.getObject("created_at", LocalDateTime.class) + "Z")).list());
    }

    @PostMapping("/conversations/{id}/notes")
    @Transactional
    ResponseEntity<Note> addNote(@PathVariable String id, @Valid @RequestBody NoteInput input, HttpServletRequest request) {
        var actor = identity.authenticate(request, Side.STAFF, true);
        var row = chat.view(actor, id);
        String body = input.body().strip();
        if (body.isEmpty()) throw invalid("INVALID_NOTE");
        String noteId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.sql("""
                INSERT INTO conversation_note (tenant_id, id, conversation_id, staff_id, body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """).params(row.tenantId(), noteId, row.id(), actor.id(), body, now).update();
        String author = jdbc.sql("SELECT display_name FROM staff_account WHERE tenant_id = ? AND id = ?")
                .params(row.tenantId(), actor.id()).query(String.class).single();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(new Note(noteId, actor.id(), author, body, now + "Z"));
    }

    @GetMapping("/tags")
    ResponseEntity<List<Tag>> tags(HttpServletRequest request) {
        var actor = identity.authenticate(request, Side.STAFF, false);
        return ok(jdbc.sql("SELECT id, name, color FROM conversation_tag WHERE tenant_id = ? ORDER BY name, id")
                .param(actor.tenantId())
                .query((rs, i) -> new Tag(rs.getString("id"), rs.getString("name"), rs.getString("color"))).list());
    }

    @PostMapping("/tags")
    ResponseEntity<Tag> createTag(@Valid @RequestBody TagInput input, HttpServletRequest request) {
        var actor = requireAdmin(request);
        String name = input.name().strip();
        String color = input.color() == null || !input.color().matches("#[0-9a-fA-F]{6}") ? "#409eff" : input.color();
        String tagId = UUID.randomUUID().toString();
        try {
            jdbc.sql("INSERT INTO conversation_tag (tenant_id, id, name, color) VALUES (?, ?, ?, ?)")
                    .params(actor.tenantId(), tagId, name, color).update();
        } catch (DuplicateKeyException dup) {
            throw conflict("TAG_ALREADY_EXISTS");
        }
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new Tag(tagId, name, color));
    }

    @DeleteMapping("/tags/{id}")
    @Transactional
    ResponseEntity<Void> deleteTag(@PathVariable String id, HttpServletRequest request) {
        var actor = requireAdmin(request);
        jdbc.sql("DELETE FROM conversation_tag_rel WHERE tenant_id = ? AND tag_id = ?")
                .params(actor.tenantId(), uuid(id)).update();
        if (jdbc.sql("DELETE FROM conversation_tag WHERE tenant_id = ? AND id = ?")
                .params(actor.tenantId(), uuid(id)).update() == 0) {
            throw IdentityError.notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/conversations/{id}/tags")
    ResponseEntity<List<Tag>> conversationTags(@PathVariable String id, HttpServletRequest request) {
        var actor = identity.authenticate(request, Side.STAFF, false);
        var row = chat.view(actor, id);
        return ok(jdbc.sql("""
                SELECT t.id, t.name, t.color FROM conversation_tag_rel r
                JOIN conversation_tag t ON t.tenant_id = r.tenant_id AND t.id = r.tag_id
                WHERE r.tenant_id = ? AND r.conversation_id = ? ORDER BY t.name
                """).params(row.tenantId(), row.id())
                .query((rs, i) -> new Tag(rs.getString("id"), rs.getString("name"), rs.getString("color"))).list());
    }

    @PutMapping("/conversations/{id}/tags")
    @Transactional
    ResponseEntity<List<Tag>> setConversationTags(@PathVariable String id, @RequestBody TagAssignment input, HttpServletRequest request) {
        var actor = identity.authenticate(request, Side.STAFF, true);
        var row = chat.view(actor, id);
        var tagIds = input.tagIds() == null ? List.<String>of() : input.tagIds().stream().map(ChatModels::uuid).distinct().toList();
        if (tagIds.size() > 10) throw invalid("TOO_MANY_TAGS");
        jdbc.sql("DELETE FROM conversation_tag_rel WHERE tenant_id = ? AND conversation_id = ?")
                .params(row.tenantId(), row.id()).update();
        LocalDateTime now = LocalDateTime.now(clock);
        for (String tagId : tagIds) {
            int exists = jdbc.sql("SELECT COUNT(*) FROM conversation_tag WHERE tenant_id = ? AND id = ?")
                    .params(row.tenantId(), tagId).query(Integer.class).single();
            if (exists == 0) throw IdentityError.notFound();
            jdbc.sql("INSERT INTO conversation_tag_rel (tenant_id, conversation_id, tag_id, created_at) VALUES (?, ?, ?, ?)")
                    .params(row.tenantId(), row.id(), tagId, now).update();
        }
        return conversationTags(id, request);
    }

    @GetMapping("/conversations/search")
    ResponseEntity<List<ListItem>> search(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) State status,
                                          @RequestParam(required = false) String tagId,
                                          @RequestParam(defaultValue = "30") int limit,
                                          HttpServletRequest request) {
        var actor = identity.authenticate(request, Side.STAFF, false);
        int capped = Math.max(1, Math.min(limit, 50));
        var depts = new HashSet<String>(actor.agentDepartments());
        depts.addAll(actor.supervisorDepartments());
        if (depts.isEmpty()) return ok(List.of());
        String term = q == null || q.isBlank() ? null : "%" + q.strip() + "%";
        String tag = tagId == null || tagId.isBlank() ? null : uuid(tagId);
        Set<String> supDepts = actor.supervisorDepartments().isEmpty() ? Set.of("__none__") : actor.supervisorDepartments();

        var sql = new StringBuilder("""
                SELECT c.id, c.status, c.state_version, c.last_seq, c.last_customer_seq, c.queued_at,
                       c.staff_read_seq AS read_seq, c.last_activity_at AS sort_time,
                       LEFT(m.body, 100) AS preview, u.display_name AS customer_name,
                       u.avatar_url AS customer_avatar, u.guest_site_id IS NOT NULL AS is_guest
                FROM conversation c
                JOIN customer u ON u.tenant_id = c.tenant_id AND u.id = c.customer_id
                LEFT JOIN conversation_message m ON m.tenant_id = c.tenant_id AND m.conversation_id = c.id AND m.seq = c.last_seq
                WHERE c.tenant_id = :tenant AND c.department_id IN (:departments)
                  AND (c.assignee_id = :actor OR c.status = 'WAITING' OR c.department_id IN (:supervisorDepts))
                """);
        if (status != null) sql.append(" AND c.status = :status");
        if (tag != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM conversation_tag_rel r WHERE r.tenant_id = c.tenant_id AND r.conversation_id = c.id AND r.tag_id = :tag)");
        }
        if (term != null) {
            sql.append("""
                     AND (u.display_name LIKE :term OR u.email LIKE :term OR CAST(u.external_user_id AS CHAR) LIKE :term
                          OR EXISTS (SELECT 1 FROM conversation_message cm WHERE cm.tenant_id = c.tenant_id
                                     AND cm.conversation_id = c.id AND cm.body LIKE :term))
                    """);
        }
        sql.append(" ORDER BY c.last_activity_at DESC, c.id DESC LIMIT :limit");

        var stmt = jdbc.sql(sql.toString())
                .param("tenant", actor.tenantId())
                .param("departments", depts)
                .param("actor", actor.id())
                .param("supervisorDepts", supDepts)
                .param("limit", capped);
        if (status != null) stmt.param("status", status.name());
        if (tag != null) stmt.param("tag", tag);
        if (term != null) stmt.param("term", term);

        return ok(stmt.query((rs, i) -> new ListItem(rs.getString("id"), State.valueOf(rs.getString("status")),
                Long.toString(rs.getLong("state_version")), Long.toString(rs.getLong("last_seq")),
                Long.toString(rs.getLong("last_customer_seq")), Long.toString(rs.getLong("read_seq")),
                rs.getObject("sort_time", LocalDateTime.class) + "Z", rs.getObject("queued_at", LocalDateTime.class) + "Z",
                rs.getString("preview"), 0, rs.getString("customer_name"),
                rs.getString("customer_avatar"), rs.getBoolean("is_guest"))).list());
    }

    private Actor requireAdmin(HttpServletRequest request) {
        var actor = identity.authenticate(request, Side.STAFF, true);
        boolean isAdmin = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM staff_tenant_role WHERE tenant_id = ? AND staff_id = ? AND role_code = 'TENANT_ADMIN')
                """).params(actor.tenantId(), actor.id()).query(Boolean.class).single();
        if (!isAdmin) throw new IdentityError(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
        return actor;
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    record NoteInput(@NotBlank @Size(max = 4000) String body) {}
    record Note(String id, String staffId, String author, String body, String createdAt) {}
    record TagInput(@NotBlank @Size(max = 32) String name, String color) {}
    record Tag(String id, String name, String color) {}
    record TagAssignment(List<String> tagIds) {}
}
