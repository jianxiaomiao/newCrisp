package com.newcrisp.chat;

import static com.newcrisp.chat.ChatModels.*;

import com.newcrisp.identity.ChatIdentity;
import com.newcrisp.identity.ChatIdentity.Actor;
import com.newcrisp.identity.ChatIdentity.Side;
import com.newcrisp.identity.ChatIdentity.SocketAccess;
import com.newcrisp.identity.IdentityError;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class ChatWebSocket extends TextWebSocketHandler {
    static final String SIDE = ChatWebSocket.class.getName() + ".side";
    private static final CloseStatus ACCESS_CHANGED = new CloseStatus(4003, "Staff access changed");
    private static final CloseStatus STAFF_DISABLED = new CloseStatus(4004, "Staff disabled");
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocket.class);
    private final ConcurrentMap<String, Peer> peers = new ConcurrentHashMap<>();
    private final Semaphore awaitingAuth = new Semaphore(128);
    private final ChatIdentity identity;
    private final ChatService chat;
    private final ChatStore store;
    private final ChatPresence presence;
    private final ObjectMapper json;
    private final Clock clock;

    ChatWebSocket(ChatIdentity identity, ChatService chat, ChatStore store, ChatPresence presence, ObjectMapper json, Clock clock) {
        this.identity = identity;
        this.chat = chat;
        this.store = store;
        this.presence = presence;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!awaitingAuth.tryAcquire()) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.setTextMessageSizeLimit(32 * 1024);
        Peer peer = new Peer(new ConcurrentWebSocketSessionDecorator(session, 2_000, 64 * 1024),
                (Side) session.getAttributes().get(SIDE), clock.instant().plusSeconds(5));
        peers.put(session.getId(), peer);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Peer peer = peers.get(session.getId());
        if (peer == null) return;
        String requestId = null;
        try {
            ClientFrame frame = json.readValue(message.getPayload(), ClientFrame.class);
            if (frame == null || frame.type() == null) throw invalid("INVALID_FRAME");
            if (peer.actor == null) {
                authenticate(peer, frame);
                return;
            }
            if ("pong".equals(frame.type())) {
                Actor actor = refresh(peer);
                if (peer.confirm(frame.nonce()) && peers.get(session.getId()) == peer) {
                    confirmPresence(peer, actor);
                }
                return;
            }
            requestId = uuid(frame.requestId());
            Actor actor = refresh(peer);
            String conversationId = switch (frame.type()) {
                case "conversation.start" -> {
                    if (frame.conversationId() != null) throw invalid("INVALID_FRAME");
                    yield null;
                }
                case "message.send" -> uuid(frame.conversationId());
                default -> throw invalid("UNKNOWN_FRAME_TYPE");
            };
            Sent saved = chat.send(actor, conversationId, new Send(frame.clientMessageId(), frame.text(), frame.stateVersion()));
            send(peer, new ServerFrame("message.ack", requestId, saved));
        } catch (IdentityError error) {
            send(peer, new ServerFrame("error", requestId, new Failure(error.code, error.status.value())));
            if (!closeForAccessError(peer, error) && peer.actor == null) drop(peer, true);
        } catch (JacksonException invalidJson) {
            send(peer, new ServerFrame("error", requestId, new Failure("INVALID_FRAME", 400)));
            if (peer.actor == null) drop(peer, true);
        } catch (RuntimeException error) {
            log.warn("Chat WebSocket command failed");
            send(peer, new ServerFrame("error", requestId, new Failure("SEND_RESULT_UNKNOWN", 503)));
            if (peer.actor == null) drop(peer, false);
        }
    }

    private void authenticate(Peer peer, ClientFrame frame) {
        if (!"auth".equals(frame.type()) || !clock.instant().isBefore(peer.authBy)) throw IdentityError.unauthorized();
        peer.access = identity.redeemSocket(peer.side, frame.ticket());
        Actor actor = identity.authenticate(peer.access);
        if (!clock.instant().isBefore(peer.authBy) || peers.get(peer.socket.getId()) != peer) throw IdentityError.unauthorized();
        peer.actor = actor;
        releaseAuthSlot(peer);
        if (!confirmPresence(peer, actor)) return;
        send(peer, new ServerFrame("ready", null, actor.id()));
        ping(peer);
    }

    private boolean confirmPresence(Peer peer, Actor actor) {
        var id = peer.socket.getId();
        presence.confirm(id, actor, peer.access);
        if (peers.get(id) == peer) return true;
        presence.forget(id);
        return false;
    }

    private Actor refresh(Peer peer) {
        Actor current = identity.authenticate(peer.access);
        if (!current.id().equals(peer.actor.id()) || current.tenantId() != peer.actor.tenantId()) throw IdentityError.unauthorized();
        peer.actor = current;
        return current;
    }

    @Scheduled(fixedDelay = 1_000)
    void expireConnections() {
        Instant now = clock.instant();
        for (Peer peer : peers.values()) {
            if (peer.actor == null ? !now.isBefore(peer.authBy) : !now.isBefore(peer.access.expiresAt())) {
                drop(peer, true);
            }
        }
    }

    @Scheduled(fixedDelayString = "${newcrisp.chat.heartbeat-ms:15000}")
    void heartbeat() {
        presence.forgetExpired();
        for (Peer peer : peers.values()) {
            if (peer.actor == null) continue;
            if (peer.timedOut()) {
                drop(peer, false);
                continue;
            }
            try {
                refresh(peer);
                ping(peer);
            } catch (IdentityError error) {
                closeForAccessError(peer, error);
            }
        }
    }

    private void ping(Peer peer) {
        send(peer, new ServerFrame("ping", null, peer.challenge()));
    }

    @Scheduled(fixedDelayString = "${newcrisp.chat.dispatch-ms:250}", initialDelayString = "${newcrisp.chat.outbox-start-delay-ms:250}")
    void dispatch() {
        for (Outbox event : store.ready(LocalDateTime.now(clock))) {
            try {
                if (!peers.isEmpty()) publish(event);
                store.delivered(event.id());
            } catch (RuntimeException error) {
                long wait = Math.min(60, 1L << Math.min(event.attempts(), 6));
                store.retry(event.id(), LocalDateTime.now(clock).plusSeconds(wait));
                log.warn("Chat notification will retry, event {}", event.id());
            }
        }
    }

    private void publish(Outbox event) {
        var chatRow = store.find(event.tenantId(), event.conversationId(), false).orElseThrow();
        var message = event.sequence() == null ? null : store.message(chatRow, event.sequence()).orElseThrow().publicMessage();
        IdentityError unavailable = null;
        for (Peer peer : peers.values()) {
            var initial = peer.actor;
            if (initial == null) continue;
            if (initial.tenantId() != chatRow.tenantId() || (initial.customer() && !initial.id().equals(chatRow.customerId()))) continue;
            if (!initial.customer() && !initial.agentDepartments().contains(chatRow.departmentId())
                    && !initial.supervisorDepartments().contains(chatRow.departmentId())) continue;
            try {
                Actor actor = refresh(peer);
                if (!actor.customer() && !actor.agentDepartments().contains(chatRow.departmentId())
                        && !actor.supervisorDepartments().contains(chatRow.departmentId())) {
                    send(peer, new ServerFrame("sync.required", null, null));
                    continue;
                }
                if (message != null || event.assignmentVersion() != null) {
                    var current = store.find(event.tenantId(), event.conversationId(), false).orElseThrow();
                    if (message != null && ChatService.canRead(actor, current)) {
                        send(peer, new ServerFrame("message.created", null, message));
                    }
                    if (current.state() == State.ACTIVE && ChatService.owns(actor, current)
                            && ((event.assignmentVersion() != null && event.assignmentVersion() == current.version())
                            || (message != null && "CUSTOMER".equals(message.sender()) && event.sequence() > current.staffReadSeq()))) {
                        send(peer, new ServerFrame("staff.notification", null,
                                new Notification(current.id(), Long.toString(event.id()), event.assignmentVersion() != null)));
                    }
                }
                send(peer, new ServerFrame("sync.required", null, null));
            } catch (IdentityError error) {
                closeForAccessError(peer, error);
                if (error.status == HttpStatus.SERVICE_UNAVAILABLE) unavailable = error;
            }
        }
        if (unavailable != null) throw unavailable;
    }

    @EventListener
    void signedOut(ChatIdentity.SessionEnded event) {
        for (Peer peer : peers.values()) {
            var access = peer.access;
            if (access != null && access.side() == event.side() && access.sid().equals(event.sid())) drop(peer, true);
        }
        presence.revoke(event.sid());
    }

    @TransactionalEventListener
    void staffAccessChanged(ChatIdentity.StaffAccessChanged event) {
        for (Peer peer : peers.values()) {
            var access = peer.access;
            if (access == null || access.side() != Side.STAFF || access.tenantId() != event.tenantId()
                    || !access.actorId().equals(event.staffId())) continue;
            if (event.disabled() || access.staffAccessVersion() == null || access.staffAccessVersion() < event.accessVersion()) {
                drop(peer, event.disabled() ? STAFF_DISABLED : ACCESS_CHANGED, true);
            }
        }
    }

    private boolean closeForAccessError(Peer peer, IdentityError error) {
        if ("STAFF_ACCESS_CHANGED".equals(error.code)) {
            drop(peer, ACCESS_CHANGED, true);
            return true;
        }
        if (error.status == HttpStatus.UNAUTHORIZED) {
            drop(peer, true);
            return true;
        }
        return false;
    }

    private void send(Peer peer, ServerFrame frame) {
        try {
            if (peer.socket.isOpen()) peer.socket.sendMessage(new TextMessage(json.writeValueAsString(frame)));
        } catch (IOException | RuntimeException writeFailure) {
            drop(peer, false);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Peer peer = peers.remove(session.getId());
        if (peer != null) {
            if (peer.side == Side.STAFF) presence.forget(session.getId());
            releaseAuthSlot(peer);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        Peer peer = peers.get(session.getId());
        if (peer != null) drop(peer, false);
    }

    private void drop(Peer peer, boolean revoked) {
        drop(peer, revoked ? CloseStatus.POLICY_VIOLATION : CloseStatus.GOING_AWAY, revoked);
    }

    private void drop(Peer peer, CloseStatus status, boolean revoked) {
        peers.remove(peer.socket.getId(), peer);
        releaseAuthSlot(peer);
        if (revoked || peer.side == Side.STAFF) presence.forget(peer.socket.getId());
        try { peer.socket.close(status); }
        catch (IOException ignored) { /* transport is already gone */ }
    }

    private void releaseAuthSlot(Peer peer) {
        if (peer.pending.compareAndSet(true, false)) awaitingAuth.release();
    }

    private record ClientFrame(String type, String requestId, String conversationId, String clientMessageId,
                               String text, String stateVersion, String nonce, String ticket) {}
    private record ServerFrame(String type, String requestId, Object data) {}
    private record Failure(String code, int status) {}
    private record Notification(String conversationId, String eventId, boolean assigned) {}

    private static final class Peer {
        final WebSocketSession socket;
        final Side side;
        final Instant authBy;
        final AtomicBoolean pending = new AtomicBoolean(true);
        volatile SocketAccess access;
        volatile Actor actor;
        private String nonce;
        private int missedHeartbeats;

        Peer(WebSocketSession socket, Side side, Instant authBy) {
            this.socket = socket;
            this.side = side;
            this.authBy = authBy;
        }

        synchronized String challenge() {
            nonce = UUID.randomUUID().toString();
            missedHeartbeats++;
            return nonce;
        }

        synchronized boolean confirm(String offered) {
            if (offered == null || !offered.equals(nonce)) return false;
            nonce = null;
            missedHeartbeats = 0;
            return true;
        }

        synchronized boolean timedOut() { return missedHeartbeats >= 3; }
    }
}
