package com.newcrisp.chat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class ServiceHours {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ServiceHours(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Settings get(long tenant) {
        return jdbc.sql("SELECT hours_zone, business_hours, assignment_limit, dispatch_waiting, welcome_message, auto_close_minutes FROM tenant WHERE id = ?")
                .param(tenant).query((rs, row) -> {
                    String hours = rs.getString("business_hours");
                    return new Settings(rs.getString("hours_zone"), hours == null ? null
                            : json.readValue(hours, new TypeReference<List<Window>>() {}),
                            rs.getObject("assignment_limit", Integer.class), rs.getBoolean("dispatch_waiting"),
                            rs.getString("welcome_message"), rs.getObject("auto_close_minutes", Integer.class));
                }).single();
    }

    public void save(long tenant, Settings input) {
        String welcome = input.welcomeMessage() == null || input.welcomeMessage().isBlank() ? null : input.welcomeMessage().strip();
        try {
            if (!ZoneId.getAvailableZoneIds().contains(input.zone())) throw new IllegalArgumentException();
            if (input.maxActive() != null && (input.maxActive() < 1 || input.maxActive() > 1000)) throw new IllegalArgumentException();
            if (input.autoCloseMinutes() != null && (input.autoCloseMinutes() < 5 || input.autoCloseMinutes() > 43200)) {
                throw new IllegalArgumentException();
            }
            if (welcome != null && welcome.codePointCount(0, welcome.length()) > 1000) throw new IllegalArgumentException();
            if (input.windows() != null) {
                var days = new HashSet<Integer>();
                if (input.windows().isEmpty()) throw new IllegalArgumentException();
                for (Window window : input.windows()) {
                    if (window.day() < 1 || window.day() > 7 || !days.add(window.day())
                            || !window.start().matches("[0-9]{2}:[0-9]{2}") || !window.end().matches("[0-9]{2}:[0-9]{2}")
                            || !LocalTime.parse(window.start()).isBefore(LocalTime.parse(window.end()))) throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException invalidInput) {
            throw ChatModels.invalid("INVALID_SUPPORT_SETTINGS");
        }
        // The administrator's update and routing decisions are serialized for this tenant.
        jdbc.sql("SELECT tenant_id FROM chat_routing_lock WHERE tenant_id = ? FOR UPDATE").param(tenant).query(Long.class).single();
        jdbc.sql("UPDATE tenant SET hours_zone = ?, business_hours = ?, assignment_limit = ?, dispatch_waiting = ?, welcome_message = ?, auto_close_minutes = ? WHERE id = ?")
                .params(input.zone(), input.windows() == null ? null : json.writeValueAsString(input.windows()),
                        input.maxActive(), input.fillQueue(), welcome, input.autoCloseMinutes(), tenant).update();
    }

    Status status(long tenant, Instant now) { return status(get(tenant), now); }

    static Status status(Settings settings, Instant now) {
        if (settings.windows() == null) return new Status(true, null, settings.zone());
        var zone = ZoneId.of(settings.zone());
        var local = now.atZone(zone);
        Instant next = null;
        for (int offset = 0; offset <= 7; offset++) {
            var date = local.toLocalDate().plusDays(offset);
            for (Window window : settings.windows()) {
                if (window.day() != date.getDayOfWeek().getValue()) continue;
                var start = ZonedDateTime.of(date, LocalTime.parse(window.start()), zone).toInstant();
                var end = ZonedDateTime.of(date, LocalTime.parse(window.end()), zone).toInstant();
                if (!now.isBefore(start) && now.isBefore(end)) return new Status(true, null, settings.zone());
                if (start.isAfter(now) && (next == null || start.isBefore(next))) next = start;
            }
            if (next != null) break;
        }
        return new Status(false, next, settings.zone());
    }

    public record Settings(String zone, List<Window> windows, Integer maxActive, boolean fillQueue,
                           String welcomeMessage, Integer autoCloseMinutes) {
        public Settings(String zone, List<Window> windows, Integer maxActive, boolean fillQueue) {
            this(zone, windows, maxActive, fillQueue, null, null);
        }
    }
    public record Window(int day, String start, String end) {}
    public record Status(boolean open, Instant nextOpen, String zone) {}
}
