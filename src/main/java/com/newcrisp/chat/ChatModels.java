package com.newcrisp.chat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.newcrisp.identity.IdentityError;
import org.springframework.http.HttpStatus;

final class ChatModels {
    private ChatModels() {}

    enum State { WAITING, ACTIVE, CLOSED }
    enum View { CURRENT, HISTORY, PENDING, MINE, CLOSED, DEPARTMENT }
    enum Operation { CLAIM, RETURN, CLOSE }

    record Conversation(long tenantId, String id, String customerId, String departmentId, String assigneeId,
                        State state, long version, long lastSeq, long lastCustomerSeq, long lastStaffSeq,
                        long customerReadSeq, long staffReadSeq, LocalDateTime queuedAt, LocalDateTime lastActivity) {}

    record StoredMessage(String conversationId, long seq, boolean customer, String text, LocalDateTime at,
                         String clientMessageId, boolean firstSend) {
        Message publicMessage() {
            return new Message(conversationId, Long.toString(seq), customer ? "CUSTOMER" : "STAFF",
                    text, at + "Z", clientMessageId);
        }
    }

    record Message(String conversationId, String seq, String sender, String text, String createdAt, String clientMessageId) {}
    record MessagePage(List<Message> items, boolean hasMore) {}
    record Send(String clientMessageId, String text, String stateVersion) {}
    record Sent(Message message, boolean replayed) {}
    record Action(String actionId, String stateVersion, String observedCustomerSeq, String reason) {}
    record ActionRecord(String conversationId, Operation operation, long version, Long observedCustomerSeq, String reason) {}
    record ActionResult(String conversationId, State status, String stateVersion) {}
    record ActionHistory(Operation action, String operator, String occurredAt, String reason) {}
    record Read(String upToSeq, String stateVersion) {}
    record Summary(long pending, long unread) {}
    record ListItem(String id, State status, String stateVersion, String lastSeq, String lastCustomerSeq,
                    String readSeq, String timestamp, String preview, long unread) {}
    record ConversationPage(List<ListItem> items, String nextCursor) {}
    record Detail(String id, State status, String stateVersion, String lastSeq, String lastCustomerSeq,
                  String readSeq, boolean canReply, boolean canReturn, boolean canClose,
                  String assignee, Boolean customerOnline) {}
    record Cursor(LocalDateTime time, String id) {}
    record Outbox(long id, long tenantId, String conversationId, Long sequence, int attempts) {}

    static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw invalid("INVALID_ID");
        }
        return UUID.fromString(value).toString();
    }

    static long number(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid("INVALID_SEQUENCE_OR_VERSION");
        try { return Long.parseLong(value); } catch (NumberFormatException error) {
            throw invalid("INVALID_SEQUENCE_OR_VERSION");
        }
    }

    static IdentityError invalid(String code) { return new IdentityError(HttpStatus.BAD_REQUEST, code); }
    static IdentityError conflict(String code) { return new IdentityError(HttpStatus.CONFLICT, code); }
    static IdentityError forbidden() { return new IdentityError(HttpStatus.FORBIDDEN, "CHAT_PERMISSION_REQUIRED"); }
}
