package com.newcrisp.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/platform/tenants", consumes = MediaType.APPLICATION_JSON_VALUE)
class PlatformController {
    private final StaffService staff;
    private final NexusLaunchService nexus;
    private final byte[] platformDigest;

    PlatformController(StaffService staff, NexusLaunchService nexus, IdentityConfiguration.Settings settings) {
        this.staff = staff;
        this.nexus = nexus;
        this.platformDigest = settings.platformDigest();
    }

    @PostMapping
    ResponseEntity<TenantCreated> create(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                         @Valid @RequestBody Onboarding input) {
        authorize(auth);
        staff.provision(input.code(), input.name(), input.adminEmail(), input.adminPassword());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new TenantCreated(input.code(), input.name().strip()));
    }

    @PostMapping("/{tenantCode}/nexus-integrations")
    ResponseEntity<NexusLaunchService.IntegrationCreated> integration(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
            @PathVariable String tenantCode, @Valid @RequestBody IntegrationInput input) {
        authorize(auth);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(nexus.configure(tenantCode, input.nexusOrigin()));
    }

    private void authorize(String authorization) {
        if (platformDigest.length == 0 || !IdentityTokens.matches(IdentityTokens.bearer(authorization), platformDigest)) {
            throw IdentityError.unauthorized();
        }
    }

    record Onboarding(@NotBlank @Pattern(regexp = StaffController.TENANT_CODE) String code,
                      @NotBlank @Size(max = 120) String name, @NotBlank @Email @Size(max = 254) String adminEmail,
                      @NotBlank @Size(max = 72) String adminPassword) {}
    record TenantCreated(String code, String name) {}
    record IntegrationInput(@NotBlank @Size(max = 255) String nexusOrigin) {}
}
