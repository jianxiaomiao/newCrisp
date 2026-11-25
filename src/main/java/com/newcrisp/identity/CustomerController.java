package com.newcrisp.identity;

import java.net.URI;
import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class CustomerController {
    private final NexusLaunchService nexus;
    private final IdentityConfiguration.Settings settings;
    private final ApplicationEventPublisher events;

    CustomerController(NexusLaunchService nexus, IdentityConfiguration.Settings settings, ApplicationEventPublisher events) {
        this.nexus = nexus;
        this.settings = settings;
        this.events = events;
    }

    @PostMapping(value = "/integrations/nexus/launch-grants", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GrantResponse> issue(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                        @Valid @RequestBody GrantInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new GrantResponse(nexus.issue(auth, input.userId(), input.sessionId())));
    }

    @PostMapping(value = "/launch/nexus", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> redeem(HttpServletRequest request) {
        String[] grants = request.getParameterValues("grant");
        if (request.getQueryString() != null || grants == null || grants.length != 1 || request.getParameterMap().size() != 1) {
            throw IdentityError.invalidGrant();
        }
        String token = nexus.redeem(grants[0], IdentityHttp.origin(request));
        return ResponseEntity.status(HttpStatus.SEE_OTHER).cacheControl(CacheControl.noStore())
                .location(URI.create("/customer.html"))
                .header(HttpHeaders.SET_COOKIE, IdentityHttp.cookie(IdentityHttp.CUSTOMER, token, NexusLaunchService.SESSION_TTL))
                .build();
    }

    @GetMapping("/api/v1/customer/me")
    ResponseEntity<NexusStore.CustomerProfile> me(HttpServletRequest request) {
        String token = IdentityHttp.token(request, IdentityHttp.CUSTOMER);
        var profile = nexus.profile(token);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(IdentityHttp.CSRF, IdentityHttp.csrf(token))
                .body(profile);
    }

    @GetMapping("/api/v1/customer/tenants/{tenantCode}/me")
    ResponseEntity<NexusStore.CustomerProfile> tenantProfile(@PathVariable String tenantCode, HttpServletRequest request) {
        var profile = profile(request);
        if (!profile.tenantCode().equals(tenantCode)) throw IdentityError.notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile);
    }

    @DeleteMapping("/api/v1/customer/session")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        IdentityHttp.requireOrigin(request, settings.publicOrigin());
        String token = IdentityHttp.token(request, IdentityHttp.CUSTOMER);
        IdentityHttp.requireCsrf(request, token);
        nexus.logout(token);
        events.publishEvent(new ChatIdentity.SessionEnded(new ChatIdentity.Credential(ChatIdentity.Side.CUSTOMER, token)));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, IdentityHttp.cookie(IdentityHttp.CUSTOMER, "", Duration.ZERO)).build();
    }

    private NexusStore.CustomerProfile profile(HttpServletRequest request) {
        return nexus.profile(IdentityHttp.token(request, IdentityHttp.CUSTOMER));
    }

    record GrantInput(@Positive long userId, @Positive long sessionId) {}
    record GrantResponse(String grant) {}
}
