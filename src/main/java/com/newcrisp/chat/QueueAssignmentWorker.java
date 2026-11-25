package com.newcrisp.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class QueueAssignmentWorker {
    private static final Logger log = LoggerFactory.getLogger(QueueAssignmentWorker.class);
    private final ChatStore store;
    private final ChatService chat;

    QueueAssignmentWorker(ChatStore store, ChatService chat) {
        this.store = store;
        this.chat = chat;
    }

    @Scheduled(fixedDelayString = "${newcrisp.chat.queue-dispatch-ms:2000}", initialDelay = 2000)
    void run() {
        for (long tenant : store.tenantsWithWaitingDispatch()) {
            try {
                chat.fillQueue(tenant);
            } catch (RuntimeException failure) {
                log.warn("Waiting conversation dispatch failed for tenant {}", tenant, failure);
            }
        }
    }
}
