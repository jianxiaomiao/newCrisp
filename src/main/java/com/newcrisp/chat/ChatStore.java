package com.newcrisp.chat;

import static com.newcrisp.chat.ChatModels.*;

import com.newcrisp.identity.ChatIdentity.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ChatStore {
    private final JdbcClient jdbc;
    private final CustomerUnread customerUnread;

    ChatStore(JdbcClient jdbc, CustomerUnread customerUnread) {
        this.jdbc = jdbc;
        this.customerUnread = customerUnread;
    }

    boolean lockCustomer(Actor actor) {
        return jdbc.sql("SELECT id FROM customer WHERE tenant_id = ? AND id = ? FOR UPDATE")
                .params(actor.tenantId(), actor.id()).query(String.class).optional().isPresent();
    }

    Optional<Conversation> active(Actor actor) {
        return jdbc.sql("SELECT * FROM conversation WHERE tenant_id = ? AND customer_id = ? AND active_marker = 1 FOR UPDATE")
                .params(actor.tenantId(), actor.id()).query(ChatStore::conversation).optional();
    }

    void create(Actor actor, String id, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO conversation (tenant_id,id,customer_id,department_id,status,queued_at,last_activity_at)
                VALUES (?, ?, ?, ?, 'WAITING', ?, ?)
                """).params(actor.tenantId(), id, actor.id(), actor.defaultDepartment(), now, now).update();
    }

    Optional<Conversation> find(long tenant, String id, boolean lock) {
        return jdbc.sql("SELECT * FROM conversation WHERE tenant_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""))
                .params(tenant, id).query(ChatStore::conversation).optional();
    }

    private static Conversation conversation(ResultSet rs, int row) throws SQLException {
        return new Conversation(rs.getLong("tenant_id"), rs.getString("id"), rs.getString("customer_id"),
                rs.getString("department_id"), rs.getString("assignee_id"), State.valueOf(rs.getString("status")),
                rs.getLong("state_version"), rs.getLong("last_seq"), rs.getLong("last_customer_seq"),
                rs.getLong("last_staff_seq"), rs.getLong("customer_read_seq"), rs.getLong("staff_read_seq"),
                rs.getObject("queued_at", LocalDateTime.class), rs.getObject("last_activity_at", LocalDateTime.class));
    }

    Optional<StoredMessage> replay(Actor actor, String clientMessageId) {
        String sender = actor.customer() ? "customer_sender_id" : "staff_sender_id";
        return jdbc.sql("SELECT * FROM conversation_message WHERE tenant_id = ? AND " + sender + " = ? AND client_message_id = ?")
                .params(actor.tenantId(), actor.id(), clientMessageId).query(ChatStore::message).optional();
    }

    Optional<StoredMessage> message(Conversation chat, long seq) {
        return jdbc.sql("SELECT * FROM conversation_message WHERE tenant_id = ? AND conversation_id = ? AND seq = ?")
                .params(chat.tenantId(), chat.id(), seq).query(ChatStore::message).optional();
    }

    StoredMessage append(Conversation chat, Actor actor, String clientMessageId, String text, boolean first, LocalDateTime now) {
        long seq = chat.lastSeq() + 1;
        jdbc.sql("""
                INSERT INTO conversation_message (tenant_id, conversation_id, seq, customer_sender_id, staff_sender_id,
                    client_message_id, first_send, body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(chat.tenantId(), chat.id(), seq, actor.customer() ? actor.id() : null,
                actor.customer() ? null : actor.id(), clientMessageId, first, text, now).update();
        String senderSequence = actor.customer() ? "last_customer_seq" : "last_staff_seq";
        jdbc.sql("UPDATE conversation SET last_seq = ?, " + senderSequence + " = ?, last_activity_at = ? WHERE tenant_id = ? AND id = ?")
                .params(seq, seq, now, chat.tenantId(), chat.id()).update();
        return message(chat, seq).orElseThrow();
    }

    List<StoredMessage> messages(Conversation chat, Long after, Long before, int limit) {
        String order = after != null ? " ASC" : " DESC";
        String boundary = after != null ? " AND seq > :seq" : before != null ? " AND seq < :seq" : "";
        var statement = jdbc.sql("SELECT * FROM conversation_message WHERE tenant_id = :tenant AND conversation_id = :id"
                + boundary + " ORDER BY seq" + order + " LIMIT :limit")
                .param("tenant", chat.tenantId()).param("id", chat.id()).param("limit", limit);
        if (after != null || before != null) statement.param("seq", after != null ? after : before);
        return statement.query(ChatStore::message).list();
    }

    private static StoredMessage message(ResultSet rs, int row) throws SQLException {
        return new StoredMessage(rs.getString("conversation_id"), rs.getLong("seq"),
                rs.getString("customer_sender_id") != null, rs.getString("body"),
                rs.getObject("created_at", LocalDateTime.class), rs.getString("client_message_id"), rs.getBoolean("first_send"));
    }

    List<ListItem> list(Actor actor, View view, Set<String> departments, Cursor cursor, int limit) {
        boolean pending = view == View.PENDING;
        String time = pending ? "c.queued_at" : "c.last_activity_at";
        String order = pending ? " ASC" : " DESC";
        String operator = pending ? ">" : "<";
        String scope = switch (view) {
            case CURRENT -> "c.customer_id = :actor AND c.active_marker = 1";
            case HISTORY -> "c.customer_id = :actor AND c.status = 'CLOSED'";
            case PENDING -> "c.department_id IN (:departments) AND c.status = 'WAITING'";
            case MINE -> "c.department_id IN (:departments) AND c.assignee_id = :actor AND c.status = 'ACTIVE'";
            case CLOSED -> "c.department_id IN (:departments) AND c.assignee_id = :actor AND c.status = 'CLOSED'";
            case DEPARTMENT -> "c.department_id IN (:departments)";
        };
        String readSeq = actor.customer() ? "c.customer_read_seq" : pending || view == View.DEPARTMENT ? "0" : "c.staff_read_seq";
        String sender = actor.customer() ? "m.staff_sender_id" : "m.customer_sender_id";
        String unread = view == View.PENDING || view == View.DEPARTMENT ? "0" :
                "(SELECT COUNT(*) FROM conversation_message m WHERE m.tenant_id = c.tenant_id AND m.conversation_id = c.id "
                        + "AND m.seq > " + readSeq + " AND " + sender + " IS NOT NULL)";
        String boundary = cursor == null ? "" : " AND (" + time + " " + operator + " :time OR (" + time
                + " = :time AND c.id " + operator + " :cursorId))";
        var statement = jdbc.sql("SELECT c.id, c.status, c.state_version, c.last_seq, c.last_customer_seq, "
                + readSeq + " AS read_seq, " + time + " AS sort_time, " + unread + " AS unread, "
                + "LEFT(m.body, 100) AS preview FROM conversation c LEFT JOIN conversation_message m "
                + "ON m.tenant_id = c.tenant_id AND m.conversation_id = c.id AND m.seq = " + (pending ? "1" : "c.last_seq")
                + " WHERE c.tenant_id = :tenant AND " + scope + boundary
                + " ORDER BY " + time + order + ", c.id" + order + " LIMIT :limit")
                .param("tenant", actor.tenantId()).param("limit", limit);
        if (scope.contains(":actor")) statement.param("actor", actor.id());
        if (scope.contains(":departments")) statement.param("departments", departments);
        if (cursor != null) statement.param("time", cursor.time()).param("cursorId", cursor.id());
        return statement.query((rs, row) -> new ListItem(rs.getString("id"), State.valueOf(rs.getString("status")),
                Long.toString(rs.getLong("state_version")), Long.toString(rs.getLong("last_seq")),
                Long.toString(rs.getLong("last_customer_seq")), Long.toString(rs.getLong("read_seq")),
                rs.getObject("sort_time", LocalDateTime.class) + "Z", rs.getString("preview"), rs.getLong("unread"))).list();
    }

    Summary summary(Actor actor) {
        if (actor.customer()) return new Summary(0, customerUnread.forCustomer(actor.tenantId(), actor.id()));
        if (actor.agentDepartments().isEmpty()) return new Summary(0, 0);
        long pending = jdbc.sql("""
                SELECT COUNT(*) FROM conversation WHERE tenant_id = :tenant AND status = 'WAITING' AND department_id IN (:departments)
                """).param("tenant", actor.tenantId()).param("departments", actor.agentDepartments()).query(Long.class).single();
        long unread = jdbc.sql("""
                SELECT COUNT(*) FROM conversation c JOIN conversation_message m
                ON m.tenant_id = c.tenant_id AND m.conversation_id = c.id
                WHERE c.tenant_id = :tenant AND c.assignee_id = :actor AND c.status = 'ACTIVE'
                  AND c.department_id IN (:departments) AND m.customer_sender_id IS NOT NULL AND m.seq > c.staff_read_seq
                """).param("tenant", actor.tenantId()).param("actor", actor.id()).param("departments", actor.agentDepartments())
                .query(Long.class).single();
        return new Summary(pending, unread);
    }

    boolean advanceRead(Conversation chat, Actor actor, long seq) {
        String field = actor.customer() ? "customer_read_seq" : "staff_read_seq";
        return jdbc.sql("UPDATE conversation SET " + field + " = ? WHERE tenant_id = ? AND id = ? AND " + field + " < ?")
                .params(seq, chat.tenantId(), chat.id(), seq).update() == 1;
    }

    void change(Conversation chat, State state, String newOwner, LocalDateTime now) {
        var queuedAt = state == State.WAITING ? now : chat.queuedAt();
        var staffRead = state == State.ACTIVE ? chat.lastStaffSeq() : chat.staffReadSeq();
        jdbc.sql("""
                UPDATE conversation SET status = ?, assignee_id = ?, state_version = state_version + 1,
                staff_read_seq = ?, queued_at = ?, last_activity_at = ?, closed_at = ?
                WHERE tenant_id = ? AND id = ?
                """).params(state.name(), newOwner, staffRead, queuedAt, now, state == State.CLOSED ? now : null,
                chat.tenantId(), chat.id()).update();
    }

    Optional<ActionRecord> action(Actor actor, String id) {
        return jdbc.sql("SELECT * FROM conversation_action WHERE tenant_id = ? AND staff_id = ? AND action_id = ?")
                .params(actor.tenantId(), actor.id(), id).query((rs, row) -> new ActionRecord(rs.getString("conversation_id"),
                    Operation.valueOf(rs.getString("action_code")), rs.getLong("expected_version"),
                    rs.getObject("observed_customer_seq", Long.class), rs.getString("reason"))).optional();
    }

    void recordAction(Conversation chat, Actor staff, String id, Operation op, Long seen, String reason, String owner, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO conversation_action (tenant_id, staff_id, action_id, conversation_id, action_code,
                    expected_version, observed_customer_seq, reason, previous_assignee, new_assignee, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(chat.tenantId(), staff.id(), id, chat.id(), op.name(), chat.version(), seen, reason,
                chat.assigneeId(), owner, now).update();
    }

    void enqueue(Conversation chat, Long seq, LocalDateTime now) {
        jdbc.sql("INSERT INTO chat_outbox (tenant_id, conversation_id, message_seq, next_at) VALUES (?, ?, ?, ?)")
                .params(chat.tenantId(), chat.id(), seq, now).update();
    }

    String assigneeName(Conversation chat) {
        if (chat.assigneeId() == null) return null;
        return jdbc.sql("SELECT display_name FROM staff_account WHERE tenant_id = ? AND id = ?")
                .params(chat.tenantId(), chat.assigneeId()).query(String.class).optional().orElse(null);
    }

    List<ActionHistory> history(Conversation chat) {
        return jdbc.sql("""
                SELECT a.action_code, s.display_name, a.created_at, a.reason FROM conversation_action a
                JOIN staff_account s ON s.tenant_id = a.tenant_id AND s.id = a.staff_id
                WHERE a.tenant_id = ? AND a.conversation_id = ? ORDER BY a.expected_version DESC LIMIT 100
                """).params(chat.tenantId(), chat.id()).query((rs, row) -> new ActionHistory(
                Operation.valueOf(rs.getString("action_code")), rs.getString("display_name"),
                rs.getObject("created_at", LocalDateTime.class) + "Z", rs.getString("reason"))).list();
    }

    List<Outbox> ready(LocalDateTime now) {
        return jdbc.sql("SELECT * FROM chat_outbox WHERE next_at <= ? ORDER BY next_at, id LIMIT 50")
                .param(now).query((rs, row) -> new Outbox(rs.getLong("id"), rs.getLong("tenant_id"),
                        rs.getString("conversation_id"), rs.getObject("message_seq", Long.class), rs.getInt("attempts"))).list();
    }

    void delivered(long id) { jdbc.sql("DELETE FROM chat_outbox WHERE id = ?").param(id).update(); }

    void retry(long id, LocalDateTime next) {
        jdbc.sql("UPDATE chat_outbox SET attempts = attempts + 1, next_at = ? WHERE id = ?").params(next, id).update();
    }
}
