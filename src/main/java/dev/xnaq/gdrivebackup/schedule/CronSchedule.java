package dev.xnaq.gdrivebackup.schedule;

import java.time.ZonedDateTime;
import java.util.BitSet;

/**
 * A small 5-field cron implementation (minute, hour, day-of-month, month,
 * day-of-week). It supports '*' , lists, ranges, and '/' steps.
 */
public final class CronSchedule {
    private static final int MAX_ITERATIONS = 366 * 24 * 60 * 5;

    private final BitSet minutes = new BitSet(60);
    private final BitSet hours = new BitSet(24);
    private final BitSet daysOfMonth = new BitSet(32);
    private final BitSet months = new BitSet(13);
    private final BitSet daysOfWeek = new BitSet(7);
    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;
    private final String expression;

    private CronSchedule(String expression, String[] fields) {
        this.expression = expression;
        parseField(fields[0], 0, 59, minutes, false);
        parseField(fields[1], 0, 23, hours, false);
        parseField(fields[2], 1, 31, daysOfMonth, false);
        parseField(fields[3], 1, 12, months, false);
        parseField(fields[4], 0, 7, daysOfWeek, true);
        this.dayOfMonthRestricted = !fields[2].equals("*");
        this.dayOfWeekRestricted = !fields[4].equals("*");
    }

    public static CronSchedule parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Cron expression is empty");
        }
        String[] fields = expression.trim().split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("Cron expression must have exactly 5 fields: " + expression);
        }
        return new CronSchedule(expression.trim(), fields);
    }

    public boolean matches(ZonedDateTime time) {
        if (!minutes.get(time.getMinute()) || !hours.get(time.getHour())
                || !months.get(time.getMonthValue())) {
            return false;
        }
        return matchesDay(time);
    }

    public ZonedDateTime next(ZonedDateTime after) {
        ZonedDateTime candidate = after.plusMinutes(1).withSecond(0).withNano(0);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (!months.get(candidate.getMonthValue())) {
                candidate = candidate.plusMonths(1)
                        .withDayOfMonth(1)
                        .withHour(0)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
                continue;
            }
            if (!matchesDay(candidate)) {
                candidate = candidate.plusDays(1)
                        .withHour(0)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
                continue;
            }
            if (!hours.get(candidate.getHour())) {
                candidate = candidate.plusHours(1)
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
                continue;
            }
            if (!minutes.get(candidate.getMinute())) {
                candidate = candidate.plusMinutes(1);
                continue;
            }
            return candidate;
        }
        throw new IllegalStateException("Cron expression did not match within five years: " + expression);
    }

    public String expression() {
        return expression;
    }

    private boolean matchesDay(ZonedDateTime time) {
        boolean dom = daysOfMonth.get(time.getDayOfMonth());
        boolean dow = daysOfWeek.get(time.getDayOfWeek().getValue() % 7);

        if (dayOfMonthRestricted && dayOfWeekRestricted) {
            return dom || dow;
        }
        if (dayOfMonthRestricted) {
            return dom;
        }
        if (dayOfWeekRestricted) {
            return dow;
        }
        return true;
    }

    private static void parseField(String field, int min, int max, BitSet target, boolean dayOfWeek) {
        if (field.isBlank()) {
            throw new IllegalArgumentException("Empty cron field");
        }

        for (String part : field.split(",")) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("Empty cron list element in: " + field);
            }

            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                if (slash == 0 || slash == part.length() - 1) {
                    throw new IllegalArgumentException("Invalid cron step: " + part);
                }
                step = Integer.parseInt(part.substring(slash + 1));
                range = part.substring(0, slash);
                if (step <= 0) {
                    throw new IllegalArgumentException("Cron step must be positive: " + part);
                }
            }

            int start;
            int end;
            if (range.equals("*")) {
                start = min;
                end = max;
            } else {
                int dash = range.indexOf('-');
                if (dash > 0) {
                    start = Integer.parseInt(range.substring(0, dash));
                    end = Integer.parseInt(range.substring(dash + 1));
                } else {
                    start = Integer.parseInt(range);
                    end = start;
                }
            }

            if (start < min || end > max || start > end) {
                throw new IllegalArgumentException(
                        "Cron value out of range [" + min + ", " + max + "]: " + part);
            }

            for (int value = start; value <= end; value += step) {
                int normalized = dayOfWeek && value == 7 ? 0 : value;
                target.set(normalized);
            }
        }

        if (target.isEmpty()) {
            throw new IllegalArgumentException("Cron field has no values: " + field);
        }
    }
}
