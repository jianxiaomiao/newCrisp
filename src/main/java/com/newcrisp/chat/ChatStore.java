package com.newcrisp.chat;

import static com.newcrisp.chat.ChatModels.*;

import com.newcrisp.identity.ChatIdentity.Actor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
class ChatStore {
    private static final TypeReference<Map<String, Object>> ATTR_TYPE = new TypeReference<>() {};
    private static final String DEPARTMENT_AGENTS = """
            SELECT a.id, a.display_name, a.email FROM staff_department_role r
            JOIN staff_account a ON a.tenant_id = r.tenant_id AND a.id = r.staff_id
            WHERE r.tenant_id = ? AND r.department_id = ? AND r.role_code = 'AGENT' AND a.enabled
            """;
    private final JdbcClient jdbc;
    private final CustomerUnread customerUnread;
    private final ObjectMapper json;
    private final AttachmentStorage storage;

    ChatStore(JdbcClient jdbc, CustomerUnread customerUnread, ObjectMapper json, AttachmentStorage storage) {
        this.jdbc = jdbc;
        this.customerUnread = customerUnread;
        this.json = json;
        this.storage = storage;
    }

    boolean lockCustomer(Actor actor) {
        var subject = jdbc.sql("SELECT guest_site_id FROM customer WHERE tenant_id = ? AND id = ? FOR UPDATE")
                .params(actor.tenantId(), actor.id()).query((rs, row) -> new CustomerLock(rs.getString(1))).optional();
        if (subject.isEmpty()) return false;
        if (subject.get().site() == null) return true;
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM guest_resume g JOIN guest_site s
                    ON s.tenant_id = g.tenant_id AND s.id = g.site_id
                    WHERE g.tenant_id = ? AND g.customer_id = ? AND g.linked_at IS NULL
                      AND s.enabled AND g.expires_at > UTC_TIMESTAMP(3))
                """).params(actor.tenantId(), actor.id()).query(Boolean.class).single();
    }

    private record CustomerLock(String site) {}

    boolean lockStaffAccess(Actor actor) {
        return lockActiveStaff(actor.tenantId(), actor.id())
                .filter(version -> version == actor.accessVersion()).isPresent();
    }

    private Optional<Long> lockActiveStaff(long tenant, String staffId) {
        return jdbc.sql("""
                SELECT access_version FROM staff_account WHERE tenant_id = ? AND id = ? AND enabled FOR SHARE
                """).params(tenant, staffId).query(Long.class).optional();
    }

    void lockRouting(long tenant) {
        jdbc.sql("SELECT tenant_id FROM chat_routing_lock WHERE tenant_id = ? FOR UPDATE")
                .param(tenant).query(Long.class).single();
    }

    List<RoutingCandidate> onlineCandidates(Conversation chat, Set<String> sessions, LocalDateTime now) {
        return jdbc.sql("""
                SELECT a.id, a.access_version FROM staff_department_role r
                JOIN staff_account a ON a.tenant_id = r.tenant_id AND a.id = r.staff_id
                WHERE r.tenant_id = :tenant AND r.department_id = :department AND r.role_code = 'AGENT'
                  AND a.enabled AND a.accepting
                  AND EXISTS (SELECT 1 FROM staff_session s WHERE s.tenant_id = a.tenant_id AND s.staff_id = a.id
                              AND s.sid IN (:sessions) AND s.revoked_at IS NULL AND s.expires_at > :now)
                  AND (SELECT COUNT(*) FROM conversation c WHERE c.tenant_id = a.tenant_id
                       AND c.assignee_id = a.id AND c.status = 'ACTIVE') <
                      COALESCE((SELECT assignment_limit FROM tenant WHERE id = :tenant), 9223372036854775807)
                ORDER BY (SELECT COUNT(*) FROM conversation c
                          WHERE c.tenant_id = a.tenant_id AND c.assignee_id = a.id AND c.status = 'ACTIVE'), a.id
                """).param("tenant", chat.tenantId()).param("department", chat.departmentId())
                .param("sessions", sessions).param("now", now)
                .query((rs, row) -> new RoutingCandidate(rs.getString("id"), rs.getLong("access_version"))).list();
    }

    boolean lockRoutingCandidate(long tenant, RoutingCandidate candidate) {
        return lockActiveStaff(tenant, candidate.id()).filter(version -> version == candidate.accessVersion()).isPresent();
    }

    void recordAutoAssignment(Conversation chat, String staffId, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO conversation_auto_assignment (tenant_id, conversation_id, staff_id, assigned_at, expected_version)
                VALUES (?, ?, ?, ?, ?)
                """).params(chat.tenantId(), chat.id(), staffId, now, chat.version()).update();
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
        if (actor.customer()) {
            jdbc.sql("UPDATE conversation SET last_seq = ?, " + senderSequence + " = ?, last_activity_at = ? WHERE tenant_id = ? AND id = ?")
                    .params(seq, seq, now, chat.tenantId(), chat.id()).update();
        } else {
            jdbc.sql("""
                    UPDATE conversation SET last_seq = ?, last_staff_seq = ?, last_activity_at = ?,
                        first_staff_reply_at = COALESCE(first_staff_reply_at, ?)
                    WHERE tenant_id = ? AND id = ?
                    """).params(seq, seq, now, now, chat.tenantId(), chat.id()).update();
        }
        return message(chat, seq).orElseThrow();
    }

    StoredMessage attach(Conversation chat, StoredMessage message, Upload upload) {
        var info = upload.info();
        String storageKey = storage.store(chat.tenantId(), chat.id(), message.seq(), upload);
        jdbc.sql("""
                UPDATE conversation_message
                SET attachment_name = ?, attachment_type = ?, attachment_size = ?, attachment_sha = ?, attachment_storage_key = ?
                WHERE tenant_id = ? AND conversation_id = ? AND seq = ?
                """).params(info.name(), info.type(), info.size(), upload.sha(), storageKey,
                chat.tenantId(), chat.id(), message.seq()).update();
        return message(chat, message.seq()).orElseThrow();
    }

    Optional<FileContent> attachment(Conversation chat, long seq) {
        var meta = jdbc.sql("""
                SELECT attachment_name, attachment_type, attachment_size, attachment_storage_key
                FROM conversation_message
                WHERE tenant_id = ? AND conversation_id = ? AND seq = ? AND attachment_name IS NOT NULL
                """).params(chat.tenantId(), chat.id(), seq)
                .query((rs, row) -> new AttachmentMeta(attachmentInfo(rs), rs.getString("attachment_storage_key"))).optional();
        if (meta.isEmpty()) return Optional.empty();
        return storage.load(chat.tenantId(), chat.id(), seq, meta.get().storageKey())
                .map(bytes -> new FileContent(meta.get().info(), bytes));
    }

    private record AttachmentMeta(AttachmentInfo info, String storageKey) {}

    List<StoredMessage> messages(Conversation chat, Long after, Long before, int limit) {
        String order = after != null ? " ASC" : " DESC";
        String boundary = after != null ? " AND seq > :seq" : before != null ? " AND seq < :seq" : "";
        var statement = jdbc.sql("SELECT * FROM conversation_message WHERE tenant_id = :tenant AND conversation_id = :id"
                + boundary + " ORDER BY seq" + order + " LIMIT :limit")
                .param("tenant", chat.tenantId()).param("id", chat.id()).param("limit", limit);
        if (after != null || before != null) statement.param("seq", after != null ? after : before);
        return statement.query(ChatStore::message).list();
    }

    StoredMessage appendSystem(Conversation chat, long seq, String text, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO conversation_message (tenant_id, conversation_id, seq, customer_sender_id, staff_sender_id,
                    system_message, client_message_id, first_send, body, created_at)
                VALUES (?, ?, ?, NULL, NULL, TRUE, ?, FALSE, ?, ?)
                """).params(chat.tenantId(), chat.id(), seq, java.util.UUID.randomUUID().toString(), text, now).update();
        jdbc.sql("UPDATE conversation SET last_seq = ?, last_activity_at = ? WHERE tenant_id = ? AND id = ?")
                .params(seq, now, chat.tenantId(), chat.id()).update();
        return message(chat, seq).orElseThrow();
    }

    private static StoredMessage message(ResultSet rs, int row) throws SQLException {
        return new StoredMessage(rs.getString("conversation_id"), rs.getLong("seq"),
                rs.getString("customer_sender_id") != null, rs.getBoolean("system_message"), rs.getString("body"),
                rs.getObject("created_at", LocalDateTime.class), rs.getString("client_message_id"), rs.getBoolean("first_send"),
                attachmentInfo(rs), rs.getBytes("attachment_sha"));
    }

    private static AttachmentInfo attachmentInfo(ResultSet rs) throws SQLException {
        String name = rs.getString("attachment_name");
        return name == null ? null : new AttachmentInfo(name, rs.getString("attachment_type"), rs.getInt("attachment_size"));
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
        var statement = jdbc.sql("SELECT c.id, c.status, c.state_version, c.last_seq, c.last_customer_seq, c.queued_at, "
                + readSeq + " AS read_seq, " + time + " AS sort_time, " + unread + " AS unread, "
                + "LEFT(m.body, 100) AS preview, u.display_name AS customer_name, u.avatar_url AS customer_avatar, "
                + "u.guest_site_id IS NOT NULL AS is_guest "
                + "FROM conversation c JOIN customer u ON u.tenant_id = c.tenant_id AND u.id = c.customer_id "
                + "LEFT JOIN conversation_message m "
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
                rs.getObject("sort_time", LocalDateTime.class) + "Z", rs.getObject("queued_at", LocalDateTime.class) + "Z",
                rs.getString("preview"), rs.getLong("unread"),
                rs.getString("customer_name"), rs.getString("customer_avatar"), rs.getBoolean("is_guest"))).list();
    }

    CustomerInfo customerInfo(long tenantId, String customerId) {
        return jdbc.sql("""
                SELECT id, external_user_id, guest_site_id, display_name, email, phone, avatar_url, attributes, last_page_url
                FROM customer WHERE tenant_id = ? AND id = ?
                """).params(tenantId, customerId).query((rs, row) -> {
            String attrRaw = rs.getString("attributes");
            Map<String, Object> attrs = null;
            if (attrRaw != null && !attrRaw.isBlank()) {
                try { attrs = json.readValue(attrRaw, ATTR_TYPE); } catch (RuntimeException ignored) {}
            }
            Long extId = rs.getObject("external_user_id", Long.class);
            return new CustomerInfo(rs.getString("id"), extId, rs.getString("guest_site_id") != null,
                    rs.getString("display_name"), rs.getString("email"), rs.getString("phone"),
                    rs.getString("avatar_url"), attrs, rs.getString("last_page_url"));
        }).optional().orElse(null);
    }

    List<ListItem> customerHistory(Actor staff, String customerId, Set<String> departments) {
        if (departments.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT c.id, c.status, c.state_version, c.last_seq, c.last_customer_seq, c.queued_at,
                       c.staff_read_seq AS read_seq, c.last_activity_at AS sort_time,
                       LEFT(m.body, 100) AS preview, u.display_name AS customer_name,
                       u.avatar_url AS customer_avatar, u.guest_site_id IS NOT NULL AS is_guest
                FROM conversation c
                JOIN customer u ON u.tenant_id = c.tenant_id AND u.id = c.customer_id
                LEFT JOIN conversation_message m ON m.tenant_id = c.tenant_id AND m.conversation_id = c.id AND m.seq = c.last_seq
                WHERE c.tenant_id = :tenant AND c.customer_id = :customer AND c.department_id IN (:departments)
                  AND (c.assignee_id = :actor OR c.department_id IN (:supervisorDepts))
                ORDER BY c.last_activity_at DESC, c.id DESC LIMIT 20
                """).param("tenant", staff.tenantId()).param("customer", customerId)
                .param("departments", departments).param("actor", staff.id())
                .param("supervisorDepts", staff.supervisorDepartments().isEmpty() ? Set.of("__none__") : staff.supervisorDepartments())
                .query((rs, row) -> new ListItem(rs.getString("id"), State.valueOf(rs.getString("status")),
                        Long.toString(rs.getLong("state_version")), Long.toString(rs.getLong("last_seq")),
                        Long.toString(rs.getLong("last_customer_seq")), Long.toString(rs.getLong("read_seq")),
                        rs.getObject("sort_time", LocalDateTime.class) + "Z", rs.getObject("queued_at", LocalDateTime.class) + "Z",
                        rs.getString("preview"), 0, rs.getString("customer_name"),
                        rs.getString("customer_avatar"), rs.getBoolean("is_guest"))).list();
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

    List<StaffOption> transferCandidates(Conversation chat) {
        return jdbc.sql(DEPARTMENT_AGENTS + " AND a.id <> ? ORDER BY a.display_name, a.email")
                .params(chat.tenantId(), chat.departmentId(), chat.assigneeId())
                .query((rs, row) -> new StaffOption(rs.getString("id"), rs.getString("display_name"), rs.getString("email"))).list();
    }

    boolean lockTransferTarget(Conversation chat, String staffId) {
        // Role edits lock this same account before deleting or inserting department grants.
        if (lockActiveStaff(chat.tenantId(), staffId).isEmpty()) return false;
        return jdbc.sql("""
                SELECT staff_id FROM staff_department_role
                WHERE tenant_id = ? AND staff_id = ? AND department_id = ? AND role_code = 'AGENT'
                """).params(chat.tenantId(), staffId, chat.departmentId()).query(String.class).optional().isPresent();
    }

    Optional<ActionRecord> action(Actor actor, String id) {
        return jdbc.sql("SELECT * FROM conversation_action WHERE tenant_id = ? AND staff_id = ? AND action_id = ?")
                .params(actor.tenantId(), actor.id(), id).query((rs, row) -> new ActionRecord(rs.getString("conversation_id"),
                    Operation.valueOf(rs.getString("action_code")), rs.getLong("expected_version"),
                    rs.getObject("observed_customer_seq", Long.class), rs.getString("reason"), rs.getString("new_assignee"))).optional();
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

    void enqueueAssignment(Conversation chat, LocalDateTime now) {
        jdbc.sql("INSERT INTO chat_outbox (tenant_id, conversation_id, assignment_version, next_at) VALUES (?, ?, ?, ?)")
                .params(chat.tenantId(), chat.id(), chat.version() + 1, now).update();
    }

    String assigneeName(Conversation chat) {
        if (chat.assigneeId() == null) return null;
        return jdbc.sql("SELECT display_name FROM staff_account WHERE tenant_id = ? AND id = ?")
                .params(chat.tenantId(), chat.assigneeId()).query(String.class).optional().orElse(null);
    }

    List<ActionHistory> history(Conversation chat) {
        return jdbc.sql("""
                WITH activity AS (
                    SELECT tenant_id, action_code, staff_id AS operator_id, created_at, reason,
                           previous_assignee, new_assignee, expected_version
                    FROM conversation_action WHERE tenant_id = :tenant AND conversation_id = :id
                    UNION ALL
                    SELECT tenant_id, 'AUTO', NULL, assigned_at, NULL, NULL, staff_id, expected_version
                    FROM conversation_auto_assignment WHERE tenant_id = :tenant AND conversation_id = :id
                    UNION ALL
                    SELECT tenant_id, 'AUTO_CLOSE', NULL, closed_at, reason, NULL, NULL, expected_version
                    FROM conversation_auto_close WHERE tenant_id = :tenant AND conversation_id = :id
                )
                SELECT a.action_code, COALESCE(s.display_name, '系统') AS operator_name, a.created_at, a.reason,
                       p.id AS previous_id, p.display_name AS previous_name, p.email AS previous_email,
                       n.id AS new_id, n.display_name AS new_name, n.email AS new_email
                FROM activity a
                LEFT JOIN staff_account s ON s.tenant_id = a.tenant_id AND s.id = a.operator_id
                LEFT JOIN staff_account p ON p.tenant_id = a.tenant_id AND p.id = a.previous_assignee AND a.action_code = 'TRANSFER'
                LEFT JOIN staff_account n ON n.tenant_id = a.tenant_id AND n.id = a.new_assignee AND a.action_code IN ('TRANSFER', 'AUTO')
                ORDER BY a.expected_version DESC LIMIT 100
                """).param("tenant", chat.tenantId()).param("id", chat.id()).query((rs, row) -> new ActionHistory(
                Activity.valueOf(rs.getString("action_code")), rs.getString("operator_name"),
                rs.getObject("created_at", LocalDateTime.class) + "Z", rs.getString("reason"),
                historyAssignee(rs, "previous"), historyAssignee(rs, "new"))).list();
    }

    List<Long> tenantsWithWaitingDispatch() {
        return jdbc.sql("""
                SELECT id FROM tenant t WHERE dispatch_waiting AND status = 'ACTIVE'
                AND EXISTS (SELECT 1 FROM conversation c WHERE c.tenant_id = t.id AND c.status = 'WAITING')
                ORDER BY id
                """).query(Long.class).list();
    }

    boolean hasDispatchQueue(long tenant, String department) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM tenant t WHERE t.id = ? AND t.dispatch_waiting
                    AND EXISTS (SELECT 1 FROM conversation c WHERE c.tenant_id = t.id
                        AND c.department_id = ? AND c.status = 'WAITING'))
                """).params(tenant, department).query(Boolean.class).single();
    }

    List<String> waitingDepartments(long tenant) {
        return jdbc.sql("""
                SELECT department_id FROM conversation WHERE tenant_id = ? AND status = 'WAITING'
                GROUP BY department_id ORDER BY MIN(queued_at), department_id
                """).param(tenant).query(String.class).list();
    }

    Optional<Conversation> nextWaiting(long tenant, String department) {
        return jdbc.sql("""
                SELECT * FROM conversation WHERE tenant_id = ? AND department_id = ? AND status = 'WAITING'
                ORDER BY queued_at, id LIMIT 1 FOR UPDATE
                """).params(tenant, department).query(ChatStore::conversation).optional();
    }

    private static StaffOption historyAssignee(ResultSet rs, String prefix) throws SQLException {
        String id = rs.getString(prefix + "_id");
        return id == null ? null : new StaffOption(id, rs.getString(prefix + "_name"), rs.getString(prefix + "_email"));
    }

    List<AutoClosePolicy> tenantsWithAutoClose() {
        return jdbc.sql("""
                SELECT id, auto_close_minutes FROM tenant
                WHERE status = 'ACTIVE' AND auto_close_minutes IS NOT NULL AND auto_close_minutes >= 5
                ORDER BY id
                """).query((rs, row) -> new AutoClosePolicy(rs.getLong(1), rs.getInt(2))).list();
    }

    List<Conversation> idleConversations(long tenantId, LocalDateTime cutoff, int limit) {
        return jdbc.sql("""
                SELECT * FROM conversation
                WHERE tenant_id = ? AND status = 'ACTIVE' AND last_staff_seq > last_customer_seq AND last_activity_at < ?
                ORDER BY last_activity_at ASC, id ASC LIMIT ? FOR UPDATE
                """).params(tenantId, cutoff, limit).query(ChatStore::conversation).list();
    }

    void recordAutoClose(Conversation chat, String reason, LocalDateTime now) {
        jdbc.sql("""
                INSERT INTO conversation_auto_close (tenant_id, conversation_id, expected_version, reason, closed_at)
                VALUES (?, ?, ?, ?, ?)
                """).params(chat.tenantId(), chat.id(), chat.version(), reason, now).update();
    }

    List<Outbox> ready(LocalDateTime now) {
        return jdbc.sql("SELECT * FROM chat_outbox WHERE next_at <= ? ORDER BY next_at, id LIMIT 50")
                .param(now).query((rs, row) -> new Outbox(rs.getLong("id"), rs.getLong("tenant_id"),
                        rs.getString("conversation_id"), rs.getObject("message_seq", Long.class), rs.getInt("attempts"),
                        rs.getObject("assignment_version", Long.class))).list();
    }

    void delivered(long id) { jdbc.sql("DELETE FROM chat_outbox WHERE id = ?").param(id).update(); }

    void retry(long id, LocalDateTime next) {
        jdbc.sql("UPDATE chat_outbox SET attempts = attempts + 1, next_at = ? WHERE id = ?").params(next, id).update();
    }
}
