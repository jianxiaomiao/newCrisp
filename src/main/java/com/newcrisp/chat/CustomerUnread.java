package com.newcrisp.chat;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerUnread {
    private static final String COUNT = """
            SELECT COUNT(*) FROM conversation c JOIN conversation_message m
            ON m.tenant_id = c.tenant_id AND m.conversation_id = c.id
                AND m.staff_sender_id IS NOT NULL AND m.seq > c.customer_read_seq
            """;

    private final JdbcClient jdbc;

    CustomerUnread(JdbcClient jdbc) { this.jdbc = jdbc; }

    long forCustomer(long tenantId, String customerId) {
        return jdbc.sql(COUNT + " WHERE c.tenant_id = ? AND c.customer_id = ?")
                .params(tenantId, customerId).query(Long.class).single();
    }

    public long forNexusUser(long tenantId, String integrationId, long userId) {
        return jdbc.sql(COUNT + """
                JOIN customer u ON u.tenant_id = c.tenant_id AND u.id = c.customer_id
                WHERE u.tenant_id = ? AND u.integration_id = ? AND u.external_user_id = ?
                """).params(tenantId, integrationId, userId).query(Long.class).single();
    }
}
