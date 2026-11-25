package com.newcrisp.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceHoursTest {
    @Test
    void nextOpeningUsesTenantTimeAndTreatsClosingTimeAsExclusive() {
        var schedule = new ServiceHours.Settings("Asia/Shanghai",
                List.of(new ServiceHours.Window(3, "09:00", "18:00"), new ServiceHours.Window(5, "10:00", "18:00")),
                null, true);
        assertThat(ServiceHours.status(schedule, Instant.parse("2026-11-25T09:00:00Z")).open()).isTrue();
        var closed = ServiceHours.status(schedule, Instant.parse("2026-11-25T10:00:00Z"));
        assertThat(closed.open()).isFalse();
        assertThat(closed.nextOpen()).isEqualTo(Instant.parse("2026-11-27T02:00:00Z"));
    }
}
