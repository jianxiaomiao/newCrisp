package com.newcrisp.identity;

import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff")
class StaffController {
    static final String TENANT_CODE = "[a-z][a-z0-9-]{2,47}";
    private final StaffService staff;
    private final IdentityConfiguration.Settings settings;

    StaffController(StaffService staff, IdentityConfiguration.Settings settings) {
        this.staff = staff;
        this.settings = settings;
    }

    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> login(@Valid @RequestBody Login input, HttpServletRequest request) {
        IdentityHttp.requireOrigin(request, settings.publicOrigin());
        String token = staff.login(input.tenantCode(), input.email(), input.password());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, IdentityHttp.cookie(IdentityHttp.STAFF, token, StaffService.SESSION_TTL)).build();
    }

    @GetMapping("/me")
    ResponseEntity<StaffStore.StaffProfile> me(HttpServletRequest request) {
        String token = IdentityHttp.token(request, IdentityHttp.STAFF);
        var profile = staff.profile(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(IdentityHttp.CSRF, IdentityHttp.csrf(token))
                .body(profile);
    }

    @GetMapping("/tenants/{tenantCode}")
    ResponseEntity<TenantView> tenant(@PathVariable String tenantCode, HttpServletRequest request) {
        var profile = staff.profile(IdentityHttp.token(request, IdentityHttp.STAFF));
        if (!profile.tenantCode().equals(tenantCode)) throw IdentityError.notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new TenantView(profile.tenantCode(), profile.tenantName()));
    }

    @DeleteMapping("/session")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        IdentityHttp.requireOrigin(request, settings.publicOrigin());
        String token = IdentityHttp.token(request, IdentityHttp.STAFF);
        IdentityHttp.requireCsrf(request, token);
        staff.logout(token);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, IdentityHttp.cookie(IdentityHttp.STAFF, "", Duration.ZERO)).build();
    }

    record Login(@NotBlank @Pattern(regexp = TENANT_CODE) String tenantCode,
                 @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}
    record TenantView(String code, String name) {}
}
