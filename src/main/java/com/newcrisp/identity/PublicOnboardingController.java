package com.newcrisp.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public")
class PublicOnboardingController {
    private static final Set<String> RESERVED_CODES = Set.of(
            "admin", "platform", "api", "www", "system", "staff", "guest", "customer",
            "support", "newcrisp", "nexus", "health", "static", "assets", "embed", "launch", "ws", "root"
    );
    private static final int MAX_SIGNUPS_PER_WINDOW = 10;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(10);
    private static final int MAX_TRACKED_IPS = 4096;

    private final StaffService staff;
    private final ArithmeticCaptchaService captcha;
    private final EmailVerificationService emailVerification;
    private final IdentityConfiguration.Settings settings;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> rateLimits = new ConcurrentHashMap<>();

    PublicOnboardingController(StaffService staff, ArithmeticCaptchaService captcha,
                               EmailVerificationService emailVerification,
                               IdentityConfiguration.Settings settings, Clock clock) {
        this.staff = staff;
        this.captcha = captcha;
        this.emailVerification = emailVerification;
        this.settings = settings;
        this.clock = clock;
    }

    @GetMapping("/config")
    ResponseEntity<PublicConfig> config() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PublicConfig(settings.isSelfSignupEnabled(),
                        settings.isSignupVerificationEnabled(),
                        settings.landingDemoSiteId().isEmpty() ? null : settings.landingDemoSiteId()));
    }

    @GetMapping("/captcha")
    ResponseEntity<ArithmeticCaptchaService.CaptchaView> captcha() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(captcha.issue());
    }

    @PostMapping(value = "/email-codes", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EmailCodeSent> sendEmailCode(@Valid @RequestBody EmailCodeRequest input, HttpServletRequest request) {
        IdentityHttp.requireOrigin(request, settings.publicOrigin());
        if (!settings.isSelfSignupEnabled()) {
            throw new IdentityError(HttpStatus.FORBIDDEN, "SELF_SIGNUP_DISABLED");
        }
        if (!captcha.verifyAndConsume(input.captchaId(), input.captchaAnswer())) {
            throw new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_CAPTCHA");
        }
        emailVerification.sendSignupCode(input.email(), request.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new EmailCodeSent((int) EmailVerificationService.EMAIL_COOLDOWN.toSeconds(),
                        (int) EmailVerificationService.CODE_TTL.toSeconds()));
    }

    @PostMapping(value = "/tenants/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RegisteredTenant> register(@Valid @RequestBody SelfSignup input, HttpServletRequest request) {
        IdentityHttp.requireOrigin(request, settings.publicOrigin());
        if (!settings.isSelfSignupEnabled()) {
            throw new IdentityError(HttpStatus.FORBIDDEN, "SELF_SIGNUP_DISABLED");
        }
        if (RESERVED_CODES.contains(input.code())) {
            throw new IdentityError(HttpStatus.BAD_REQUEST, "RESERVED_TENANT_CODE");
        }
        checkRateLimit(request.getRemoteAddr());
        if (settings.isSignupVerificationEnabled()) {
            emailVerification.verifyAndConsumeSignupCode(input.adminEmail(), input.emailCode());
        }
        staff.provision(input.code(), input.name(), input.adminEmail(), input.adminPassword());
        var issued = staff.login(input.code(), input.adminEmail(), input.adminPassword());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .headers(IdentityHttp.refreshHeaders(ChatIdentity.Side.STAFF, false, issued, clock.instant()))
                .body(new RegisteredTenant(input.code(), input.name().strip(), issued.access()));
    }

    private void checkRateLimit(String remoteAddr) {
        String key = remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr;
        Instant now = clock.instant();
        Instant cutoff = now.minus(RATE_WINDOW);
        if (rateLimits.size() > MAX_TRACKED_IPS) {
            rateLimits.clear();
        }
        Deque<Instant> timestamps = rateLimits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_SIGNUPS_PER_WINDOW) {
                throw new IdentityError(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_SIGNUPS");
            }
            timestamps.addLast(now);
        }
    }

    record PublicConfig(boolean selfSignupEnabled, boolean signupVerificationEnabled, String landingDemoSiteId) {}
    record EmailCodeRequest(@NotBlank @Email @Size(max = 254) String email,
                            @NotBlank @Size(max = 64) String captchaId,
                            @NotBlank @Size(max = 16) String captchaAnswer) {}
    record EmailCodeSent(int cooldownSeconds, int expiresInSeconds) {}
    record SelfSignup(@NotBlank @Pattern(regexp = StaffController.TENANT_CODE) String code,
                      @NotBlank @Size(max = 120) String name,
                      @NotBlank @Email @Size(max = 254) String adminEmail,
                      @NotBlank @Size(max = 72) String adminPassword,
                      @Size(max = 12) String emailCode) {}
    record RegisteredTenant(String code, String name, AccessJwt.Token access) {}
}
