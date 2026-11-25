package com.newcrisp.chat;

import static com.newcrisp.chat.ChatModels.*;

import com.newcrisp.identity.ChatIdentity;
import com.newcrisp.identity.ChatIdentity.Actor;
import com.newcrisp.identity.IdentityError;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatService {
    private final ChatStore store;
    private final Clock clock;
    private final ChatPresence presence;
    private final ServiceHours hours;

    ChatService(ChatStore store, Clock clock, ChatPresence presence, ServiceHours hours) {
        this.store = store;
        this.clock = clock;
        this.presence = presence;
        this.hours = hours;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    Sent send(Actor actor, String id, Send input) {
        return send(actor, id, input, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    Sent sendAttachment(Actor actor, String id, Send input, Upload upload) {
        return send(actor, id, input, upload);
    }

    private Sent send(Actor actor, String id, Send input, Upload upload) {
        String key = uuid(input.clientMessageId());
        String text = input.text() == null ? "" : input.text().strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > 4_000) throw invalid("INVALID_MESSAGE");
        boolean first = id == null;
        if (first && !actor.customer()) throw forbidden();
        if (!actor.customer() && input.stateVersion() == null) throw invalid("INVALID_SEQUENCE_OR_VERSION");
        String requested = first ? null : uuid(id);
        LocalDateTime now = LocalDateTime.now(clock);
        if (actor.customer() && !store.lockCustomer(actor)) throw IdentityError.unauthorized();
        requireStaffAccess(actor);

        Conversation conversation = first ? null : require(actor, requested, true);
        if (conversation != null && !canRead(actor, conversation)) throw IdentityError.notFound();
        var prior = store.replay(actor, key);
        if (prior.isPresent()) return replay(actor, prior.get(), requested, first, text, conversation, upload);

        if (first) {
            conversation = store.active(actor).orElse(null);
            if (conversation == null) {
                store.lockRouting(actor.tenantId());
                boolean olderWaiting = store.hasDispatchQueue(actor.tenantId(), actor.defaultDepartment());
                var newId = UUID.randomUUID().toString();
                store.create(actor, newId, now);
                conversation = require(actor, newId, true);
                if (!olderWaiting && hours.status(actor.tenantId(), clock.instant()).open()) assignToOnlineAgent(conversation, now);
            }
        }
        if (conversation.state() == State.CLOSED) throw conflict("CONVERSATION_CLOSED");
        if (!actor.customer()) {
            if (!owns(actor, conversation)) throw forbidden();
            checkVersion(conversation, number(input.stateVersion()));
        }
        try {
            var message = store.append(conversation, actor, key, text, first, now);
            if (upload != null) message = store.attach(conversation, message, upload);
            store.enqueue(conversation, message.seq(), now);
            return new Sent(message.publicMessage(), false);
        } catch (DuplicateKeyException conflict) {
            // Same-room writes are serialized. A duplicate here is a conflicting use in another conversation.
            throw conflict("MESSAGE_ID_CONFLICT");
        }
    }

    private boolean assignToOnlineAgent(Conversation conversation, LocalDateTime assignedAt) {
        long tenant = conversation.tenantId();
        String department = conversation.departmentId();
        var sessions = presence.onlineAgentSessions(tenant, department);
        if (sessions.isEmpty()) return false;
        for (var candidate : store.onlineCandidates(conversation, sessions, LocalDateTime.now(clock))) {
            if (!store.lockRoutingCandidate(tenant, candidate) || !presence.hasOnlineAgent(tenant, department, candidate)) continue;
            store.change(conversation, State.ACTIVE, candidate.id(), assignedAt);
            store.recordAutoAssignment(conversation, candidate.id(), assignedAt);
            store.enqueueAssignment(conversation, assignedAt);
            return true;
        }
        return false;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void fillQueue(long tenant) {
        store.lockRouting(tenant);
        var settings = hours.get(tenant);
        if (!settings.fillQueue() || !ServiceHours.status(settings, clock.instant()).open()) return;
        int remaining = 25;
        for (String department : store.waitingDepartments(tenant)) {
            if (remaining == 0) return;
            if (presence.onlineAgentSessions(tenant, department).isEmpty()) continue;
            while (remaining > 0) {
                var next = store.nextWaiting(tenant, department);
                if (next.isEmpty()) break;
                var now = LocalDateTime.now(clock);
                if (!assignToOnlineAgent(next.get(), now)) break;
                remaining--;
            }
        }
    }

    private Sent replay(Actor actor, StoredMessage prior, String requested, boolean first, String text, Conversation locked, Upload upload) {
        if (prior.firstSend() != first || (!first && !prior.conversationId().equals(requested)) || !prior.text().equals(text)
                || !Arrays.equals(prior.attachmentSha(), upload == null ? null : upload.sha())) {
            throw conflict("MESSAGE_ID_CONFLICT");
        }
        Conversation original = locked == null ? require(actor, prior.conversationId(), false) : locked;
        if (!canRead(actor, original)) throw IdentityError.notFound();
        return new Sent(prior.publicMessage(), true);
    }

    @Transactional(readOnly = true)
    FileContent attachment(Actor actor, String id, String sequence) {
        return store.attachment(view(actor, id), number(sequence)).orElseThrow(IdentityError::notFound);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    ActionResult action(Actor actor, String id, Operation op, Action input) {
        if (actor.customer()) throw forbidden();
        String key = uuid(input.actionId());
        long version = number(input.stateVersion());
        Long seen = op == Operation.CLOSE ? number(input.observedCustomerSeq()) : null;
        String target = op == Operation.TRANSFER ? uuid(input.targetStaffId()) : null;
        String reason = input.reason() == null || input.reason().isBlank() ? null : input.reason().strip();
        if (reason != null && (op != Operation.RETURN || reason.codePointCount(0, reason.length()) > 400)) {
            throw invalid("INVALID_ACTION_REASON");
        }
        if ((op != Operation.CLOSE && input.observedCustomerSeq() != null)
                || (op != Operation.TRANSFER && input.targetStaffId() != null)) throw invalid("INVALID_ACTION");
        // Ownership changes share the same lock as automatic routing before taking a conversation lock.
        store.lockRouting(actor.tenantId());
        requireStaffAccess(actor);
        Conversation chat = require(actor, uuid(id), true);
        if (op == Operation.RETURN || op == Operation.TRANSFER) requireSupervisor(actor, chat);
        else if (!actor.agentDepartments().contains(chat.departmentId())) throw IdentityError.notFound();
        var prior = store.action(actor, key);
        if (prior.isPresent()) {
            var action = prior.get();
            if (!action.conversationId().equals(chat.id()) || action.operation() != op || action.version() != version
                    || !Objects.equals(action.observedCustomerSeq(), seen) || !Objects.equals(action.reason(), reason)
                    || (op == Operation.TRANSFER && !Objects.equals(action.newAssignee(), target))) {
                throw conflict("ACTION_ID_CONFLICT");
            }
            return result(chat, op, version);
        }

        checkVersion(chat, version);
        String owner = switch (op) {
            case CLAIM -> {
                if (chat.state() != State.WAITING) throw conflict("ALREADY_CLAIMED");
                yield actor.id();
            }
            case RETURN -> {
                requireActive(chat);
                yield null;
            }
            case TRANSFER -> {
                requireActive(chat);
                if (target.equals(chat.assigneeId())) throw conflict("ALREADY_ASSIGNED");
                if (!store.lockTransferTarget(chat, target)) throw IdentityError.notFound();
                yield target;
            }
            case CLOSE -> {
                if (chat.state() != State.ACTIVE || !owns(actor, chat)) throw IdentityError.notFound();
                if (seen != chat.lastCustomerSeq() || chat.staffReadSeq() < chat.lastCustomerSeq()) {
                    throw conflict("NEW_CUSTOMER_MESSAGES");
                }
                yield actor.id();
            }
        };
        var time = LocalDateTime.now(clock);
        try {
            store.recordAction(chat, actor, key, op, seen, reason, owner, time);
            store.change(chat, resultState(op), owner, time);
            if (op == Operation.CLAIM || op == Operation.TRANSFER) store.enqueueAssignment(chat, time);
            else store.enqueue(chat, null, time);
            return result(chat, op, version);
        } catch (DuplicateKeyException conflict) {
            throw conflict("ACTION_ID_CONFLICT");
        }
    }

    List<StaffOption> transferCandidates(Actor actor, String id) {
        var chat = require(actor, uuid(id), false);
        requireSupervisor(actor, chat);
        requireActive(chat);
        return store.transferCandidates(chat);
    }

    private static void requireSupervisor(Actor actor, Conversation chat) {
        if (!actor.supervisorDepartments().contains(chat.departmentId())) throw IdentityError.notFound();
    }

    private static void requireActive(Conversation chat) {
        if (chat.state() != State.ACTIVE) throw conflict("INVALID_CONVERSATION_STATE");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    void read(Actor actor, String id, Read input) {
        requireStaffAccess(actor);
        var chat = require(actor, uuid(id), true);
        if (!canRead(actor, chat)) throw IdentityError.notFound();
        if (!actor.customer()) {
            if (!owns(actor, chat) || chat.state() != State.ACTIVE) throw forbidden();
            checkVersion(chat, number(input.stateVersion()));
        }
        long seq = number(input.upToSeq());
        if (seq > chat.lastSeq()) throw invalid("INVALID_READ_POSITION");
        if (store.advanceRead(chat, actor, seq)) store.enqueue(chat, null, LocalDateTime.now(clock));
    }

    ConversationPage list(Actor actor, View view, String cursor, int limit) {
        boolean customerView = view == View.CURRENT || view == View.HISTORY;
        if (customerView != actor.customer()) throw forbidden();
        Set<String> departments = view == View.DEPARTMENT ? actor.supervisorDepartments() : actor.agentDepartments();
        if (!customerView && departments.isEmpty()) return new ConversationPage(java.util.List.of(), null);
        var rows = store.list(actor, view, departments, decode(cursor, view), pageSize(limit) + 1);
        boolean more = rows.size() > limit;
        var items = more ? rows.subList(0, limit) : rows;
        String next = more ? encode(items.getLast(), view) : null;
        return new ConversationPage(java.util.List.copyOf(items), next);
    }

    Summary summary(Actor actor) { return store.summary(actor); }

    @Transactional(readOnly = true)
    MessagePage messages(Actor actor, String id, String after, String before, int limit) {
        var chat = require(actor, uuid(id), false);
        if (!canRead(actor, chat)) throw IdentityError.notFound();
        if (after != null && before != null) throw invalid("AMBIGUOUS_MESSAGE_CURSOR");
        var rows = store.messages(chat, after == null ? null : number(after), before == null ? null : number(before),
                pageSize(limit) + 1);
        boolean more = rows.size() > limit;
        var result = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        if (after == null) Collections.reverse(result);
        return new MessagePage(result.stream().map(StoredMessage::publicMessage).toList(), more);
    }

    Conversation view(Actor actor, String id) {
        var chat = require(actor, uuid(id), false);
        if (!canRead(actor, chat)) throw IdentityError.notFound();
        return chat;
    }

    String assigneeName(Conversation conversation) { return store.assigneeName(conversation); }

    @Transactional(readOnly = true)
    java.util.List<ActionHistory> history(Actor actor, String id) {
        if (actor.customer()) throw IdentityError.notFound();
        return store.history(view(actor, id));
    }

    static boolean canRead(Actor actor, Conversation chat) {
        if (actor.tenantId() != chat.tenantId()) return false;
        return actor.customer() ? actor.id().equals(chat.customerId())
                : owns(actor, chat) || actor.supervisorDepartments().contains(chat.departmentId());
    }

    static boolean owns(Actor staff, Conversation chat) {
        return !staff.customer() && staff.id().equals(chat.assigneeId()) && staff.agentDepartments().contains(chat.departmentId());
    }

    private Conversation require(Actor actor, String id, boolean lock) {
        return store.find(actor.tenantId(), id, lock).orElseThrow(IdentityError::notFound);
    }

    private void requireStaffAccess(Actor actor) {
        if (!actor.customer() && !store.lockStaffAccess(actor)) throw ChatIdentity.accessChanged();
    }

    private static void checkVersion(Conversation chat, long expected) {
        if (chat.version() != expected) throw conflict("STATE_VERSION_CHANGED");
    }

    private static State resultState(Operation op) {
        return switch (op) {
            case CLAIM, TRANSFER -> State.ACTIVE;
            case RETURN -> State.WAITING;
            case CLOSE -> State.CLOSED;
        };
    }

    private static ActionResult result(Conversation chat, Operation op, long version) {
        return new ActionResult(chat.id(), resultState(op), Long.toString(version + 1));
    }

    private static int pageSize(int value) {
        if (value < 1 || value > 100) throw invalid("INVALID_PAGE_SIZE");
        return value;
    }

    private static String encode(ListItem item, View view) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (view + "|" + item.timestamp() + "|" + item.id()).getBytes(StandardCharsets.US_ASCII));
    }

    private static Cursor decode(String value, View view) {
        if (value == null) return null;
        try {
            if (value.length() > 200) throw invalid("INVALID_CURSOR");
            var parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split("\\|", -1);
            if (parts.length != 3 || !view.name().equals(parts[0]) || !parts[1].endsWith("Z")) throw invalid("INVALID_CURSOR");
            return new Cursor(LocalDateTime.parse(parts[1].substring(0, parts[1].length() - 1)), uuid(parts[2]));
        } catch (IllegalArgumentException | java.time.DateTimeException error) {
            throw invalid("INVALID_CURSOR");
        }
    }
}
