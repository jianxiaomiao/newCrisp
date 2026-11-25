package com.newcrisp.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.newcrisp.identity.ChatIdentity;
import com.newcrisp.identity.IdentityError;
import static com.newcrisp.chat.ChatModels.*;

@RestController
@RequestMapping("/api/v1/{side:customer|staff}/conversations")
class ChatFiles {
    private final ChatIdentity identity;
    private final ChatService chat;

    ChatFiles(ChatIdentity identity, ChatService chat) {
        this.identity = identity;
        this.chat = chat;
    }

    @PostMapping(value = {"/attachments", "/{id}/attachments"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Sent> upload(@PathVariable String side, @PathVariable(required = false) String id,
            @RequestParam String clientMessageId, @RequestParam(required = false) String stateVersion,
            @RequestParam MultipartFile file, HttpServletRequest request) throws IOException {
        var actor = identity.authenticate(request, ChatIdentity.Side.fromPath(side), true);
        var upload = validate(file);
        var sent = chat.sendAttachment(actor, id, new Send(clientMessageId, "附件：" + upload.info().name(), stateVersion), upload);
        return ResponseEntity.status(sent.replayed() ? 200 : 201).cacheControl(CacheControl.noStore()).body(sent);
    }

    @GetMapping("/{id}/attachments/{seq}")
    ResponseEntity<byte[]> download(@PathVariable String side, @PathVariable String id, @PathVariable String seq,
                                    HttpServletRequest request) {
        var actor = identity.authenticate(request, ChatIdentity.Side.fromPath(side), false);
        var file = chat.attachment(actor, id, seq);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.info().type()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.info().name(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }

    static Upload validate(MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > 5 * 1024 * 1024) throw invalid("INVALID_ATTACHMENT_SIZE");
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 180 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl)) throw invalid("INVALID_ATTACHMENT_NAME");
        var content = file.getBytes();
        String type;
        if (starts(content, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) type = "image/png";
        else if (starts(content, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) type = "image/jpeg";
        else if (starts(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) type = "application/pdf";
        else throw new IdentityError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_ATTACHMENT_TYPE");
        try {
            return new Upload(new AttachmentInfo(name.strip(), type, content.length), content, MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean starts(byte[] content, byte[] magic) {
        return content.length >= magic.length && Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
    }
}
