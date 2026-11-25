package com.newcrisp.chat;

import static com.newcrisp.chat.ChatModels.*;

import com.newcrisp.identity.ChatIdentity;
import com.newcrisp.identity.ChatIdentity.Actor;
import com.newcrisp.identity.ChatIdentity.Credential;
import com.newcrisp.identity.IdentityError;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class ChatWebSocket extends TextWebSocketHandler {
    static final String CREDENTIAL = ChatWebSocket.class.getName() + ".credential";
    static final String ACTOR = ChatWebSocket.class.getName() + ".actor";
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocket.class);
    private final ConcurrentMap<String, Peer> peers = new ConcurrentHashMap<>();
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
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(32 * 1024);
        Peer peer = new Peer(new ConcurrentWebSocketSessionDecorator(session, 2_000, 64 * 1024),
                (Credential) session.getAttributes().get(CREDENTIAL), (Actor) session.getAttributes().get(ACTOR));
        peers.put(session.getId(), peer);
        presence.confirm(session.getId(), peer.actor, peer.sessionMarker);
        send(peer, new ServerFrame("ready", null, peer.actor.id()));
        ping(peer);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Peer peer = peers.get(session.getId());
        if (peer == null) return;
        String requestId = null;
        try {
            ClientFrame frame = json.readValue(message.getPayload(), ClientFrame.class);
            if (frame == null || frame.type() == null) throw invalid("INVALID_FRAME");
            if ("pong".equals(frame.type())) {
                Actor actor = refresh(peer);
                if (peer.confirm(frame.nonce()) && peers.get(session.getId()) == peer) {
                    presence.confirm(session.getId(), actor, peer.sessionMarker);
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
            if (error.status == HttpStatus.UNAUTHORIZED) drop(peer, true);
        } catch (JacksonException invalidJson) {
            send(peer, new ServerFrame("error", requestId, new Failure("INVALID_FRAME", 400)));
        } catch (RuntimeException error) {
            log.warn("Chat WebSocket command failed for tenant {}", peer.actor.tenantId());
            send(peer, new ServerFrame("error", requestId, new Failure("SEND_RESULT_UNKNOWN", 503)));
        }
    }

    private Actor refresh(Peer peer) {
        Actor current = identity.authenticate(peer.credential);
        if (!current.id().equals(peer.actor.id()) || current.tenantId() != peer.actor.tenantId()) throw IdentityError.unauthorized();
        peer.actor = current;
        return current;
    }

    @Scheduled(fixedDelayString = "${newcrisp.chat.heartbeat-ms:15000}")
    void heartbeat() {
        presence.forgetExpired();
        for (Peer peer : peers.values()) {
            if (peer.timedOut()) {
                drop(peer, false);
                continue;
            }
            try {
                refresh(peer);
                ping(peer);
            } catch (IdentityError error) {
                if (error.status == HttpStatus.UNAUTHORIZED) drop(peer, true);
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
                if (message != null) {
                    var current = store.find(event.tenantId(), event.conversationId(), false).orElseThrow();
                    if (ChatService.canRead(actor, current)) send(peer, new ServerFrame("message.created", null, message));
                }
                send(peer, new ServerFrame("sync.required", null, null));
            } catch (IdentityError error) {
                if (error.status == HttpStatus.UNAUTHORIZED) drop(peer, true);
                if (error.status == HttpStatus.SERVICE_UNAVAILABLE) unavailable = error;
            }
        }
        if (unavailable != null) throw unavailable;
    }

    @EventListener
    void signedOut(ChatIdentity.SessionEnded event) {
        for (Peer peer : peers.values()) {
            if (peer.credential.equals(event.credential())) drop(peer, true);
        }
        presence.revoke(event.credential().sessionMarker());
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
        peers.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) {
        Peer peer = peers.get(session.getId());
        if (peer != null) drop(peer, false);
    }

    private void drop(Peer peer, boolean revoked) {
        peers.remove(peer.socket.getId(), peer);
        if (revoked) presence.forget(peer.socket.getId());
        try { peer.socket.close(revoked ? CloseStatus.POLICY_VIOLATION : CloseStatus.GOING_AWAY); }
        catch (IOException ignored) { /* transport is already gone */ }
    }

    private record ClientFrame(String type, String requestId, String conversationId, String clientMessageId,
                               String text, String stateVersion, String nonce) {}
    private record ServerFrame(String type, String requestId, Object data) {}
    private record Failure(String code, int status) {}

    private static final class Peer {
        final WebSocketSession socket;
        final Credential credential;
        final String sessionMarker;
        volatile Actor actor;
        private String nonce;
        private int missedHeartbeats;

        Peer(WebSocketSession socket, Credential credential, Actor actor) {
            this.socket = socket;
            this.credential = credential;
            this.sessionMarker = credential.sessionMarker();
            this.actor = actor;
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
