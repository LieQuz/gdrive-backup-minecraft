package dev.xnaq.gdrivebackup.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BackupScheduleTest {
    @Test
    void intervalSchedule() {
        BackupSchedule schedule = BackupSchedule.parse("6h");
        Instant from = Instant.parse("2026-10-22T00:00:00Z");
        assertEquals(Instant.parse("2026-10-22T06:00:00Z"), schedule.next(from, ZoneId.of("UTC")));
    }

    @Test
    void aliasSchedule() {
        BackupSchedule schedule = BackupSchedule.parse("@daily");
        assertEquals(
                "2026-10-23T00:00:00Z",
                schedule.next(Instant.parse("2026-10-22T12:00:00Z"), ZoneId.of("UTC")).toString()
        );
    }
}
