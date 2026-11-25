package com.newcrisp.identity;

import com.newcrisp.identity.ChatIdentity.Side;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/{side:customer|staff}")
class WebSessionController {
    private final ChatIdentity identity;
    private final WebTokens tokens;
    private final Clock clock;

    WebSessionController(ChatIdentity identity, WebTokens tokens, Clock clock) {
        this.identity = identity;
        this.tokens = tokens;
        this.clock = clock;
    }

    @PostMapping("/session/refresh")
    ResponseEntity<AccessJwt.Token> refresh(@PathVariable String side, HttpServletRequest request) {
        identity.requireBrowserOrigin(request);
        Side role = Side.fromPath(side);
        boolean bootstrap = bootstrap(request, role);
        String raw = IdentityHttp.token(request, IdentityHttp.refreshName(role, bootstrap));
        IdentityHttp.requireCsrf(request, raw);
        var issued = tokens.rotate(role, raw, bootstrap);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .headers(IdentityHttp.refreshHeaders(role, false, issued, clock.instant())).body(issued.access());
    }

    @DeleteMapping("/session")
    ResponseEntity<Void> logout(@PathVariable String side, HttpServletRequest request) {
        identity.requireBrowserOrigin(request);
        Side role = Side.fromPath(side);
        boolean bootstrap = bootstrap(request, role);
        String raw = IdentityHttp.token(request, IdentityHttp.refreshName(role, bootstrap));
        IdentityHttp.requireCsrf(request, raw);
        tokens.logout(role, raw, bootstrap);
        // A consumed bootstrap cookie can coexist briefly with the promoted session.
        // Clearing the selected pair avoids overwriting a newer, concurrent Nexus launch.
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .headers(IdentityHttp.clearRefresh(role, bootstrap)).build();
    }

    @PostMapping("/ws-ticket")
    ResponseEntity<ChatIdentity.SocketTicket> ticket(@PathVariable String side, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identity.issueSocket(request, Side.fromPath(side)));
    }

    private static boolean bootstrap(HttpServletRequest request, Side side) {
        String value = request.getHeader("X-NewCrisp-Launch");
        if (value == null) return false;
        if (!"1".equals(value) || side != Side.CUSTOMER) throw IdentityError.unauthorized();
        return true;
    }
}
