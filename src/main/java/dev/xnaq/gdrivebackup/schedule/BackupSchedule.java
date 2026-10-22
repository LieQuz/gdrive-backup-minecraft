package dev.xnaq.gdrivebackup.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Either a cron schedule or a fixed interval (for example "6h").
 */
public final class BackupSchedule {
    private static final Pattern INTERVAL = Pattern.compile("^(\\d+)\\s*([smhd])$", Pattern.CASE_INSENSITIVE);

    private final CronSchedule cron;
    private final Duration interval;
    private final String description;

    private BackupSchedule(CronSchedule cron, Duration interval, String description) {
        this.cron = cron;
        this.interval = interval;
        this.description = description;
    }

    public static BackupSchedule parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Schedule is empty");
        }
        String value = expression.trim();

        String alias = switch (value.toLowerCase(Locale.ROOT)) {
            case "@hourly" -> "0 * * * *";
            case "@daily", "@midnight" -> "0 0 * * *";
            case "@weekly" -> "0 0 * * 0";
            case "@monthly" -> "0 0 1 * *";
            case "@yearly", "@annually" -> "0 0 1 1 *";
            default -> null;
        };
        if (alias != null) {
            return new BackupSchedule(CronSchedule.parse(alias), null, value);
        }

        Matcher intervalMatcher = INTERVAL.matcher(value);
        if (intervalMatcher.matches()) {
            long amount = Long.parseLong(intervalMatcher.group(1));
            if (amount <= 0) {
                throw new IllegalArgumentException("Interval must be positive: " + expression);
            }
            Duration duration = switch (intervalMatcher.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalArgumentException("Unknown interval unit: " + expression);
            };
            return new BackupSchedule(null, duration, "every " + value);
        }

        return new BackupSchedule(CronSchedule.parse(value), null, value);
    }

    public Instant next(Instant after, ZoneId zone) {
        if (cron != null) {
            return cron.next(after.atZone(zone)).toInstant();
        }
        return after.plus(interval);
    }

    public String description() {
        return description;
    }
}
