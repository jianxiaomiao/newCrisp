package com.newcrisp.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T02:00:00Z"), ZoneOffset.UTC);
    private static final String PASSWORD = "a long admin password";

    @Mock StaffStore store;
    @Mock PasswordEncoder encoder;
    StaffService staff;

    @BeforeEach
    void setUp() {
        when(encoder.encode(anyString())).thenReturn("encoded-password");
        staff = new StaffService(store, encoder, CLOCK);
    }

    @Test
    void provisionsFirstAdminWithNormalizedEmailAndPasswordDigest() {
        staff.provision("tenant-a", "  第一租户  ", "ADMIN@example.test", PASSWORD);

        verify(encoder).encode(PASSWORD);
        verify(store).createTenant("tenant-a", "第一租户", "admin@example.test", "encoded-password", LocalDateTime.now(CLOCK));
    }

    @Test
    void rejectsShortAdminPasswordBeforeWritingAnything() {
        assertThatThrownBy(() -> staff.provision("tenant-a", "第一租户", "admin@example.test", "short"))
                .hasMessage("INVALID_ADMIN_PASSWORD");
        verifyNoInteractions(store);
    }

    @Test
    void mapsDuplicateTenantToConflict() {
        doThrow(new DuplicateKeyException("duplicate code")).when(store).createTenant(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> staff.provision("tenant-a", "第一租户", "admin@example.test", PASSWORD))
                .hasMessage("TENANT_ALREADY_EXISTS");
    }

    @Test
    void bindsSessionToAuthenticatedTenantAccountAndStoresOnlyDigest() {
        var account = new StaffStore.LoginAccount(17, "staff-a", "password-a");
        when(store.loginAccount("tenant-a", "admin@example.test")).thenReturn(Optional.of(account));
        when(encoder.matches(PASSWORD, "password-a")).thenReturn(true);
        when(store.createSession(any(), eq(account), any())).thenReturn(true);

        String token = staff.login("tenant-a", "ADMIN@example.test", PASSWORD);

        verify(store).createSession(aryEq(IdentityTokens.digest(token)), eq(account), eq(LocalDateTime.now(CLOCK).plusHours(8)));
    }

    @Test
    void wrongTenantDoesNotCreateSession() {
        when(store.loginAccount("tenant-b", "admin@example.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> staff.login("tenant-b", "admin@example.test", PASSWORD)).hasMessage("SESSION_REQUIRED");
        verify(encoder).matches(PASSWORD, "encoded-password");
        verify(store, never()).createSession(any(), any(), any());
    }
}
