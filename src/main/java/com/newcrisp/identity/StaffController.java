package com.newcrisp.identity;

import java.time.Clock;
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
    private final WebTokens tokens;
    private final Clock clock;

    StaffController(StaffService staff, IdentityConfiguration.Settings settings, WebTokens tokens, Clock clock) {
        this.staff = staff;
        this.settings = settings;
        this.tokens = tokens;
        this.clock = clock;
    }

    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccessJwt.Token> login(@Valid @RequestBody Login input, HttpServletRequest request) {
        IdentityHttp.requireOrigin(request, settings.publicOrigin());
        var issued = staff.login(input.tenantCode(), input.email(), input.password());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .headers(IdentityHttp.refreshHeaders(ChatIdentity.Side.STAFF, false, issued, clock.instant())).body(issued.access());
    }

    @GetMapping("/me")
    ResponseEntity<StaffStore.StaffProfile> me(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(staff.profile(sid(request)));
    }

    @GetMapping("/tenants/{tenantCode}")
    ResponseEntity<TenantView> tenant(@PathVariable String tenantCode, HttpServletRequest request) {
        var profile = staff.profile(sid(request));
        if (!profile.tenantCode().equals(tenantCode)) throw IdentityError.notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new TenantView(profile.tenantCode(), profile.tenantName()));
    }

    @PostMapping(value = "/members", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MemberCreated> createMember(@Valid @RequestBody Member input, HttpServletRequest request) {
        IdentityHttp.requireOrigin(request, settings.publicOrigin());
        String id = staff.createMember(sid(request), input.departmentCode(), input.email(), input.password(), input.role(), input.displayName());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new MemberCreated(id));
    }

    private String sid(HttpServletRequest request) {
        return tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), ChatIdentity.Side.STAFF).subject().sid();
    }

    record Login(@NotBlank @Pattern(regexp = TENANT_CODE) String tenantCode,
                 @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}
    record TenantView(String code, String name) {}
    record Member(@NotBlank @Pattern(regexp = TENANT_CODE) String departmentCode,
                  @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 72) String password,
                  @NotBlank @Pattern(regexp = "AGENT|SUPERVISOR") String role, @Size(max = 64) String displayName) {}
    record MemberCreated(String staffId) {}
}
