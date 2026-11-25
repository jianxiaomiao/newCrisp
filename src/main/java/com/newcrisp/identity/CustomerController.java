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
    private final ChatIdentity identity;

    CustomerController(NexusLaunchService nexus, WebTokens tokens, Clock clock, ChatIdentity identity) {
        this.nexus = nexus;
        this.tokens = tokens;
        this.clock = clock;
        this.identity = identity;
    }

    @PostMapping(value = "/integrations/nexus/launch-grants", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GrantResponse> issue(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                        @Valid @RequestBody GrantInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new GrantResponse(nexus.issue(auth, input.userId(), input.sessionId(), input.profile())));
    }

    @PostMapping(value = "/integrations/nexus/unread-summary", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UnreadResponse> unread(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                         @Valid @RequestBody UnreadInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new UnreadResponse(nexus.unread(auth, input.userId())));
    }

    @PostMapping(value = "/launch/nexus", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> redeem(HttpServletRequest request) {
        String[] grants = request.getParameterValues("grant");
        String[] embeds = request.getParameterValues("embed");
        boolean embed = embeds != null && embeds.length == 1 && "1".equals(embeds[0]);
        int expectedParams = embed ? 2 : 1;
        if (request.getQueryString() != null || grants == null || grants.length != 1 || request.getParameterMap().size() != expectedParams) {
            throw IdentityError.invalidGrant();
        }
        String origin = IdentityHttp.origin(request);
        var launch = nexus.redeem(grants[0], origin);
        var headers = IdentityHttp.refreshHeaders(ChatIdentity.Side.CUSTOMER, true, launch, clock.instant());
        if (embed) {
            headers.add("Content-Security-Policy", "frame-ancestors " + origin + "; object-src 'none'; base-uri 'none'");
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).cacheControl(CacheControl.noStore())
                .location(URI.create(embed ? "/customer.html?embed=1" : "/customer.html"))
                .headers(headers)
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

    @PutMapping(value = "/api/v1/customer/context", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> updateContext(@RequestBody ContextInput input, HttpServletRequest request) {
        identity.requireBrowserOrigin(request);
        String sid = tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), ChatIdentity.Side.CUSTOMER).subject().sid();
        nexus.updateContext(sid, input.displayName(), input.email(), input.pageUrl(), request.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private NexusStore.CustomerProfile profile(HttpServletRequest request) {
        String sid = tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), ChatIdentity.Side.CUSTOMER).subject().sid();
        return nexus.profile(sid);
    }

    record GrantInput(@Positive long userId, @Positive long sessionId,
                      @Valid NexusLaunchService.CustomerProfileInput profile) {}
    record GrantResponse(String grant) {}
    record UnreadInput(@Positive long userId) {}
    record UnreadResponse(long unread) {}
    record ContextInput(String displayName, String email, String pageUrl) {}
}
