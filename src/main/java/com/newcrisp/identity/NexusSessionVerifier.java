package com.newcrisp.identity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class NexusSessionVerifier {
    private final IdentityConfiguration.Settings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    NexusSessionVerifier(IdentityConfiguration.Settings settings) { this.settings = settings; }

    void requireConfigured() {
        if (settings.nexusVerifyUrl().isEmpty()) throw IdentityError.unavailable();
    }

    boolean active(long userId, long sessionId) {
        requireConfigured();
        // Only server-derived numeric identifiers enter this back-channel request.
        var request = HttpRequest.newBuilder(URI.create(settings.nexusVerifyUrl())).timeout(Duration.ofSeconds(4))
                .header("Authorization", "Bearer " + settings.nexusVerifyKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":" + userId + ",\"sessionId\":" + sessionId + "}"))
                .build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 204) return true;
            if (status == 404) return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException unavailable) {
            throw IdentityError.unavailable();
        }
        throw IdentityError.unavailable();
    }
}
