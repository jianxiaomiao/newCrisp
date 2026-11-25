package com.newcrisp.chat;

import static com.newcrisp.chat.ChatModels.*;

import com.newcrisp.identity.ChatIdentity;
import com.newcrisp.identity.ChatIdentity.Actor;
import com.newcrisp.identity.ChatIdentity.Side;
import com.newcrisp.identity.IdentityError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/{side:customer|staff}")
class ChatController {
    private final ChatIdentity identity;
    private final ChatService chat;
    private final ChatPresence presence;

    ChatController(ChatIdentity identity, ChatService chat, ChatPresence presence) {
        this.identity = identity;
        this.chat = chat;
        this.presence = presence;
    }

    @GetMapping("/conversations")
    ResponseEntity<ConversationPage> list(@PathVariable String side, @RequestParam(required = false) View view,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "30") int limit, HttpServletRequest request) {
        var actor = actor(request, side, false);
        View requested = view == null ? (actor.customer() ? View.CURRENT : View.PENDING) : view;
        return ok(chat.list(actor, requested, cursor, limit));
    }

    @GetMapping("/summary")
    ResponseEntity<Summary> summary(@PathVariable String side, HttpServletRequest request) {
        return ok(chat.summary(actor(request, side, false)));
    }

    @GetMapping("/conversations/{id}")
    ResponseEntity<Detail> detail(@PathVariable String side, @PathVariable String id, HttpServletRequest request) {
        var actor = actor(request, side, false);
        var row = chat.view(actor, id);
        boolean owner = ChatService.owns(actor, row);
        boolean active = row.state() == State.ACTIVE;
        return ok(new Detail(row.id(), row.state(), Long.toString(row.version()), Long.toString(row.lastSeq()),
                Long.toString(row.lastCustomerSeq()), Long.toString(actor.customer() ? row.customerReadSeq() : owner ? row.staffReadSeq() : 0),
                actor.customer() ? row.state() != State.CLOSED : owner && active,
                !actor.customer() && active && actor.supervisorDepartments().contains(row.departmentId()),
                owner && active, chat.assigneeName(row),
                actor.customer() ? null : presence.online(row.tenantId(), row.customerId())));
    }

    @GetMapping("/conversations/{id}/actions")
    ResponseEntity<java.util.List<ActionHistory>> history(@PathVariable String side, @PathVariable String id, HttpServletRequest request) {
        return ok(chat.history(actor(request, side, false), id));
    }

    @GetMapping("/conversations/{id}/messages")
    ResponseEntity<MessagePage> messages(@PathVariable String side, @PathVariable String id,
            @RequestParam(required = false) String afterSeq, @RequestParam(required = false) String beforeSeq,
            @RequestParam(defaultValue = "50") int limit, HttpServletRequest request) {
        return ok(chat.messages(actor(request, side, false), id, afterSeq, beforeSeq, limit));
    }

    @PostMapping("/conversations")
    ResponseEntity<Sent> start(@PathVariable String side, @RequestBody Send input, HttpServletRequest request) {
        var sent = chat.send(actor(request, side, true), null, input);
        return sent(sent);
    }

    @PostMapping("/conversations/{id}/messages")
    ResponseEntity<Sent> send(@PathVariable String side, @PathVariable String id, @RequestBody Send input,
            HttpServletRequest request) {
        return sent(chat.send(actor(request, side, true), id, input));
    }

    @PutMapping("/conversations/{id}/read-position")
    ResponseEntity<Void> read(@PathVariable String side, @PathVariable String id, @RequestBody Read input,
            HttpServletRequest request) {
        chat.read(actor(request, side, true), id, input);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/conversations/{id}/{operation:claim|return|close}")
    ResponseEntity<ActionResult> action(@PathVariable String side, @PathVariable String id, @PathVariable String operation,
            @RequestBody Action input, HttpServletRequest request) {
        if (!"staff".equals(side)) throw IdentityError.notFound();
        return ok(chat.action(actor(request, side, true), id, Operation.valueOf(operation.toUpperCase(java.util.Locale.ROOT)), input));
    }

    private Actor actor(HttpServletRequest request, String side, boolean write) {
        return identity.authenticate(request, Side.fromPath(side), write);
    }

    private static ResponseEntity<Sent> sent(Sent value) {
        return ResponseEntity.status(value.replayed() ? 200 : 201).cacheControl(CacheControl.noStore()).body(value);
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
