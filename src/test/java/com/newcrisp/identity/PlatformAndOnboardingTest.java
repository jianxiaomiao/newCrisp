package com.newcrisp.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;

class PlatformAndOnboardingTest {
    private static final String ORIGIN = "https://chat.example.test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-27T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void arithmeticCaptchaNeverDividesByZeroAndIsSingleUse() {
        var captcha = new ArithmeticCaptchaService(CLOCK);
        boolean sawAdd = false, sawSub = false, sawMul = false, sawDiv = false;
        for (int i = 0; i < 400; i++) {
            var expr = captcha.nextExpression();
            assertTrue(expr.answer() >= 0, "Answer must be non-negative: " + expr.text());
            if (expr.text().contains(" + ")) sawAdd = true;
            else if (expr.text().contains(" - ")) sawSub = true;
            else if (expr.text().contains(" × ")) sawMul = true;
            else if (expr.text().contains(" ÷ ")) {
                sawDiv = true;
                String[] parts = expr.text().replace(" = ?", "").split(" ÷ ");
                int dividend = Integer.parseInt(parts[0]);
                int divisor = Integer.parseInt(parts[1]);
                assertTrue(divisor >= 2, "Divisor must be >= 2: " + expr.text());
                assertEquals(0, dividend % divisor, "Division must be exact: " + expr.text());
                assertEquals(dividend / divisor, expr.answer());
            }
        }
        assertTrue(sawAdd && sawSub && sawMul && sawDiv, "All four arithmetic operators should appear");

