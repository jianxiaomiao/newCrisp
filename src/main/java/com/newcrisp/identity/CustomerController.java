package com.newcrisp.identity;

import java.net.URI;
import java.time.Clock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class CustomerController {
    private final NexusLaunchService nexus;
    private final WebTokens tokens;
    private final Clock clock;

    CustomerController(NexusLaunchService nexus, WebTokens tokens, Clock clock) {
        this.nexus = nexus;
        this.tokens = tokens;
        this.clock = clock;
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
        var launch = nexus.redeem(grants[0], IdentityHttp.origin(request));
        return ResponseEntity.status(HttpStatus.SEE_OTHER).cacheControl(CacheControl.noStore())
                .location(URI.create("/customer.html"))
                .headers(IdentityHttp.refreshHeaders(ChatIdentity.Side.CUSTOMER, true, launch, clock.instant()))
                .build();
    }

    @GetMapping("/api/v1/customer/me")
    ResponseEntity<NexusStore.CustomerProfile> me(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile(request));
    }

    @GetMapping("/api/v1/customer/tenants/{tenantCode}/me")
    ResponseEntity<NexusStore.CustomerProfile> tenantProfile(@PathVariable String tenantCode, HttpServletRequest request) {
        var profile = profile(request);
        if (!profile.tenantCode().equals(tenantCode)) throw IdentityError.notFound();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile);
    }

    private NexusStore.CustomerProfile profile(HttpServletRequest request) {
        String sid = tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), ChatIdentity.Side.CUSTOMER).subject().sid();
        return nexus.profile(sid);
    }

    record GrantInput(@Positive long userId, @Positive long sessionId) {}
    record GrantResponse(String grant) {}
}
