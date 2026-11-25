package com.newcrisp.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class IdleConversationWorker {
    private static final Logger log = LoggerFactory.getLogger(IdleConversationWorker.class);
    private final ChatStore store;
    private final ChatService chat;

    IdleConversationWorker(ChatStore store, ChatService chat) {
        this.store = store;
        this.chat = chat;
    }

    @Scheduled(fixedDelayString = "${newcrisp.chat.auto-close-ms:60000}", initialDelay = 15000)
    void run() {
        for (var tenant : store.tenantsWithAutoClose()) {
            try {
                chat.autoCloseIdle(tenant.tenantId(), tenant.minutes());
            } catch (RuntimeException failure) {
                log.warn("Auto-close worker failed for tenant {}", tenant.tenantId(), failure);
            }
        }
    }
}