        var view = captcha.issue();
        assertTrue(view.imageDataUrl().startsWith("data:image/svg+xml;base64,"));
        assertFalse(captcha.verifyAndConsume(view.captchaId(), "not-a-number"));
        // Single-use: even after one failed attempt, the challenge is consumed
        assertFalse(captcha.verifyAndConsume(view.captchaId(), "0"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emailVerificationSendsHashedCodeEnforcesCooldownAndVerifies() {
        var store = mock(EmailVerificationStore.class);
        var provider = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class);
        var mailSender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        when(store.countRecentByEmail(anyString(), anyString(), any())).thenReturn(0L);
        when(store.countRecentByIp(anyString(), any())).thenReturn(0L);

        var service = new EmailVerificationService(store, provider, CLOCK, "noreply@qq.com");
        service.sendSignupCode("Admin@Acme.Test", "127.0.0.1");

        var msgCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(msgCaptor.capture());
        assertEquals("noreply@qq.com", msgCaptor.getValue().getFrom());
        assertArrayEquals(new String[]{"admin@acme.test"}, msgCaptor.getValue().getTo());
        String body = msgCaptor.getValue().getText();
        assertNotNull(body);
        var matcher = java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(body);
        assertTrue(matcher.find(), "Email body should contain a 6-digit code");
        String code = matcher.group(1);

        verify(store).insert(anyString(), eq("admin@acme.test"), eq("SIGNUP"),
                eq(EmailVerificationService.hash("admin@acme.test", "SIGNUP", code)),
                eq("127.0.0.1"), any(), any());

        // Cooldown blocks immediate resend
        when(store.countRecentByEmail(eq("admin@acme.test"), eq("SIGNUP"), any())).thenReturn(1L);
        var cooldown = assertThrows(IdentityError.class, () -> service.sendSignupCode("admin@acme.test", "127.0.0.1"));
        assertEquals("EMAIL_CODE_COOLDOWN", cooldown.code);

        // Verify valid code succeeds and consumes
        when(store.latestActive(eq("admin@acme.test"), eq("SIGNUP"), any()))
                .thenReturn(Optional.of(new EmailVerificationStore.CodeRecord("rec-1",
                        EmailVerificationService.hash("admin@acme.test", "SIGNUP", code), 0)));
        when(store.consume(eq("rec-1"), any(), eq(5))).thenReturn(true);
        assertDoesNotThrow(() -> service.verifyAndConsumeSignupCode("admin@acme.test", code));

        // Wrong code increments attempts
        var wrong = assertThrows(IdentityError.class, () -> service.verifyAndConsumeSignupCode("admin@acme.test", "000000"));
        assertEquals("INVALID_EMAIL_CODE", wrong.code);
        verify(store).incrementAttempts("rec-1");
    }

    @Test
    void publicSelfSignupVerifiesCaptchaAndEmailCodeThenLogsIn() {
        var staff = mock(StaffService.class);
        var captcha = mock(ArithmeticCaptchaService.class);
        var emailVerification = mock(EmailVerificationService.class);
        var settings = new IdentityConfiguration.Settings(ORIGIN, "", "", "", Duration.ofSeconds(60), true, true, "site-demo");
        var controller = new PublicOnboardingController(staff, captcha, emailVerification, settings, CLOCK);

        // Invalid captcha blocks sending email code
        var emailReq = new MockHttpServletRequest("POST", "/api/v1/public/email-codes");
        emailReq.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        when(captcha.verifyAndConsume("cap-1", "99")).thenReturn(false);
        var badCap = assertThrows(IdentityError.class, () -> controller.sendEmailCode(
                new PublicOnboardingController.EmailCodeRequest("admin@acme.test", "cap-1", "99"), emailReq));
        assertEquals("INVALID_CAPTCHA", badCap.code);
        verifyNoInteractions(emailVerification);

        // Valid captcha sends email code
        when(captcha.verifyAndConsume("cap-2", "12")).thenReturn(true);
        var sent = controller.sendEmailCode(
                new PublicOnboardingController.EmailCodeRequest("admin@acme.test", "cap-2", "12"), emailReq);
        assertEquals(HttpStatus.OK, sent.getStatusCode());
        verify(emailVerification).sendSignupCode(eq("admin@acme.test"), any());

        // Registration verifies email code, provisions tenant, and sets auth cookies
        var access = new AccessJwt.Token("jwt-token", CLOCK.instant().plusSeconds(600), "sid-1");
        var issued = new WebTokens.Issued(IdentityTokens.generate(), "sid-1", CLOCK.instant().plusSeconds(28800), access);
        when(staff.login("acme", "admin@acme.test", "StrongPass!2026")).thenReturn(issued);

        var req = new MockHttpServletRequest("POST", "/api/v1/public/tenants/register");
        req.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        req.setRemoteAddr("127.0.0.1");

        var response = controller.register(
                new PublicOnboardingController.SelfSignup("acme", "Acme Corp", "admin@acme.test", "StrongPass!2026", "123456"), req);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("acme", response.getBody().code());
        assertEquals("jwt-token", response.getBody().access().accessToken());
        var cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(cookies);
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("__Host-newcrisp_staff_rt=")));
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("__Host-newcrisp_staff_csrf=")));
        verify(emailVerification).verifyAndConsumeSignupCode("admin@acme.test", "123456");
        verify(staff).provision("acme", "Acme Corp", "admin@acme.test", "StrongPass!2026");

        var cfg = controller.config().getBody();
        assertTrue(cfg.selfSignupEnabled());
        assertTrue(cfg.signupVerificationEnabled());
        assertEquals("site-demo", cfg.landingDemoSiteId());
    }

    @Test
    void publicSelfSignupBlocksReservedCodesAndDisabledFlag() {
        var staff = mock(StaffService.class);
        var captcha = mock(ArithmeticCaptchaService.class);
        var emailVerification = mock(EmailVerificationService.class);
        var enabled = new PublicOnboardingController(staff, captcha, emailVerification,
                new IdentityConfiguration.Settings(ORIGIN, "", "", "", Duration.ofSeconds(60), true, false, ""), CLOCK);
        var req = new MockHttpServletRequest("POST", "/api/v1/public/tenants/register");
        req.addHeader(HttpHeaders.ORIGIN, ORIGIN);

        var reserved = assertThrows(IdentityError.class, () -> enabled.register(
                new PublicOnboardingController.SelfSignup("platform", "Bad", "a@b.test", "StrongPass!2026", null), req));
        assertEquals("RESERVED_TENANT_CODE", reserved.code);

        var disabled = new PublicOnboardingController(staff, captcha, emailVerification,
                new IdentityConfiguration.Settings(ORIGIN, "", "", "", Duration.ofSeconds(60), false, false, ""), CLOCK);
        var blocked = assertThrows(IdentityError.class, () -> disabled.register(
                new PublicOnboardingController.SelfSignup("acme", "Acme", "a@b.test", "StrongPass!2026", null), req));
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

        var loginReq = new MockHttpServletRequest("POST", "/api/v1/platform/session");
        loginReq.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        var loginResp = controller.login(new PlatformController.PlatformLogin(rawKey), loginReq);
        assertEquals(HttpStatus.OK, loginResp.getStatusCode());
        String setCookie = loginResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith("__Host-newcrisp_platform="));
        assertTrue(setCookie.contains("HttpOnly"));
        String cookieVal = setCookie.substring("__Host-newcrisp_platform=".length(), setCookie.indexOf(';'));

        var meReq = new MockHttpServletRequest("GET", "/api/v1/platform/me");
        meReq.setCookies(new Cookie("__Host-newcrisp_platform", cookieVal));
        assertTrue(controller.me(meReq).getBody().authenticated());

        when(store.updateTenantStatus(eq("acme"), eq("DISABLED"), any())).thenReturn(true);
        var patchReq = new MockHttpServletRequest("PATCH", "/api/v1/platform/tenants/acme/status");
        patchReq.setCookies(new Cookie("__Host-newcrisp_platform", cookieVal));
        patchReq.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        assertEquals(HttpStatus.NO_CONTENT,
                controller.updateStatus("acme", new PlatformController.StatusUpdate("DISABLED"), patchReq).getStatusCode());

        var bearerReq = new MockHttpServletRequest("POST", "/api/v1/platform/tenants");
        bearerReq.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + rawKey);
        var created = controller.create(
                new PlatformController.Onboarding("beta", "Beta", "admin@beta.test", "StrongPass!2026"), bearerReq);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        verify(staff).provision("beta", "Beta", "admin@beta.test", "StrongPass!2026");

        var badLogin = new MockHttpServletRequest("POST", "/api/v1/platform/session");
        badLogin.addHeader(HttpHeaders.ORIGIN, ORIGIN);
        assertThrows(IdentityError.class, () -> controller.login(new PlatformController.PlatformLogin(IdentityTokens.generate()), badLogin));
    }
}
