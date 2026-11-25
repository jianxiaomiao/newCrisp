package com.newcrisp.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.newcrisp.identity.ChatIdentity.Side;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class WebTokensTest {
    private static final Instant NOW = Instant.parse("2026-11-25T04:00:00Z");
    private static final String SID = "809ec62a-ad76-4245-8f9b-ec5592def6a1";
    private static final String ID = "b0f1abc3-4556-41df-bf52-a727efb020b1";
    private static final AccessJwt.Subject SUBJECT = new AccessJwt.Subject(Side.STAFF, 12, ID, SID);
    private final String raw = IdentityTokens.generate();
    private final byte[] hash = IdentityTokens.digest(raw);
    private final TokenStore store = mock(TokenStore.class);
    private final StaffStore staff = mock(StaffStore.class);
    private final NexusStore nexus = mock(NexusStore.class);
    private final NexusSessionVerifier verifier = mock(NexusSessionVerifier.class);
    private final AccessJwt jwt = mock(AccessJwt.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
    private final WebTokens service = new WebTokens(store, staff, nexus, verifier,
            new IdentityConfiguration.Settings("http://127.0.0.1:8080", "", "", "", Duration.ofSeconds(60)),
            jwt, new TransactionTemplate(manager), events, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void confirmsReplayUnderLockAndCommitsRevocationBeforePublishingClose() {
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(store.refresh(eq(Side.STAFF), aryEq(hash), eq(false)))
                .thenReturn(Optional.of(new TokenStore.Refresh(SID, false, false, NOW.plusSeconds(60))));
        when(store.refresh(eq(Side.STAFF), aryEq(hash), eq(true)))
                .thenReturn(Optional.of(new TokenStore.Refresh(SID, false, true, NOW.plusSeconds(60))));
        when(store.session(Side.STAFF, SID, NOW, true)).thenReturn(Optional.of(new TokenStore.Session(SUBJECT, NOW.plusSeconds(60))));

        assertThatThrownBy(() -> service.rotate(Side.STAFF, raw, false)).hasMessage("REFRESH_REPLAY");

        var order = inOrder(store, manager, events);
        order.verify(store).session(Side.STAFF, SID, NOW, true);
        order.verify(store).refresh(eq(Side.STAFF), aryEq(hash), eq(true));
        order.verify(store).revoke(Side.STAFF, SID, NOW);
        order.verify(manager).commit(transaction);
        order.verify(events).publishEvent(new ChatIdentity.SessionEnded(Side.STAFF, SID));
        verifyNoInteractions(jwt, verifier);
    }

    @Test
    void refreshReplacesTokenWithFreshDigestAndKeepsTheSameSession() {
        when(manager.getTransaction(any())).thenReturn(transaction);
        Instant expiry = NOW.plusSeconds(60);
        when(store.refresh(eq(Side.STAFF), aryEq(hash), anyBoolean()))
                .thenReturn(Optional.of(new TokenStore.Refresh(SID, false, false, expiry)));
        when(store.session(Side.STAFF, SID, NOW, true)).thenReturn(Optional.of(new TokenStore.Session(SUBJECT, expiry)));
        when(staff.session(SID, TokenStore.utc(NOW))).thenReturn(Optional.of(new StaffStore.StaffProfile("tenant-a", "A",
                "a@example.test", "STAFF", ID, 12, true, false)));
        when(store.consumeRefresh(aryEq(hash), eq(NOW))).thenReturn(true);

        var issued = service.rotate(Side.STAFF, raw, false);

        assertThat(issued.refresh()).isNotEqualTo(raw);
        assertThat(issued.sid()).isEqualTo(SID);
        verify(store).insertRefresh(eq(SUBJECT), aryEq(IdentityTokens.digest(issued.refresh())), eq(expiry), eq(false));
        verify(jwt).sign(SUBJECT, expiry);
    }

    @Test
    void losingTicketConsumptionCannotAuthenticate() {
        when(store.ticket(eq(Side.STAFF), aryEq(hash), eq(NOW)))
                .thenReturn(Optional.of(new TokenStore.Ticket(SID, NOW.plusSeconds(60))));
        when(store.session(Side.STAFF, SID, NOW, false)).thenReturn(Optional.of(new TokenStore.Session(SUBJECT, NOW.plusSeconds(60))));

        assertThatThrownBy(() -> service.redeemTicket(Side.STAFF, raw)).hasMessage("SESSION_REQUIRED");
        verify(store).consumeTicket(eq(Side.STAFF), aryEq(hash), eq(NOW));
    }

    @Test
    void customerRefreshRequiresLiveNexusSessionBeforeRotation() {
        when(store.refresh(eq(Side.CUSTOMER), aryEq(hash), eq(false)))
                .thenReturn(Optional.of(new TokenStore.Refresh(SID, false, false, NOW.plusSeconds(60))));
        var profile = new NexusStore.CustomerProfile(ID, "tenant-a", "A");
        when(nexus.session(SID, TokenStore.utc(NOW)))
                .thenReturn(Optional.of(new NexusStore.CustomerSession(profile, 7, 9, TokenStore.utc(NOW), 12, "support")));
        when(store.revoke(Side.CUSTOMER, SID, NOW)).thenReturn(true);

        assertThatThrownBy(() -> service.rotate(Side.CUSTOMER, raw, false)).hasMessage("SESSION_REQUIRED");

        verify(verifier).active(7, 9);
        verifyNoInteractions(manager, jwt);
        verify(events).publishEvent(new ChatIdentity.SessionEnded(Side.CUSTOMER, SID));
    }
}
