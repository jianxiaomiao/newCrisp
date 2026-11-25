package com.newcrisp.chat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.newcrisp.identity.IdentityError;
import org.springframework.http.HttpStatus;

final class ChatModels {
    private ChatModels() {}

    enum State { WAITING, ACTIVE, CLOSED }
    enum View { CURRENT, HISTORY, PENDING, MINE, CLOSED, DEPARTMENT }
    enum Operation { CLAIM, RETURN, TRANSFER, CLOSE }
    enum Activity { CLAIM, RETURN, TRANSFER, CLOSE, AUTO, AUTO_CLOSE }

    record Conversation(long tenantId, String id, String customerId, String departmentId, String assigneeId,
                        State state, long version, long lastSeq, long lastCustomerSeq, long lastStaffSeq,
                        long customerReadSeq, long staffReadSeq, LocalDateTime queuedAt, LocalDateTime lastActivity) {}

    record StoredMessage(String conversationId, long seq, boolean customer, boolean system, String text, LocalDateTime at,
                         String clientMessageId, boolean firstSend, AttachmentInfo attachment, byte[] attachmentSha) {
        StoredMessage(String conversationId, long seq, boolean customer, String text, LocalDateTime at,
                      String clientMessageId, boolean firstSend) {
            this(conversationId, seq, customer, false, text, at, clientMessageId, firstSend, null, null);
        }

        Message publicMessage() {
            String sender = system ? "SYSTEM" : customer ? "CUSTOMER" : "STAFF";
            return new Message(conversationId, Long.toString(seq), sender,
                    text, at + "Z", clientMessageId, attachment);
        }
    }
    record AutoClosePolicy(long tenantId, int minutes) {}

    record AttachmentInfo(String name, String type, int size) {}
    record Upload(AttachmentInfo info, byte[] content, byte[] sha) {}
    record FileContent(AttachmentInfo info, byte[] content) {}
    record Message(String conversationId, String seq, String sender, String text, String createdAt, String clientMessageId,
                   AttachmentInfo attachment) {}
    record MessagePage(List<Message> items, boolean hasMore) {}
    record Send(String clientMessageId, String text, String stateVersion) {}
    record Sent(Message message, boolean replayed) {}
    record Action(String actionId, String stateVersion, String observedCustomerSeq, String reason, String targetStaffId) {}
    record ActionRecord(String conversationId, Operation operation, long version, Long observedCustomerSeq, String reason,
                        String newAssignee) {}
    record ActionResult(String conversationId, State status, String stateVersion) {}
    record StaffOption(String id, String displayName, String email) {}
    record RoutingCandidate(String id, long accessVersion) {}
    record ActionHistory(Activity action, String operator, String occurredAt, String reason,
                         StaffOption previousAssignee, StaffOption newAssignee) {}
    record Read(String upToSeq, String stateVersion) {}
    record Summary(long pending, long unread) {}
    record CustomerInfo(String id, Long externalUserId, boolean guest, String displayName, String email,
                        String phone, String avatarUrl, Map<String, Object> attributes, String lastPageUrl) {}
    record ListItem(String id, State status, String stateVersion, String lastSeq, String lastCustomerSeq,
                    String readSeq, String timestamp, String queuedAt, String preview, long unread,
                    String customerName, String customerAvatar, boolean guest) {
        ListItem(String id, State status, String stateVersion, String lastSeq, String lastCustomerSeq,
                 String readSeq, String timestamp, String queuedAt, String preview, long unread) {
            this(id, status, stateVersion, lastSeq, lastCustomerSeq, readSeq, timestamp, queuedAt, preview, unread,
                    null, null, false);
        }
    }
    record ConversationPage(List<ListItem> items, String nextCursor) {}
    record Detail(String id, State status, String stateVersion, String lastSeq, String lastCustomerSeq,
                  String readSeq, String counterpartReadSeq, boolean canReply, boolean canReturn, boolean canTransfer,
                  boolean canClose, String assignee, Boolean customerOnline, Boolean assigneeOnline, String queuedAt,
                  CustomerInfo customer) {}
    record Cursor(LocalDateTime time, String id) {}
    record Outbox(long id, long tenantId, String conversationId, Long sequence, int attempts, Long assignmentVersion) {}

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
