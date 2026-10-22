package dev.xnaq.gdrivebackup;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common configuration for GDrive Backup.
 */
public final class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SCHEDULE;
    public static final ModConfigSpec.ConfigValue<String> TIMEZONE;
    public static final ModConfigSpec.ConfigValue<String> SNAPSHOT_MODE;
    public static final ModConfigSpec.ConfigValue<String> SERVER_NAME;

    public static final ModConfigSpec.ConfigValue<String> LOCAL_DIR;
    public static final ModConfigSpec.BooleanValue INCLUDE_MODS;
    public static final ModConfigSpec.BooleanValue INCLUDE_CONFIG;
    public static final ModConfigSpec.IntValue KEEP_LOCAL;
    public static final ModConfigSpec.IntValue MIN_FREE_GB;
    public static final ModConfigSpec.IntValue COMPRESSION_LEVEL;
    public static final ModConfigSpec.IntValue MAX_FREEZE_SECONDS;
    public static final ModConfigSpec.BooleanValue RUN_MISSED;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXTRA_PATHS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDE_PATTERNS;

    public static final ModConfigSpec.BooleanValue DRIVE_ENABLED;
    public static final ModConfigSpec.BooleanValue REQUIRE_UPLOAD;
    public static final ModConfigSpec.ConfigValue<String> DRIVE_FOLDER;
    public static final ModConfigSpec.ConfigValue<String> CLIENT_SECRET_FILE;
    public static final ModConfigSpec.ConfigValue<String> CREDENTIALS_FILE;
    public static final ModConfigSpec.IntValue CHUNK_SIZE_MIB;
    public static final ModConfigSpec.IntValue MAX_RETRIES;
    public static final ModConfigSpec.IntValue KEEP_REMOTE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("General settings for GDrive Backup.").push("general");
        ENABLED = builder
                .comment("Enable scheduled backups.")
                .define("enabled", true);
        SCHEDULE = builder
                .comment("Backup schedule. Accepts a 5-field cron expression or an interval such as '6h'.")
                .define("schedule", "0 4 * * *");
        TIMEZONE = builder
                .comment("Timezone used to evaluate the schedule, for example 'Asia/Tokyo' or 'UTC'.")
                .define("timezone", "Asia/Tokyo");
        SNAPSHOT_MODE = builder
                .comment("Snapshot strategy: 'online' copies while the server runs, 'pause' copies while the server is paused.")
                .define("snapshot-mode", "online");
        SERVER_NAME = builder
                .comment("Name used for the Google Drive folder. Empty means use the world name.")
                .define("server-name", "");
        builder.pop();

        builder.comment("Backup contents and local storage.").push("backup");
        LOCAL_DIR = builder
                .comment("Local directory for staging and ZIP archives. Relative paths are resolved against the server directory. "
                        + "Docker users should point this at a persistent volume such as /backups/gdrivebackup.")
                .define("local-dir", "backups/gdrivebackup");
        INCLUDE_MODS = builder
                .comment("Include the mods/ directory in the archive. When false, only mods-manifest.json is included.")
                .define("include-mods", false);
        INCLUDE_CONFIG = builder
                .comment("Include config/ and defaultconfigs/ in the archive.")
                .define("include-config", true);
        KEEP_LOCAL = builder
                .comment("Number of local ZIP archives to keep (including the newest).")
                .defineInRange("keep-local", 2, 1, 100);
        MIN_FREE_GB = builder
                .comment("Minimum free space to keep on the backup volume after staging and ZIP creation.")
                .defineInRange("min-free-gb", 5, 1, 1000);
        COMPRESSION_LEVEL = builder
                .comment("ZIP compression level from 0 (store) to 9 (best). Region files are stored without compression.")
                .defineInRange("compression-level", 1, 0, 9);
        MAX_FREEZE_SECONDS = builder
                .comment("Maximum time the server may stay in save-off/finalization before the backup is aborted. "
                        + "Keep this below max-tick-time. 0 disables the guard.")
                .defineInRange("max-freeze-seconds", 45, 0, 300);
        RUN_MISSED = builder
                .comment("Run a missed scheduled backup shortly after the server starts.")
                .define("run-missed", true);
        EXTRA_PATHS = builder
                .comment("Additional files or directories to include, relative to the server directory.")
                .defineListAllowEmpty("extra-paths", List.of(), () -> "", o -> o instanceof String);
        EXCLUDE_PATTERNS = builder
                .comment("Additional glob patterns to exclude, relative to the server directory. Example: 'world/datapacks/large/**'.")
                .defineListAllowEmpty("exclude-patterns", List.of(), () -> "", o -> o instanceof String);
        builder.pop();

        builder.comment("Google Drive upload settings.").push("drive");
        DRIVE_ENABLED = builder
                .comment("Upload completed archives to Google Drive. Set to false for local-only testing.")
                .define("enabled", true);
        REQUIRE_UPLOAD = builder
                .comment("Treat a failed Drive upload as a backup failure. The local ZIP is kept and retried on the next run.")
                .define("require-upload", true);
        DRIVE_FOLDER = builder
                .comment("Top-level folder name in Google Drive.")
                .define("folder", "MinecraftBackups");
        CLIENT_SECRET_FILE = builder
                .comment("OAuth client JSON downloaded from Google Cloud Console. Relative paths use the server directory.")
                .define("client-secret-file", "config/gdrivebackup/client_secret.json");
        CREDENTIALS_FILE = builder
                .comment("File where the refresh token is stored. Relative paths use the server directory.")
                .define("credentials-file", "config/gdrivebackup/credentials.json");
        CHUNK_SIZE_MIB = builder
                .comment("Resumable upload chunk size in MiB. Google requires multiples of 256 KiB.")
                .defineInRange("chunk-size-mib", 16, 1, 64);
        MAX_RETRIES = builder
                .comment("Maximum retries for transient Drive API errors.")
                .defineInRange("max-retries", 5, 1, 20);
        KEEP_REMOTE = builder
                .comment("Number of Drive archives to keep per server.")
                .defineInRange("keep-remote", 7, 1, 365);
        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }
}
