package com.newcrisp.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.newcrisp.identity.ChatIdentity.Side;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessJwtTest {
    @Test
    void rejectsWrongAudienceTamperingAndExpiry() throws Exception {
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-11-25T04:00:00Z");
        when(clock.instant()).thenReturn(now);
        var keys = new AccessJwt(clock, new IdentityConfiguration.Settings("http://127.0.0.1:8080", "", "", "",
                Duration.ofSeconds(60)), "test-key", "", "", "", Duration.ofMinutes(5));
        var subject = new AccessJwt.Subject(Side.STAFF, 12, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var token = keys.sign(subject, now.plusSeconds(120));
        assertThat(keys.verify(token.accessToken(), Side.STAFF).subject()).isEqualTo(subject);
        assertThat(token.expiresAt()).isEqualTo(now.plusSeconds(120));
        assertThatThrownBy(() -> keys.verify(token.accessToken(), Side.CUSTOMER)).hasMessage("SESSION_REQUIRED");
        String[] pieces = token.accessToken().split("\\.");
        pieces[2] = (pieces[2].startsWith("A") ? "B" : "A") + pieces[2].substring(1);
        assertThatThrownBy(() -> keys.verify(String.join(".", pieces), Side.STAFF)).hasMessage("SESSION_REQUIRED");
        when(clock.instant()).thenReturn(token.expiresAt());
        assertThatThrownBy(() -> keys.verify(token.accessToken(), Side.STAFF)).hasMessage("SESSION_REQUIRED");
    }

    @Test
    void acceptsOnlyTheConfiguredSigningKeysDuringRotation() throws Exception {
        var now = Instant.parse("2026-11-25T04:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var settings = new IdentityConfiguration.Settings("https://support.example.test", "", "", "", Duration.ofSeconds(60));
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var old = generator.generateKeyPair();
        var next = generator.generateKeyPair();
        var base64 = Base64.getEncoder();
        var first = new AccessJwt(clock, settings, "old", base64.encodeToString(old.getPrivate().getEncoded()), "", "", Duration.ofMinutes(5));
        String newPrivate = base64.encodeToString(next.getPrivate().getEncoded());
        var rotation = new AccessJwt(clock, settings, "next", newPrivate, "old", base64.encodeToString(old.getPublic().getEncoded()), Duration.ofMinutes(5));
        var finished = new AccessJwt(clock, settings, "next", newPrivate, "", "", Duration.ofMinutes(5));
        var subject = new AccessJwt.Subject(Side.CUSTOMER, 12, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        var original = first.sign(subject, now.plusSeconds(60));
        var replacement = rotation.sign(subject, now.plusSeconds(60));

        assertThat(rotation.verify(original.accessToken(), Side.CUSTOMER).subject()).isEqualTo(subject);
        assertThat(finished.verify(replacement.accessToken(), Side.CUSTOMER).subject()).isEqualTo(subject);
        assertThatThrownBy(() -> finished.verify(original.accessToken(), Side.CUSTOMER)).hasMessage("SESSION_REQUIRED");
    }
}
