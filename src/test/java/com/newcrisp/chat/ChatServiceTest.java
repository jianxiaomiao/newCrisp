package com.newcrisp.chat;

import static com.newcrisp.chat.ChatModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newcrisp.identity.ChatIdentity.Actor;
import com.newcrisp.identity.ChatIdentity.Side;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T02:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final String ID = "7c9e4205-0a40-4ca3-9d3b-0979056af040";
    private static final String KEY = "8c9e4205-0a40-4ca3-9d3b-0979056af040";
    private final ChatStore store = mock(ChatStore.class);
    private final ChatService service = new ChatService(store, CLOCK);
    private final Actor customer = new Actor(Side.CUSTOMER, 1, "customer", "support", Set.of(), Set.of(), 0);
    private final Actor owner = new Actor(Side.STAFF, 1, "agent", null, Set.of("support"), Set.of(), 0);
    private final Actor supervisor = new Actor(Side.STAFF, 1, "manager", null, Set.of(), Set.of("support"), 0);

    @BeforeEach
    void currentRoles() {
        when(store.lockStaffAccess(owner)).thenReturn(true);
        when(store.lockStaffAccess(supervisor)).thenReturn(true);
    }

    @Test
    void checksTenantAndAssignmentEvenWhenIdsAreKnown() {
        var chat = conversation("agent", State.ACTIVE, 1, 5);
        var outsider = new Actor(Side.STAFF, 9, "agent", null, Set.of("support"), Set.of("support"), 0);
        var peer = new Actor(Side.STAFF, 1, "other", null, Set.of("support"), Set.of(), 0);
        assertThat(ChatService.canRead(customer, chat)).isTrue();
        assertThat(ChatService.canRead(owner, chat)).isTrue();
        assertThat(ChatService.canRead(supervisor, chat)).isTrue();
        assertThat(ChatService.canRead(peer, chat)).isFalse();
        assertThat(ChatService.canRead(outsider, chat)).isFalse();
    }

    @Test
    void staleOwnerMessageCannotWriteAfterSameAgentReclaimed() {
        var chat = conversation("agent", State.ACTIVE, 3, 5);
        when(store.find(1, ID, true)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> service.send(owner, ID, new Send(KEY, "消息", "1"))).hasMessage("STATE_VERSION_CHANGED");
        verify(store, never()).append(any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void permissionChangeBeforeTheWriteLockStopsAnAlreadyAuthenticatedCommand() {
        when(store.lockStaffAccess(owner)).thenReturn(false);

        assertThatThrownBy(() -> service.send(owner, ID, new Send(KEY, "消息", "1"))).hasMessage("STAFF_ACCESS_CHANGED");
        verify(store, never()).find(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void resolvesFirstSendReplayBeforeLookingForAnActiveConversation() {
        var closed = conversation("agent", State.CLOSED, 2, 5);
        when(store.lockCustomer(customer)).thenReturn(true);
        when(store.replay(customer, KEY)).thenReturn(Optional.of(new StoredMessage(ID, 1, true, "首条", NOW, KEY, true)));
        when(store.find(1, ID, false)).thenReturn(Optional.of(closed));

        assertThat(service.send(customer, null, new Send(KEY, "首条", null)).replayed()).isTrue();
        verify(store, never()).active(any());
        verify(store, never()).create(any(), any(), any());
    }

    @Test
    void closeRequiresBothObservedSequenceAndSavedReadPosition() {
        var unread = conversation("agent", State.ACTIVE, 1, 4);
        when(store.find(1, ID, true)).thenReturn(Optional.of(unread));

        assertThatThrownBy(() -> service.action(owner, ID, Operation.CLOSE, new Action(KEY, "1", "5", null)))
                .hasMessage("NEW_CUSTOMER_MESSAGES");
        when(store.find(1, ID, true)).thenReturn(Optional.of(conversation("agent", State.ACTIVE, 1, 5)));
        assertThatThrownBy(() -> service.action(owner, ID, Operation.CLOSE, new Action(KEY, "1", "4", null)))
                .hasMessage("NEW_CUSTOMER_MESSAGES");
        verify(store, never()).change(any(), any(), any(), any());
    }

    @Test
    void supervisorCanReturnButCannotMoveOwnersReadPosition() {
        var chat = conversation("agent", State.ACTIVE, 1, 0);
        when(store.find(1, ID, true)).thenReturn(Optional.of(chat));

        assertThatThrownBy(() -> service.read(supervisor, ID, new Read("5", "1"))).hasMessage("CHAT_PERMISSION_REQUIRED");
        assertThat(service.action(supervisor, ID, Operation.RETURN, new Action(KEY, "1", null, "接手")))
                .isEqualTo(new ActionResult(ID, State.WAITING, "2"));
        verify(store, never()).advanceRead(any(), any(), anyLong());
        verify(store).change(chat, State.WAITING, null, NOW);
        verify(store).enqueue(chat, null, NOW);
    }

    private Conversation conversation(String staff, State state, long version, long read) {
        return new Conversation(1, ID, "customer", "support", staff, state, version, 5, 5, 2, 0, read, NOW, NOW);
    }
}
