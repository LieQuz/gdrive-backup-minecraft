package dev.xnaq.gdrivebackup.drive;

import dev.xnaq.gdrivebackup.util.Checksums;
import dev.xnaq.gdrivebackup.util.Log;
import dev.xnaq.gdrivebackup.Config;
import dev.xnaq.gdrivebackup.backup.BackupPlan;
import dev.xnaq.gdrivebackup.backup.BackupState;
import dev.xnaq.gdrivebackup.backup.ZipArchiver;
import dev.xnaq.gdrivebackup.util.Hashing;
import dev.xnaq.gdrivebackup.util.Text;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * High-level Drive lifecycle: folder selection, upload, retention, listing and
 * restore downloads.
 */
public final class DriveService {
    private final Path serverRoot;
    private final Path stateFile;
    private final BackupState state;
    private final Path clientSecretFile;
    private final Path credentialsFile;
    private final GoogleOAuth oauth;
    private final DriveClient client;
    private final AtomicBoolean authorizing = new AtomicBoolean(false);

    public DriveService(Path serverRoot, BackupState state, Path stateFile) {
        this.serverRoot = serverRoot;
        this.stateFile = stateFile;
        this.state = state;
        this.state.ensureServerId();
        this.clientSecretFile = BackupPlan.resolve(serverRoot, Config.CLIENT_SECRET_FILE.get());
        this.credentialsFile = BackupPlan.resolve(serverRoot, Config.CREDENTIALS_FILE.get());
        this.oauth = new GoogleOAuth(clientSecretFile, credentialsFile);
        this.client = new DriveClient(
                oauth,
                Config.MAX_RETRIES.get(),
                Config.CHUNK_SIZE_MIB.get() * 1024 * 1024
        );
    }

    public boolean isEnabled() {
        return Config.DRIVE_ENABLED.get();
    }

    public boolean isRequired() {
        return Config.REQUIRE_UPLOAD.get();
    }

    public boolean isAuthorized() {
        return oauth.isAuthorized();
    }

    public Path credentialsFile() {
        return credentialsFile;
    }

    public Path clientSecretFile() {
        return clientSecretFile;
    }

    public void authorize(Consumer<GoogleOAuth.DeviceCode> onCode) {
        if (!authorizing.compareAndSet(false, true)) {
            throw new AuthException("Google Drive authorization is already in progress.");
        }
        try {
            oauth.authorize(onCode, Duration.ofMinutes(30));
            Log.info("Google Drive authorization is now active.");
        } finally {
            authorizing.set(false);
        }
    }

    public void revoke() {
        oauth.revoke();
        state.driveRootFolderId = null;
        state.driveFolderId = null;
        saveState();
    }

    public RemoteFile upload(UploadRequest request) {
        ensureAuthorized();
        String folderId = ensureServerFolder(request.serverId(), request.serverName());

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("managedBy", "gdrivebackup");
        properties.put("formatVersion", "1");
        properties.put("serverId", request.serverId());
        properties.put("serverName", Text.truncate(request.serverName(), 100));
        properties.put("createdAt", request.createdAt());
        properties.put("reason", Text.truncate(Text.sanitize(request.reason(), "manual"), 100));
        properties.put("sha256", request.sha256());
        properties.put("md5", request.md5());
        properties.put("fileCount", Integer.toString(request.fileCount()));
        properties.put("sourceBytes", Long.toString(request.sourceBytes()));

        RemoteFile uploaded = client.upload(
                request.archive(),
                request.name(),
                folderId,
                properties,
                request.md5()
        );
        Log.info(
                "Uploaded {} to Google Drive ({}); id={}",
                request.name(), Text.formatBytes(request.size()), uploaded.id());
        try {
            prune(folderId, request.name());
        } catch (RuntimeException e) {
            Log.warn("Drive upload succeeded but retention cleanup failed", e);
        }
        return uploaded;
    }

    public boolean retryPending(BackupState.PendingUpload pending) {
        Path archive = Path.of(pending.path);
        if (!Files.isRegularFile(archive)) {
            Log.warn("Pending upload archive is missing; clearing pending upload: {}", archive);
            state.pendingUpload = null;
            saveState();
            return false;
        }
        upload(UploadRequest.fromPending(pending));
        state.pendingUpload = null;
        saveState();
        return true;
    }

    public List<RemoteFile> list() {
        ensureAuthorized();
        state.ensureServerId();
        String folderId = ensureServerFolder(state.serverId, serverName());
        return client.listFiles(folderId);
    }

    public RemoteFile find(String name) {
        return list().stream()
                .filter(file -> name.equals(file.name()))
                .findFirst()
                .orElse(null);
    }

    public Path download(String name) {
        RemoteFile file = find(name);
        if (file == null) {
            throw new DriveException("Backup not found in Google Drive: " + name);
        }
        Path restoreDir = BackupPlan.resolve(serverRoot, Config.LOCAL_DIR.get()).resolve("restore");
        Path target = restoreDir.resolve(Text.sanitize(name, "backup.zip"));
        client.downloadFile(file, target);
        Log.info("Downloaded {} to {}", name, target);
        return target;
    }

