package dev.xnaq.gdrivebackup.schedule;

import dev.xnaq.gdrivebackup.util.Log;
import dev.xnaq.gdrivebackup.Config;
import dev.xnaq.gdrivebackup.backup.BackupManager;
import dev.xnaq.gdrivebackup.backup.BackupState;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class ScheduleService {
    private final MinecraftServer server;
    private final BackupManager manager;
    private final BackupState state;
    private ZoneId zone;
    private BackupSchedule schedule;
    private Instant nextRun;
    private Instant warned60;
    private Instant warned10;
    private int ticks;

    public ScheduleService(MinecraftServer server, BackupManager manager, BackupState state) {
        this.server = server;
        this.manager = manager;
        this.state = state;
        this.zone = resolveZone(Config.TIMEZONE.get());
        this.schedule = parseSchedule(Config.SCHEDULE.get());
        try {
            this.nextRun = initialNextRun();
        } catch (RuntimeException e) {
            Log.warn("Schedule cannot produce a next run; falling back to daily at 04:00", e);
            this.schedule = BackupSchedule.parse("0 4 * * *");
            this.nextRun = this.schedule.next(Instant.now(), zone);
        }
    }

    public void start() {
        state.scheduleExpression = Config.SCHEDULE.get();
        saveNextRun();
        Log.info(
                "Scheduled backups: {} ({}) next run at {}",
                schedule.description(), zone, nextRun);
    }

    public void tick() {
        if (++ticks % 20 != 0) {
            return;
        }
        if (!Config.ENABLED.get()) {
            return;
        }

        Instant now = Instant.now();
        if (nextRun == null) {
            nextRun = schedule.next(now, zone);
            saveNextRun();
        }

        long seconds = Duration.between(now, nextRun).getSeconds();
        if (seconds <= 60 && seconds > 10 && !nextRun.equals(warned60)) {
            warned60 = nextRun;
            broadcast("Backup in 60 seconds.");
        } else if (seconds <= 10 && seconds > 0 && !nextRun.equals(warned10)) {
            warned10 = nextRun;
            broadcast("Backup in 10 seconds.");
        }

        if (seconds > 0) {
            return;
        }

        warned60 = null;
        warned10 = null;

        if (manager.isRunning()) {
            Log.warn("Skipping scheduled backup because another backup is still running.");
            nextRun = schedule.next(now, zone);
            saveNextRun();
            return;
        }

        broadcast("Scheduled backup starting.");
        manager.requestBackup("scheduled", null);
        nextRun = schedule.next(now, zone);
        saveNextRun();
    }

    public void reload() {
        this.zone = resolveZone(Config.TIMEZONE.get());
        state.scheduleExpression = Config.SCHEDULE.get();
        BackupSchedule loaded = parseSchedule(Config.SCHEDULE.get());
        try {
            this.nextRun = loaded.next(Instant.now(), zone);
            this.schedule = loaded;
        } catch (RuntimeException e) {
            Log.warn("Schedule cannot produce a next run; falling back to daily at 04:00", e);
            this.schedule = BackupSchedule.parse("0 4 * * *");
            this.nextRun = this.schedule.next(Instant.now(), zone);
        }
        this.warned60 = null;
        this.warned10 = null;
        saveNextRun();
    }

    public void shutdown() {
        saveNextRun();
    }

    public Instant nextRun() {
        return nextRun;
    }

    public String description() {
        return schedule.description() + " (" + zone + ")";
    }

    private Instant initialNextRun() {
        Instant now = Instant.now();
        String configuredExpression = Config.SCHEDULE.get();
        boolean scheduleChanged = state.scheduleExpression != null
                && !state.scheduleExpression.equals(configuredExpression);
        state.scheduleExpression = configuredExpression;
        if (scheduleChanged) {
            Log.info("Backup schedule changed to '{}'; recomputing the next run.", configuredExpression);
            return schedule.next(now, zone);
        }
        if (state.nextRunAt != null && !state.nextRunAt.isBlank()) {
            try {
                Instant persisted = Instant.parse(state.nextRunAt);
                if (!persisted.isBefore(now)) {
                    return persisted;
                }
                if (Config.RUN_MISSED.get()
                        && Duration.between(persisted, now).compareTo(Duration.ofHours(24)) <= 0) {
                    Log.info(
                            "A scheduled backup was missed at {}; running it shortly after startup.", persisted);
                    return now.plusSeconds(60);
                }
            } catch (DateTimeException e) {
                Log.warn("Invalid next-run timestamp in state: {}", state.nextRunAt);
            }
        }
        return schedule.next(now, zone);
    }

    private void saveNextRun() {
        if (nextRun == null) {
            return;
        }
        state.nextRunAt = nextRun.toString();
        try {
            state.save(manager.stateFile());
        } catch (IOException e) {
            Log.warn("Could not save next scheduled run", e);
        }
    }

    private void broadcast(String message) {
        Log.info("[Schedule] {}", message);
        if (!server.getPlayerList().getPlayers().isEmpty()) {
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("[GDrive Backup] " + message), false);
        }
    }

    private static ZoneId resolveZone(String value) {
        try {
            return ZoneId.of(Objects.requireNonNullElse(value, "UTC"));
        } catch (DateTimeException e) {
            Log.warn("Invalid timezone '{}'; using the system timezone", value, e);
            return ZoneId.systemDefault();
        }
    }

    private static BackupSchedule parseSchedule(String value) {
        try {
            return BackupSchedule.parse(value);
        } catch (RuntimeException e) {
            Log.warn(
                    "Invalid schedule '{}'; falling back to daily at 04:00", value, e);
            return BackupSchedule.parse("0 4 * * *");
        }
    }
}
