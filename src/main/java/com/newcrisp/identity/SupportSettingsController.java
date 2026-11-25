package com.newcrisp.identity;

import com.newcrisp.chat.ServiceHours;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff")
class SupportSettingsController {
    private final ChatIdentity identity;
    private final StaffService staff;
    private final WebTokens tokens;
    private final JdbcClient jdbc;
    private final ServiceHours hours;

    SupportSettingsController(ChatIdentity identity, StaffService staff, WebTokens tokens, JdbcClient jdbc, ServiceHours hours) {
        this.identity = identity;
        this.staff = staff;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.hours = hours;
    }

    @GetMapping("/support-settings")
    ResponseEntity<ServiceHours.Settings> settings(HttpServletRequest request) {
        return ok(hours.get(admin(request, false)));
    }

    @PutMapping("/support-settings")
    @Transactional
    ResponseEntity<Void> save(@RequestBody ServiceHours.Settings input, HttpServletRequest request) {
        hours.save(admin(request, true), input);
        return done();
    }

    @GetMapping("/quick-replies")
    ResponseEntity<List<QuickReply>> replies(HttpServletRequest request) {
        var actor = identity.authenticate(request, ChatIdentity.Side.STAFF, false);
        return ok(jdbc.sql("""
                SELECT id, title, body, owner_staff_id IS NOT NULL AS personal
                FROM quick_reply
                WHERE tenant_id = ? AND (owner_staff_id IS NULL OR owner_staff_id = ?)
                ORDER BY personal ASC, title, id
                """).params(actor.tenantId(), actor.id())
                .query((rs, row) -> new QuickReply(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4))).list());
    }

    @PostMapping("/quick-replies")
    ResponseEntity<QuickReply> create(@Valid @RequestBody ReplyInput input, HttpServletRequest request) {
        long tenant = admin(request, true);
        var reply = new QuickReply(UUID.randomUUID().toString(), input.title().strip(), input.body().strip(), false);
        jdbc.sql("INSERT INTO quick_reply (tenant_id, id, title, body, owner_staff_id) VALUES (?, ?, ?, ?, NULL)")
                .params(tenant, reply.id(), reply.title(), reply.body()).update();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(reply);
    }

    @PutMapping("/quick-replies/{id}")
    ResponseEntity<Void> edit(@PathVariable String id, @Valid @RequestBody ReplyInput input, HttpServletRequest request) {
        int count = jdbc.sql("UPDATE quick_reply SET title = ?, body = ? WHERE tenant_id = ? AND id = ? AND owner_staff_id IS NULL")
                .params(input.title().strip(), input.body().strip(), admin(request, true), id).update();
        if (count == 0) throw IdentityError.notFound();
        return done();
    }

    @DeleteMapping("/quick-replies/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        if (jdbc.sql("DELETE FROM quick_reply WHERE tenant_id = ? AND id = ? AND owner_staff_id IS NULL")
                .params(admin(request, true), id).update() == 0) {
            throw IdentityError.notFound();
        }
        return done();
    }

    @PostMapping("/quick-replies/personal")
    ResponseEntity<QuickReply> createPersonal(@Valid @RequestBody ReplyInput input, HttpServletRequest request) {
        var actor = identity.authenticate(request, ChatIdentity.Side.STAFF, true);
        var reply = new QuickReply(UUID.randomUUID().toString(), input.title().strip(), input.body().strip(), true);
        jdbc.sql("INSERT INTO quick_reply (tenant_id, id, title, body, owner_staff_id) VALUES (?, ?, ?, ?, ?)")
                .params(actor.tenantId(), reply.id(), reply.title(), reply.body(), actor.id()).update();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(reply);
    }

    @DeleteMapping("/quick-replies/personal/{id}")
    ResponseEntity<Void> deletePersonal(@PathVariable String id, HttpServletRequest request) {
        var actor = identity.authenticate(request, ChatIdentity.Side.STAFF, true);
        if (jdbc.sql("DELETE FROM quick_reply WHERE tenant_id = ? AND id = ? AND owner_staff_id = ?")
                .params(actor.tenantId(), id, actor.id()).update() == 0) {
            throw IdentityError.notFound();
        }
        return done();
    }

    private long admin(HttpServletRequest request, boolean write) {
        if (write) identity.requireBrowserOrigin(request);
        return staff.admin(tokens.access(request.getHeader(HttpHeaders.AUTHORIZATION), ChatIdentity.Side.STAFF).subject().sid()).tenantId();
    }

    static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    static ResponseEntity<Void> done() { return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }

    record ReplyInput(@NotBlank @Size(max = 80) String title, @NotBlank @Size(max = 4000) String body) {}
    record QuickReply(String id, String title, String body, boolean personal) {}
}
