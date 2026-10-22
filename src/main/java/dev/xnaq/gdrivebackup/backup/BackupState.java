package dev.xnaq.gdrivebackup.backup;

import dev.xnaq.gdrivebackup.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.xnaq.gdrivebackup.util.Text;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistent state for the backup scheduler and the last completed run.
 */
public final class BackupState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int formatVersion = 1;
    public String serverId;
    public String serverName;
    public String lastBackupAt;
    public String lastSuccessAt;
    public String lastError;
    public String lastArchive;
    public long lastArchiveSize;
    public String lastSha256;
    public String lastMd5;
    public int lastFileCount;
    public long lastSourceBytes;
    public String nextRunAt;
    public String scheduleExpression;
    public String driveRootFolderId;
    public String driveFolderId;
    public PendingUpload pendingUpload;

    public static BackupState load(Path file) {
        BackupState state = new BackupState();
        if (Files.isRegularFile(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                if (!json.isBlank()) {
                    BackupState loaded = GSON.fromJson(json, BackupState.class);
                    if (loaded != null) {
                        state = loaded;
                    }
                }
            } catch (Exception e) {
                try {
                    Path corrupt = file.resolveSibling(file.getFileName() + ".corrupt-" + Text.timestamp(Instant.now()));
                    Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
                    Log.warn(
                            "Could not read backup state; moved it to {}", corrupt, e);
                } catch (IOException moveError) {
                    Log.warn(
                            "Could not read or move backup state {}", file, moveError);
                }
            }
        }
        state.ensureServerId();
        return state;
    }

    public synchronized void ensureServerId() {
        if (serverId == null || serverId.isBlank()) {
            serverId = UUID.randomUUID().toString();
        }
    }

    public synchronized void recordStart(Instant now) {
        lastBackupAt = now.toString();
        lastError = null;
    }

    public synchronized void recordSuccess(Instant now, Path archive, long archiveSize,
                                           String sha256, String md5, int fileCount, long sourceBytes) {
        lastSuccessAt = now.toString();
        lastError = null;
        lastArchive = archive.toString();
        lastArchiveSize = archiveSize;
        lastSha256 = sha256;
        lastMd5 = md5;
        lastFileCount = fileCount;
        lastSourceBytes = sourceBytes;
        pendingUpload = null;
    }

    public synchronized void recordFailure(Throwable error) {
        lastError = Text.truncate(error.getClass().getSimpleName() + ": " + error.getMessage(), 2000);
    }

    public synchronized void save(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static final class PendingUpload {
        public String path;
        public String name;
        public long size;
        public String sha256;
        public String md5;
        public String createdAt;
        public String reason;
        public String serverName;
        public String serverId;
        public int fileCount;
        public long sourceBytes;
    }
}
