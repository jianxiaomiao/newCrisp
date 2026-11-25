package com.newcrisp.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class GuestChatController {
    private final GuestChatService guests;
    private final ChatIdentity identity;
    private final WebTokens tokens;
    private final StaffService staff;

    GuestChatController(GuestChatService guests, ChatIdentity identity, WebTokens tokens, StaffService staff) {
        this.guests = guests;
        this.identity = identity;
        this.tokens = tokens;
        this.staff = staff;
    }

    @GetMapping("/embed/{id}")
    ResponseEntity<Resource> iframe(@PathVariable String id) {
        var site = guests.site(id, false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_HTML)
                .header("Content-Security-Policy", "frame-ancestors " + site.origin() + "; object-src 'none'; base-uri 'none'")
                .header("Referrer-Policy", "no-referrer").body(new ClassPathResource("static/guest.html"));
    }

    @GetMapping("/guest.html")
    ResponseEntity<Void> privateShell() { return ResponseEntity.notFound().build(); }

    @PostMapping("/api/v1/guest/{site}/session")
    ResponseEntity<GuestChatService.Connection> session(@PathVariable String site, @Valid @RequestBody Start input, HttpServletRequest request) {
        identity.requireBrowserOrigin(request);
        return ok(guests.connect(site, input.resume(), input.previousSid()));
    }

    @PostMapping("/api/v1/customer/guest-link")
    ResponseEntity<Void> link(@Valid @RequestBody Link input, HttpServletRequest request) {
        identity.requireBrowserOrigin(request);
        guests.link(tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), ChatIdentity.Side.CUSTOMER).subject().sid(), input.code());
        return SupportSettingsController.done();
    }

    @GetMapping("/api/v1/staff/guest-sites")
    ResponseEntity<List<GuestChatService.Site>> sites(HttpServletRequest request) {
        return ok(guests.sites(admin(request, false)));
    }

    @PostMapping("/api/v1/staff/guest-sites")
    ResponseEntity<GuestChatService.Site> sites(@Valid @RequestBody SiteInput input, HttpServletRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(guests.createSite(admin(request, true), input.origin(), input.title()));
    }

    @DeleteMapping("/api/v1/staff/guest-sites/{id}")
    ResponseEntity<Void> disable(@PathVariable String id, HttpServletRequest request) {
        guests.disableSite(admin(request, true), id);
        return SupportSettingsController.done();
    }

    private long admin(HttpServletRequest request, boolean write) {
        if (write) identity.requireBrowserOrigin(request);
        return staff.admin(tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), ChatIdentity.Side.STAFF).subject().sid()).tenantId();
    }

    private static <T> ResponseEntity<T> ok(T value) { return SupportSettingsController.ok(value); }
    record Start(@Size(max = 43) String resume, @Size(max = 36) String previousSid) {}
    record Link(@NotBlank @Size(max = 90) String code) {}
    record SiteInput(@NotBlank @Size(max = 80) String title, @NotBlank @Size(max = 255) String origin) {}
}
