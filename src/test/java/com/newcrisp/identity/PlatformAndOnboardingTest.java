package com.newcrisp.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class PlatformAndOnboardingTest {
    private static final String ORIGIN = "https://chat.example.test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-27T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void publicSelfSignupProvisionsAndLogsInTenantAdmin() {
        var staff = mock(StaffService.class);
        var settings = new IdentityConfiguration.Settings(ORIGIN, "", "", "", Duration.ofSeconds(60), true, "site-demo");
        var controller = new PublicOnboardingController(staff, settings, CLOCK);

        var access = new AccessJwt.Token("jwt-token", CLOCK.instant().plusSeconds(600), "sid-1");
        var issued = new WebTokens.Issued(IdentityTokens.generate(), "sid-1", CLOCK.instant().plusSeconds(28800), access);
        when(staff.login("acme", "admin@acme.test", "StrongPass!2026")).thenReturn(issued);

        var req = new MockHttpServletRequest("POST", "/api/v1/public/tenants/register");
        req.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        req.setRemoteAddr("127.0.0.1");

        var response = controller.register(
                new PublicOnboardingController.SelfSignup("acme", "Acme Corp", "admin@acme.test", "StrongPass!2026"), req);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("acme", response.getBody().code());
        assertEquals("jwt-token", response.getBody().access().accessToken());
        var cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(cookies);
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("__Host-newcrisp_staff_rt=")));
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("__Host-newcrisp_staff_csrf=")));
        verify(staff).provision("acme", "Acme Corp", "admin@acme.test", "StrongPass!2026");

        var cfg = controller.config().getBody();
        assertTrue(cfg.selfSignupEnabled());
        assertEquals("site-demo", cfg.landingDemoSiteId());
    }

    @Test
    void publicSelfSignupBlocksReservedCodesAndDisabledFlag() {
        var staff = mock(StaffService.class);
        var enabled = new PublicOnboardingController(staff,
                new IdentityConfiguration.Settings(ORIGIN, "", "", "", Duration.ofSeconds(60), true, ""), CLOCK);
        var req = new MockHttpServletRequest("POST", "/api/v1/public/tenants/register");
        req.addHeader(HttpHeaders.ORIGIN, ORIGIN);

        var reserved = assertThrows(IdentityError.class, () -> enabled.register(
                new PublicOnboardingController.SelfSignup("platform", "Bad", "a@b.test", "StrongPass!2026"), req));
        assertEquals("RESERVED_TENANT_CODE", reserved.code);

        var disabled = new PublicOnboardingController(staff,
                new IdentityConfiguration.Settings(ORIGIN, "", "", "", Duration.ofSeconds(60), false, ""), CLOCK);
        var blocked = assertThrows(IdentityError.class, () -> disabled.register(
                new PublicOnboardingController.SelfSignup("acme", "Acme", "a@b.test", "StrongPass!2026"), req));
        assertEquals("SELF_SIGNUP_DISABLED", blocked.code);
    }

    @Test
    void platformCookieAndBearerAuthenticationWork() {
        String rawKey = IdentityTokens.generate();
        String shaHex = HexFormat.of().formatHex(IdentityTokens.digest(rawKey));
        var staff = mock(StaffService.class);
        var nexus = mock(NexusLaunchService.class);
        var store = mock(PlatformStore.class);
        var settings = new IdentityConfiguration.Settings(ORIGIN, shaHex, "", "", Duration.ofSeconds(60), true, "");
        var controller = new PlatformController(staff, nexus, store, settings, CLOCK);

        // Login with valid platform key issues HttpOnly session cookie
        var loginReq = new MockHttpServletRequest("POST", "/api/v1/platform/session");
        loginReq.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        var loginResp = controller.login(new PlatformController.PlatformLogin(rawKey), loginReq);
        assertEquals(HttpStatus.OK, loginResp.getStatusCode());
        String setCookie = loginResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith("__Host-newcrisp_platform="));
        assertTrue(setCookie.contains("HttpOnly"));
        String cookieVal = setCookie.substring("__Host-newcrisp_platform=".length(), setCookie.indexOf(';'));

        // Cookie authenticates GET /me and mutating PATCH /status (with Origin)
        var meReq = new MockHttpServletRequest("GET", "/api/v1/platform/me");
        meReq.setCookies(new Cookie("__Host-newcrisp_platform", cookieVal));
        assertTrue(controller.me(meReq).getBody().authenticated());

        when(store.updateTenantStatus(eq("acme"), eq("DISABLED"), any())).thenReturn(true);
        var patchReq = new MockHttpServletRequest("PATCH", "/api/v1/platform/tenants/acme/status");
        patchReq.setCookies(new Cookie("__Host-newcrisp_platform", cookieVal));
        patchReq.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        assertEquals(HttpStatus.NO_CONTENT,
                controller.updateStatus("acme", new PlatformController.StatusUpdate("DISABLED"), patchReq).getStatusCode());

        // Legacy Bearer auth still works without cookie or Origin
        var bearerReq = new MockHttpServletRequest("POST", "/api/v1/platform/tenants");
        bearerReq.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
        var created = controller.create(
                new PlatformController.Onboarding("beta", "Beta", "admin@beta.test", "StrongPass!2026"), bearerReq);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        verify(staff).provision("beta", "Beta", "admin@beta.test", "StrongPass!2026");

        // Wrong key rejected
        var badLogin = new MockHttpServletRequest("POST", "/api/v1/platform/session");
        badLogin.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        assertThrows(IdentityError.class, () -> controller.login(new PlatformController.PlatformLogin(IdentityTokens.generate()), badLogin));
    }
}
