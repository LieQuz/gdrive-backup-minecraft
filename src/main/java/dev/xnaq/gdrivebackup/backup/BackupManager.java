package dev.xnaq.gdrivebackup.backup;

import dev.xnaq.gdrivebackup.util.Log;
import dev.xnaq.gdrivebackup.Config;
import dev.xnaq.gdrivebackup.drive.DriveService;
import dev.xnaq.gdrivebackup.util.Text;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Owns the backup worker and the server-thread snapshot lifecycle.
 */
public final class BackupManager {
    private static final long MIB = 1024L * 1024L;

    private final MinecraftServer server;
    private final Path serverRoot;
    private final Path stateFile;
    private final BackupState state;
    private final DriveService drive;
    private final ExecutorService worker;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String stage = "idle";

    public BackupManager(MinecraftServer server, BackupState state, DriveService drive) {
        this.server = server;
        this.serverRoot = server.getServerDirectory().toAbsolutePath().normalize();
        this.stateFile = serverRoot.resolve("config/gdrivebackup/state.json");
        this.state = state;
        this.drive = drive;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gdrivebackup-worker");
            thread.setDaemon(true);
            return thread;
        });
        worker.submit(this::cleanupOldStaging);
    }

    public BackupState state() {
        return state;
    }

    public Path stateFile() {
        return stateFile;
    }

    public String stage() {
        return stage;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void requestBackup(String reason, CommandSourceStack source) {
        if (!running.compareAndSet(false, true)) {
            if (source != null) {
                source.sendFailure(Component.literal("A backup is already running."));
            }
            return;
        }

        if (source != null) {
            source.sendSuccess(() -> Component.literal("Backup started: " + reason), false);
        }

        try {
            worker.submit(() -> {
                try {
                    runBackup(reason, source);
                } catch (Throwable t) {
                    Log.error("Backup failed", t);
                } finally {
                    stage = "idle";
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            running.set(false);
            if (source != null) {
                source.sendFailure(Component.literal("The backup worker is shutting down."));
            }
        }
    }

    private void runBackup(String reason, CommandSourceStack source) {
        Instant startedAt = Instant.now();
        state.recordStart(startedAt);
        saveStateQuietly();

        BackupPlan plan = null;
        SnapshotHandle handle = null;
        boolean pauseMode = "pause".equalsIgnoreCase(Config.SNAPSHOT_MODE.get());
        try {
            if (drive.isEnabled() && state.pendingUpload != null) {
                stage = "retrying-upload";
                BackupState.PendingUpload pending = state.pendingUpload;
                Log.info("Retrying pending Drive upload: {}", pending.name);
                boolean uploaded = drive.retryPending(pending);
                state.pendingUpload = null;
                if (uploaded) {
                    state.recordSuccess(
                            Instant.now(),
                            Path.of(pending.path),
                            pending.size,
                            pending.sha256,
                            pending.md5,
                            pending.fileCount,
                            pending.sourceBytes
                    );
                }
                saveStateQuietly();
            }

            plan = onServer(() -> BackupPlan.create(server, state.serverId, reason, startedAt), 5);
            state.serverName = plan.serverName();
            saveStateQuietly();
            Log.info(
                    "Starting backup '{}' of {} root(s) into {} (mode={})",
                    reason, plan.roots().size(), plan.stagingRoot(), pauseMode ? "pause" : "online");

            BackupPathFilter filter = new BackupPathFilter(
                    Config.EXCLUDE_PATTERNS.get(),
                    plan.excludedRoots()
            );
            List<SnapshotCopier> copiers = createCopiers(plan, filter);
            long sourceBytes = estimateBytes(copiers);
            checkDisk(plan, sourceBytes);
            Files.createDirectories(plan.stagingRoot());

            if (pauseMode) {
                SnapshotHandle pauseHandle = onServer(
                        () -> beginSnapshot(copiers, true),
                        60
                );
                handle = pauseHandle;
                Log.info(
                        "Paused snapshot copied {} file(s), {}",
                        totalFiles(copiers), Text.formatBytes(totalBytes(copiers)));
            } else {
                // Copy the bulk of the world while saving is still enabled.
                // This keeps chunk unloading (and therefore memory use) normal.
                // Files written during this pass are marked dirty and are
                // recopied after the short save-off/flush window below.
                stage = "copying";
                SnapshotCopier.CopyResult initial = copyInitial(copiers);
                Log.info(
                        "Pre-copy finished: {} file(s), {} ({} changed during copy)",
                        initial.files(), Text.formatBytes(initial.bytes()), initial.recopiedFiles());

                stage = "finalizing";
                handle = onServer(() -> beginSnapshot(copiers, false), 10);
                SnapshotHandle activeHandle = handle;
                SnapshotCopier.CopyResult finalized = onServer(
                        () -> finalizeSnapshot(activeHandle),
                        30
                );
                Log.info(
                        "Final consistency pass: {} file(s) checked, {} recopied ({})",
                        finalized.files(), finalized.recopiedFiles(),
                        Text.formatBytes(finalized.recopiedBytes()));
            }

            stage = "manifest";
            int fileCount = totalFiles(copiers);
            long bytes = totalBytes(copiers);
            BackupInfo.write(plan.stagingRoot(), plan, fileCount, bytes);
            ModsManifest.write(plan.stagingRoot(), plan.serverRoot().resolve("mods"));

            stage = "zipping";
            Log.info("Creating ZIP for {} file(s), {}", fileCount, Text.formatBytes(bytes));
            ZipArchiver.ArchiveResult archive = ZipArchiver.create(
                    plan.stagingRoot(),
                    plan.localDir(),
                    plan.archiveBaseName(),
                    Config.COMPRESSION_LEVEL.get(),
                    processed -> Log.info(
                            "ZIP progress: {} / {}", Text.formatBytes(processed), Text.formatBytes(bytes))
            );

            if (drive.isEnabled()) {
                stage = "uploading";
                DriveService.UploadRequest upload = DriveService.UploadRequest.fromPlan(
                        archive.archive(), plan, archive, fileCount, bytes);
                state.pendingUpload = upload.toPending();
                saveStateQuietly();
                try {
                    drive.upload(upload);
                    state.pendingUpload = null;
                    saveStateQuietly();
                } catch (RuntimeException e) {
                    if (drive.isRequired()) {
                        throw e;
                    }
                    Log.warn(
                            "Google Drive upload failed; keeping the local archive only", e);
                    state.pendingUpload = null;
                    saveStateQuietly();
                }
            }

            stage = "retention";
            try {
                applyLocalRetention(plan.localDir(), archive.archive());
            } catch (IOException e) {
                Log.warn("Could not apply local retention policy", e);
            }

            state.recordSuccess(
                    Instant.now(),
                    archive.archive(),
                    archive.size(),
                    archive.sha256(),
                    archive.md5(),
                    fileCount,
                    bytes
            );
            saveStateQuietly();

            Log.info(
                    "Backup complete: {} ({}, {} file(s), sha256={})",
                    archive.archive().getFileName(),
                    Text.formatBytes(archive.size()),
                    fileCount,
                    archive.sha256()
            );
            notifySource(source, "Backup complete: " + archive.archive().getFileName()
                    + " (" + Text.formatBytes(archive.size()) + ")", false);
            notifyOps("Backup complete: " + archive.archive().getFileName()
                    + " (" + Text.formatBytes(archive.size()) + ")");

            try {
                deleteRecursively(plan.stagingRoot());
            } catch (IOException e) {
                Log.warn("Could not remove staging directory {}", plan.stagingRoot(), e);
            }
        } catch (Throwable failure) {
            state.recordFailure(failure);
            saveStateQuietly();
            if (failure instanceof BackupException) {
                Log.warn("Backup failed: {}", failure.getMessage());
            } else {
                Log.error("Backup failed", failure);
            }
            notifySource(source, "Backup failed: " + failure.getMessage(), true);
            notifyOps("Backup failed: " + failure.getMessage());

            if (handle != null && !handle.completed) {
                SnapshotHandle abortHandle = handle;
                try {
                    onServer(() -> abortSnapshot(abortHandle), 5);
                } catch (Throwable abortFailure) {
                    Log.error("Could not restore save state after a failed backup", abortFailure);
                }
            }
            if (plan != null) {
                try {
                    deleteRecursively(plan.stagingRoot());
                } catch (IOException cleanupFailure) {
                    Log.warn("Could not remove failed staging directory {}", plan.stagingRoot(), cleanupFailure);
                }
            }
        }
    }

    private List<SnapshotCopier> createCopiers(BackupPlan plan, BackupPathFilter filter) {
        List<SnapshotCopier> copiers = new ArrayList<>();
        for (BackupPlan.Root root : plan.roots()) {
            Path target = plan.stagingRoot().resolve(root.relative().toString()).normalize();
            if (!target.startsWith(plan.stagingRoot())) {
                throw new BackupException("Refusing to stage outside the staging directory: " + target);
            }
            copiers.add(new SnapshotCopier(
                    root.source(),
                    target,
                    root.relative(),
                    filter,
                    root.worldRoot()
            ));
        }
        return copiers;
    }

    private long estimateBytes(List<SnapshotCopier> copiers) throws IOException {
        long total = 0;
        for (SnapshotCopier copier : copiers) {
            total += copier.estimateBytes();
        }
        return total;
    }

    private void checkDisk(BackupPlan plan, long sourceBytes) throws IOException {
        if (sourceBytes <= 0) {
            throw new BackupException("No files were selected for backup.");
        }
        Files.createDirectories(plan.localDir());
        FileStore store = Files.getFileStore(plan.localDir());
        long usable = store.getUsableSpace();
        long minimumFree = Config.MIN_FREE_GB.get() * 1024L * 1024L * 1024L;
        long required = sourceBytes * 2 + 512 * MIB + minimumFree;
        Log.info(
                "Disk check: source={}, free={}, required~{} (staging + ZIP + {} reserve)",
                Text.formatBytes(sourceBytes), Text.formatBytes(usable),
                Text.formatBytes(required), Text.formatBytes(minimumFree));
        if (usable < required) {
            throw new BackupException(
                    "Not enough free space in " + plan.localDir()
                            + ": need about " + Text.formatBytes(required)
                            + ", have " + Text.formatBytes(usable));
        }
    }

    private SnapshotHandle beginSnapshot(List<SnapshotCopier> copiers, boolean pauseMode) throws IOException {
        if (server.isShutdown()) {
            throw new BackupException("Server is stopping; backup cancelled.");
        }
        Map<ServerLevel, Boolean> previous = new LinkedHashMap<>();
        long freezeStarted = System.nanoTime();
        try {
            for (ServerLevel level : server.getAllLevels()) {
                previous.put(level, level.noSave);
                level.noSave = true;
            }

            long started = System.nanoTime();
            boolean saved = server.saveEverything(false, true, true);
            if (!saved) {
                throw new BackupException("saveEverything() returned false; another save may be in progress.");
            }
            Log.info(
                    "World flushed in {} ms; saving is disabled during the snapshot",
                    (System.nanoTime() - started) / 1_000_000L);

            SnapshotHandle handle = new SnapshotHandle(previous, copiers, freezeStarted);
            if (pauseMode) {
                try {
                    copyInitial(copiers);
                    finalizeCopiers(copiers);
                    handle.completed = true;
                } finally {
                    restoreNoSave(previous);
                }
            }
            return handle;
        } catch (IOException | RuntimeException | Error t) {
            restoreNoSave(previous);
            throw t;
        }
    }

    private SnapshotCopier.CopyResult finalizeSnapshot(SnapshotHandle handle) throws IOException {
        try {
            int maxFreezeSeconds = Config.MAX_FREEZE_SECONDS.get();
            long deadline = maxFreezeSeconds > 0
                    ? handle.freezeStartedNanos + TimeUnit.SECONDS.toNanos(maxFreezeSeconds)
                    : 0L;
            return finalizeCopiers(handle.copiers, deadline);
        } finally {
            restoreNoSave(handle.previousNoSave);
            handle.completed = true;
        }
    }

    private Void abortSnapshot(SnapshotHandle handle) {
        if (!handle.completed) {
            restoreNoSave(handle.previousNoSave);
            handle.completed = true;
        }
        return null;
    }

    private static void restoreNoSave(Map<ServerLevel, Boolean> previous) {
        for (Map.Entry<ServerLevel, Boolean> entry : previous.entrySet()) {
            entry.getKey().noSave = entry.getValue();
        }
    }

    private SnapshotCopier.CopyResult copyInitial(List<SnapshotCopier> copiers) throws IOException {
        SnapshotCopier.CopyResult total = new SnapshotCopier.CopyResult(0, 0, 0, 0);
        for (SnapshotCopier copier : copiers) {
            SnapshotCopier.CopyResult result = copier.copyInitial();
            total = total.plus(result);
            Log.info(
                    "Copied {}: {} file(s), {}",
                    copier.sourceRoot().getFileName(),
                    result.files(),
                    Text.formatBytes(result.bytes()));
        }
        return total;
    }

    private SnapshotCopier.CopyResult finalizeCopiers(List<SnapshotCopier> copiers) throws IOException {
        SnapshotCopier.CopyResult total = new SnapshotCopier.CopyResult(0, 0, 0, 0);
        for (SnapshotCopier copier : copiers) {
            total = total.plus(copier.finalizeSnapshot());
        }
        return total;
    }

    private SnapshotCopier.CopyResult finalizeCopiers(List<SnapshotCopier> copiers, long deadlineNanos)
            throws IOException {
        SnapshotCopier.CopyResult total = new SnapshotCopier.CopyResult(0, 0, 0, 0);
        for (SnapshotCopier copier : copiers) {
            total = total.plus(copier.finalizeSnapshot(deadlineNanos));
        }
        return total;
    }

    private static int totalFiles(List<SnapshotCopier> copiers) {
        return copiers.stream().mapToInt(SnapshotCopier::fileCount).sum();
    }

    private static long totalBytes(List<SnapshotCopier> copiers) {
        return copiers.stream().mapToLong(SnapshotCopier::byteCount).sum();
    }

    private void applyLocalRetention(Path localDir, Path currentArchive) throws IOException {
        List<Path> archives = new ArrayList<>();
        try (Stream<Path> stream = Files.list(localDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("minecraft-backup-") && name.endsWith(".zip");
                    })
                    .forEach(archives::add);
        }

        List<Path> sorted = RetentionPolicy.sortNewestFirst(archives);
        int keep = Math.max(1, Config.KEEP_LOCAL.get());
        for (int i = 0; i < sorted.size(); i++) {
            Path archive = sorted.get(i);
            if (i < keep || archive.equals(currentArchive)) {
                continue;
            }
            Files.deleteIfExists(archive);
            Log.info("Removed old local backup {}", archive.getFileName());
        }
    }

    private void cleanupOldStaging() {
        Path localDir = BackupPlan.resolve(serverRoot, Config.LOCAL_DIR.get());
        Path stagingDir = localDir.resolve("staging");
        if (!Files.isDirectory(stagingDir)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        try (Stream<Path> stream = Files.list(stagingDir)) {
            for (Path path : stream.toList()) {
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        deleteRecursively(path);
                        Log.info("Removed stale staging path {}", path.getFileName());
                    }
                } catch (IOException e) {
                    Log.warn("Could not inspect stale staging path {}", path, e);
                }
            }
        } catch (IOException e) {
            Log.warn("Could not scan staging directory {}", stagingDir, e);
        }

        try (Stream<Path> stream = Files.list(localDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".zip.part")) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        Files.deleteIfExists(path);
                        Log.info("Removed stale partial archive {}", name);
                    }
                } catch (IOException e) {
                    Log.warn("Could not inspect partial archive {}", path, e);
                }
            }
        } catch (IOException e) {
            Log.warn("Could not scan local backup directory {}", localDir, e);
        }
    }

    private <T> T onServer(Callable<T> action, long timeoutMinutes) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    future.complete(action.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new BackupException("Server is stopping; backup cancelled.", e);
        }

        try {
            return future.get(timeoutMinutes, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new BackupException("Server task failed.", cause);
        } catch (TimeoutException e) {
            throw new BackupException("Server task did not finish within " + timeoutMinutes + " minute(s).", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("Backup was interrupted.", e);
        }
    }

    private void saveStateQuietly() {
        try {
            state.save(stateFile);
        } catch (IOException e) {
            Log.warn("Could not save backup state to {}", stateFile, e);
        }
    }

    private void notifySource(CommandSourceStack source, String message, boolean failure) {
        if (source == null) {
            return;
        }
        try {
            server.execute(() -> {
                try {
                    if (failure) {
                        source.sendFailure(Component.literal(message));
                    } else {
                        source.sendSuccess(() -> Component.literal(message), false);
                    }
                } catch (RuntimeException e) {
                    Log.warn("Could not send command feedback", e);
                }
            });
        } catch (RejectedExecutionException e) {
            Log.debug("Server is stopping; could not deliver backup feedback");
        }
    }

    private void notifyOps(String message) {
        try {
            server.execute(() -> {
                try {
                    server.getPlayerList().getPlayers().stream()
                            .filter(player -> player.hasPermissions(4))
                            .forEach(player -> player.sendSystemMessage(
                                    Component.literal("[GDrive Backup] " + message)));
                } catch (RuntimeException e) {
                    Log.warn("Could not notify operators", e);
                }
            });
        } catch (RejectedExecutionException e) {
            Log.debug("Server is stopping; could not deliver operator notification");
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private static final class SnapshotHandle {
        private final Map<ServerLevel, Boolean> previousNoSave;
        private final List<SnapshotCopier> copiers;
        private final long freezeStartedNanos;
        private boolean completed;

        private SnapshotHandle(Map<ServerLevel, Boolean> previousNoSave, List<SnapshotCopier> copiers,
                               long freezeStartedNanos) {
            this.previousNoSave = previousNoSave;
            this.copiers = copiers;
            this.freezeStartedNanos = freezeStartedNanos;
        }
    }
}