    public String verify(String name) {
        RemoteFile remote = find(name);
        if (remote == null) {
            return "Not found in Drive: " + name;
        }
        Path local = BackupPlan.resolve(serverRoot, Config.LOCAL_DIR.get())
                .resolve(Text.sanitize(name, "backup.zip"));
        if (!Files.isRegularFile(local)) {
            return "Remote: " + name + ", " + Text.formatBytes(remote.size())
                    + " (local copy not present)";
        }
        try {
            String localMd5 = Hashing.md5(local);
            boolean match = Checksums.md5Matches(remote.md5Checksum(), localMd5);
            return "Remote: " + name + ", " + Text.formatBytes(remote.size())
                    + ", checksum " + (match ? "OK" : "MISMATCH");
        } catch (IOException e) {
            return "Could not verify local archive: " + e.getMessage();
        }
    }

    public String statusSummary() {
        if (!isEnabled()) {
            return "Drive: disabled";
        }
        return "Drive: " + (oauth.isAuthorized() ? "authorized" : "not authorized")
                + ", folder=" + Config.DRIVE_FOLDER.get()
                + (state.driveFolderId == null ? "" : ", folderId=" + state.driveFolderId);
    }

    private String ensureServerFolder(String serverId, String serverName) {
        String rootId = state.driveRootFolderId;
        if (!client.folderExists(rootId)) {
            rootId = client.findFolder(Config.DRIVE_FOLDER.get(), "root");
            if (rootId == null) {
                Map<String, String> rootProperties = new LinkedHashMap<>();
                rootProperties.put("managedBy", "gdrivebackup");
                rootProperties.put("kind", "root");
                rootId = client.createFolder(Config.DRIVE_FOLDER.get(), "root", rootProperties);
            }
            state.driveRootFolderId = rootId;
            saveState();
        }

        String folderId = state.driveFolderId;
        if (!client.folderExists(folderId)) {
            String safeServerId = serverId == null || serverId.isBlank()
                    ? state.serverId
                    : serverId;
            String name = Text.sanitize(serverName, "minecraft") + "-"
                    + safeServerId.substring(0, Math.min(8, safeServerId.length()));
            folderId = client.findFolder(name, rootId);
            if (folderId == null) {
                Map<String, String> properties = new LinkedHashMap<>();
                properties.put("managedBy", "gdrivebackup");
                properties.put("kind", "server");
                properties.put("serverId", serverId);
                folderId = client.createFolder(name, rootId, properties);
            }
            state.driveFolderId = folderId;
            saveState();
        }
        return folderId;
    }

    private void prune(String folderId, String uploadedName) {
        int keep = Math.max(1, Config.KEEP_REMOTE.get());
        List<RemoteFile> files = client.listFiles(folderId);
        files.sort(Comparator.comparing(RemoteFile::createdAt).reversed());
        for (int i = 0; i < files.size(); i++) {
            RemoteFile file = files.get(i);
            if (i < keep || file.name().equals(uploadedName)) {
                continue;
            }
            try {
                client.deleteFile(file.id());
                Log.info("Removed old Drive backup {}", file.name());
            } catch (RuntimeException e) {
                Log.warn("Could not remove old Drive backup {}", file.name(), e);
            }
        }
    }

    private void ensureAuthorized() {
        if (!oauth.isAuthorized()) {
            throw new AuthException(
                    "Google Drive is not authorized. Run /gdrivebackup auth and authorize the device.");
        }
    }

    private String serverName() {
        if (state.serverName != null && !state.serverName.isBlank()) {
            return state.serverName;
        }
        String configured = Config.SERVER_NAME.get();
        return configured == null || configured.isBlank() ? "minecraft" : configured;
    }

    private void saveState() {
        try {
            state.save(stateFile);
        } catch (IOException e) {
            Log.warn("Could not save Drive state to {}", stateFile, e);
        }
    }

    public record UploadRequest(
            Path archive,
            String name,
            String serverName,
            String serverId,
            String reason,
            String createdAt,
            String sha256,
            String md5,
            int fileCount,
            long sourceBytes,
            long size
    ) {
        public static UploadRequest fromPlan(Path archive, BackupPlan plan,
                                             ZipArchiver.ArchiveResult result,
                                             int fileCount, long sourceBytes) {
            return new UploadRequest(
                    archive,
                    archive.getFileName().toString(),
                    plan.serverName(),
                    plan.serverId(),
                    plan.reason(),
                    plan.createdAt().toString(),
                    result.sha256(),
                    result.md5(),
                    fileCount,
                    sourceBytes,
                    result.size()
            );
        }

        public static UploadRequest fromPending(BackupState.PendingUpload pending) {
            return new UploadRequest(
                    Path.of(pending.path),
                    pending.name,
                    pending.serverName,
                    pending.serverId,
                    pending.reason,
                    pending.createdAt,
                    pending.sha256,
                    pending.md5,
                    pending.fileCount,
                    pending.sourceBytes,
                    pending.size
            );
        }

        public BackupState.PendingUpload toPending() {
            BackupState.PendingUpload pending = new BackupState.PendingUpload();
            pending.path = archive.toAbsolutePath().toString();
            pending.name = name;
            pending.size = size;
            pending.sha256 = sha256;
            pending.md5 = md5;
            pending.createdAt = createdAt;
            pending.reason = reason;
            pending.serverName = serverName;
            pending.serverId = serverId;
            pending.fileCount = fileCount;
            pending.sourceBytes = sourceBytes;
            return pending;
        }
    }
}
