package com.newcrisp.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NexusLaunchServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T02:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final String NEXUS_ORIGIN = "https://console.example.test";
    private static final String KEY_ID = "1c7248b5-3092-409c-b619-c7df1baf79d5";
    private final String token = IdentityTokens.generate();
    private final byte[] hash = IdentityTokens.digest(token);
    private final NexusStore.Grant grant = new NexusStore.Grant(17, "nexus-a", 7, 11, NEXUS_ORIGIN);

    @Mock NexusStore store;
    @Mock NexusSessionVerifier verifier;
    @Mock PlatformTransactionManager transactionManager;
    NexusLaunchService service;

    @BeforeEach
    void setUp() {
        var settings = new IdentityConfiguration.Settings("https://chat.example.test", "", "", "", Duration.ofSeconds(60));
        service = new NexusLaunchService(store, verifier, new TransactionTemplate(transactionManager), CLOCK, settings);
    }

    @Test
    void issuesGrantOnlyForCredentialTenantAndStoresDigestWithOneMinuteExpiry() {
        String secret = IdentityTokens.generate();
        var key = new NexusStore.IntegrationKey(17, "nexus-a", IdentityTokens.digest(secret));
        when(store.integrationKey(KEY_ID)).thenReturn(Optional.of(key));

        String result = service.issue("Bearer " + KEY_ID + "." + secret, 7, 11);

        verify(store).createGrant(aryEq(IdentityTokens.digest(result)), eq(key), eq(7L), eq(11L), eq(NOW.plusMinutes(1)));
    }

    @Test
    void invalidCredentialNeverIssuesGrant() {
        var key = new NexusStore.IntegrationKey(17, "nexus-a", IdentityTokens.digest(IdentityTokens.generate()));
        when(store.integrationKey(KEY_ID)).thenReturn(Optional.of(key));

        assertThatThrownBy(() -> service.issue("Bearer " + KEY_ID + "." + token, 7, 11)).hasMessage("SESSION_REQUIRED");
        verify(store, never()).createGrant(any(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void wrongBrowserOriginLeavesGrantUnconsumed() {
        when(store.availableGrant(aryEq(hash), eq(NOW))).thenReturn(Optional.of(grant));

        assertThatThrownBy(() -> service.redeem(token, "https://other.example.test")).hasMessage("ORIGIN_REJECTED");
        verifyNoInteractions(verifier, transactionManager);
    }

    @Test
    void redeemedOrExpiredGrantIsDeniedWithoutContactingNexus() {
        when(store.availableGrant(aryEq(hash), eq(NOW))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.redeem(token, NEXUS_ORIGIN)).hasMessage("INVALID_LAUNCH_GRANT");
        verifyNoInteractions(verifier, transactionManager);
    }

    @Test
    void revokedNexusSessionCannotRedeemAnUnusedGrant() {
        when(store.availableGrant(aryEq(hash), eq(NOW))).thenReturn(Optional.of(grant));
        when(verifier.active(7, 11)).thenReturn(false);

        assertThatThrownBy(() -> service.redeem(token, NEXUS_ORIGIN)).hasMessage("INVALID_LAUNCH_GRANT");
        verifyNoInteractions(transactionManager);
    }

    @Test
    void losingConcurrentConsumptionDoesNotCreateAnotherSession() {
        when(store.availableGrant(aryEq(hash), eq(NOW))).thenReturn(Optional.of(grant));
        when(verifier.active(7, 11)).thenReturn(true);
        var status = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(status);

        assertThatThrownBy(() -> service.redeem(token, NEXUS_ORIGIN)).hasMessage("INVALID_LAUNCH_GRANT");
        verify(store, never()).createSession(any(), any(), any(), any());
        verify(transactionManager).rollback(status);
    }

    @Test
    void expiredVerificationRevokesCustomerSessionIfNexusNoLongerRecognizesIt() {
        var profile = new NexusStore.CustomerProfile("customer-a", "tenant-a", "第一租户");
        when(store.session(aryEq(hash), eq(NOW))).thenReturn(Optional.of(new NexusStore.CustomerSession(profile, 7, 11, NOW, 17, "support")));
        when(verifier.active(7, 11)).thenReturn(false);

        assertThatThrownBy(() -> service.profile(token)).hasMessage("SESSION_REQUIRED");
        verify(store).deleteSession(aryEq(hash));
    }
}
