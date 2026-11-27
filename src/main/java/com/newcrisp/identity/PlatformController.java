package com.newcrisp.identity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/platform")
class PlatformController {
    private static final String SESSION_COOKIE = "__Host-newcrisp_platform";
    private static final Duration SESSION_TTL = Duration.ofHours(8);

    private final StaffService staff;
    private final NexusLaunchService nexus;
    private final PlatformStore platformStore;
    private final IdentityConfiguration.Settings settings;
    private final Clock clock;
    private final byte[] platformDigest;

    PlatformController(StaffService staff, NexusLaunchService nexus, PlatformStore platformStore,
                       IdentityConfiguration.Settings settings, Clock clock) {
        this.staff = staff;
        this.nexus = nexus;
        this.platformStore = platformStore;
        this.settings = settings;
        this.clock = clock;
        this.platformDigest = settings.platformDigest();
    }

    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PlatformSession> login(@Valid @RequestBody PlatformLogin input, HttpServletRequest request) {
        IdentityHttp.requireOrigin(request, settings.publicOrigin());
        if (platformDigest.length == 0 || !IdentityTokens.matches(input.key(), platformDigest)) {
            throw IdentityError.unauthorized();
        }
        Instant expiresAt = clock.instant().plus(SESSION_TTL);
        String cookie = ResponseCookie.from(SESSION_COOKIE, signSession(expiresAt.getEpochSecond()))
                .secure(true).httpOnly(true).path("/").sameSite("Strict").maxAge(SESSION_TTL).build().toString();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie)
                .body(new PlatformSession(true, settings.isSelfSignupEnabled(),
                        settings.landingDemoSiteId().isEmpty() ? null : settings.landingDemoSiteId()));
    }

    @GetMapping("/me")
    ResponseEntity<PlatformSession> me(HttpServletRequest request) {
        authorize(request, false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PlatformSession(true, settings.isSelfSignupEnabled(),
                        settings.landingDemoSiteId().isEmpty() ? null : settings.landingDemoSiteId()));
    }

    @DeleteMapping("/session")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        IdentityHttp.requireOrigin(request, settings.publicOrigin());
        String cookie = ResponseCookie.from(SESSION_COOKIE, "")
                .secure(true).httpOnly(true).path("/").sameSite("Strict").maxAge(Duration.ZERO).build().toString();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie).build();
    }

    @GetMapping("/overview")
    ResponseEntity<PlatformStore.PlatformOverview> overview(HttpServletRequest request) {
        authorize(request, false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(platformStore.overview(LocalDateTime.now(clock).minusHours(24)));
    }

    @GetMapping("/tenants")
    ResponseEntity<List<PlatformStore.TenantSummary>> listTenants(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        authorize(request, false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(platformStore.listTenants(q, status));
    }

    @PostMapping(value = "/tenants", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TenantCreated> create(@Valid @RequestBody Onboarding input, HttpServletRequest request) {
        authorize(request, true);
        staff.provision(input.code(), input.name(), input.adminEmail(), input.adminPassword());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new TenantCreated(input.code(), input.name().strip()));
    }

    @PatchMapping(value = "/tenants/{tenantCode}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> updateStatus(@PathVariable String tenantCode,
                                      @Valid @RequestBody StatusUpdate input,
                                      HttpServletRequest request) {
        authorize(request, true);
        if (!platformStore.updateTenantStatus(tenantCode, input.status(), LocalDateTime.now(clock))) {
            throw IdentityError.notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(value = "/tenants/{tenantCode}/admin-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> resetAdminPassword(@PathVariable String tenantCode,
                                            @Valid @RequestBody PasswordReset input,
                                            HttpServletRequest request) {
        authorize(request, true);
        String hash = staff.encodeNewPassword(input.password());
        if (!platformStore.resetAdminPassword(tenantCode, input.email().toLowerCase(Locale.ROOT), hash, LocalDateTime.now(clock))) {
            throw IdentityError.notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(value = "/tenants/{tenantCode}/nexus-integrations", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<NexusLaunchService.IntegrationCreated> integration(
            @PathVariable String tenantCode, @Valid @RequestBody IntegrationInput input, HttpServletRequest request) {
        authorize(request, true);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(nexus.configure(tenantCode, input.nexusOrigin()));
    }

    private void authorize(HttpServletRequest request, boolean mutating) {
        if (platformDigest.length == 0) {
            throw IdentityError.unauthorized();
        }
        String bearer = IdentityTokens.bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (bearer != null) {
            if (IdentityTokens.matches(bearer, platformDigest)) return;
            throw IdentityError.unauthorized();
        }
        String cookie = IdentityHttp.token(request, SESSION_COOKIE);
        if (cookie == null || !verifySession(cookie)) {
            throw IdentityError.unauthorized();
        }
        if (mutating) {
            IdentityHttp.requireOrigin(request, settings.publicOrigin());
        }
    }

    private String signSession(long expEpochSeconds) {
        byte[] mac = hmac("newcrisp-platform-session:" + expEpochSeconds);
        return expEpochSeconds + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    private boolean verifySession(String token) {
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) return false;
        long exp;
        try {
            exp = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException bad) {
            return false;
        }
        if (clock.instant().getEpochSecond() >= exp) return false;
        byte[] presented;
        try {
            presented = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException bad) {
            return false;
        }
        return MessageDigest.isEqual(presented, hmac("newcrisp-platform-session:" + exp));
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(platformDigest, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record PlatformLogin(@NotBlank @Size(max = 128) String key) {}
    record PlatformSession(boolean authenticated, boolean selfSignupEnabled, String landingDemoSiteId) {}
    record Onboarding(@NotBlank @Pattern(regexp = StaffController.TENANT_CODE) String code,
                      @NotBlank @Size(max = 120) String name, @NotBlank @Email @Size(max = 254) String adminEmail,
                      @NotBlank @Size(max = 72) String adminPassword) {}
    record TenantCreated(String code, String name) {}
    record StatusUpdate(@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) {}
    record PasswordReset(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}
    record IntegrationInput(@NotBlank @Size(max = 255) String nexusOrigin) {}
}
