package com.newcrisp.chat;

import static com.newcrisp.chat.ChatModels.*;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Storage SPI for chat attachments. Currently backed by MySQL ({@code conversation_attachment}),
 * designed so a local-disk implementation with periodic TTL cleanup can be plugged in later
 * without changing {@link ChatService} or {@link ChatFiles}.
 */
public interface AttachmentStorage {
    String store(long tenantId, String conversationId, long seq, Upload upload);

    Optional<byte[]> load(long tenantId, String conversationId, long seq, String storageKey);
}

@Component
class DatabaseAttachmentStorage implements AttachmentStorage {
    private final JdbcClient jdbc;

    DatabaseAttachmentStorage(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String store(long tenantId, String conversationId, long seq, Upload upload) {
        jdbc.sql("INSERT INTO conversation_attachment (tenant_id, conversation_id, seq, content) VALUES (?, ?, ?, ?)")
                .params(tenantId, conversationId, seq, upload.content()).update();
        return null;
    }

    @Override
    public Optional<byte[]> load(long tenantId, String conversationId, long seq, String storageKey) {
        return jdbc.sql("""
                SELECT content FROM conversation_attachment
                WHERE tenant_id = ? AND conversation_id = ? AND seq = ?
                """).params(tenantId, conversationId, seq).query((rs, row) -> rs.getBytes("content")).optional();
    }
}
