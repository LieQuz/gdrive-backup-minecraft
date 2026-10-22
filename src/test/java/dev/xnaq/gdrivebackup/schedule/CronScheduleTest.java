package dev.xnaq.gdrivebackup.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class CronScheduleTest {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Test
    void dailyAtFour() {
        CronSchedule schedule = CronSchedule.parse("0 4 * * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 10, 22, 3, 0, 0, 0, TOKYO);
        ZonedDateTime next = schedule.next(from);
        assertEquals(2026, next.getYear());
        assertEquals(10, next.getMonthValue());
        assertEquals(22, next.getDayOfMonth());
        assertEquals(4, next.getHour());
        assertEquals(0, next.getMinute());
    }

    @Test
    void everyFifteenMinutes() {
        CronSchedule schedule = CronSchedule.parse("*/15 * * * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 10, 22, 10, 7, 0, 0, TOKYO);
        ZonedDateTime next = schedule.next(from);
        assertEquals(10, next.getHour());
        assertEquals(15, next.getMinute());
    }

    @Test
    void firstDayOfNextMonth() {
        CronSchedule schedule = CronSchedule.parse("0 0 1 * *");
        ZonedDateTime from = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, TOKYO);
        ZonedDateTime next = schedule.next(from);
        assertEquals(2, next.getMonthValue());
        assertEquals(1, next.getDayOfMonth());
        assertEquals(0, next.getHour());
    }

    @Test
    void sundayAndSevenAreEquivalent() {
        ZonedDateTime sunday = ZonedDateTime.of(2026, 10, 25, 0, 0, 0, 0, TOKYO);
        assertTrue(CronSchedule.parse("0 0 * * 0").matches(sunday));
        assertTrue(CronSchedule.parse("0 0 * * 7").matches(sunday));
    }

    @Test
    void dayOfMonthAndDayOfWeekUseOrSemantics() {
        CronSchedule schedule = CronSchedule.parse("0 0 1 * 0");
        assertTrue(schedule.matches(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, TOKYO)));
        assertTrue(schedule.matches(ZonedDateTime.of(2026, 11, 8, 0, 0, 0, 0, TOKYO)));
        assertEquals(8, schedule.next(ZonedDateTime.of(2026, 11, 2, 0, 0, 0, 0, TOKYO)).getDayOfMonth());
    }

    @Test
    void invalidExpressionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("0 4 * *"));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("60 * * * *"));
    }
}
