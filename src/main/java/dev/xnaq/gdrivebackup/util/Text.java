package dev.xnaq.gdrivebackup.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Text {
    public static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private Text() {
    }

    public static String sanitize(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        cleaned = cleaned.replaceAll("_+", "_");
        if (cleaned.isBlank()) {
            return fallback;
        }
        return cleaned;
    }

    public static String reason(String value) {
        return sanitize(value, "manual").toLowerCase(Locale.ROOT);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        for (String unit : units) {
            value /= 1024.0;
            if (value < 1024.0 || unit.equals(units[units.length - 1])) {
                return String.format(Locale.ROOT, "%.1f %s", value, unit);
            }
        }
        return bytes + " B";
    }

    public static String timestamp(Instant instant) {
        return FILE_TIMESTAMP.format(instant);
    }

    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
