package dev.xnaq.gdrivebackup;

import dev.xnaq.gdrivebackup.util.Log;
import dev.xnaq.gdrivebackup.backup.BackupManager;
import dev.xnaq.gdrivebackup.backup.BackupState;
import dev.xnaq.gdrivebackup.drive.DriveService;
import dev.xnaq.gdrivebackup.schedule.ScheduleService;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.server.MinecraftServer;

/**
 * Per-server runtime services. A dedicated server has exactly one instance.
 */
public final class BackupRuntime {
    private final BackupState state;
    private final DriveService drive;
    private final BackupManager manager;
    private final ScheduleService schedule;
    private final ExecutorService driveExecutor;

    public BackupRuntime(MinecraftServer server) {
        Path serverRoot = server.getServerDirectory().toAbsolutePath().normalize();
        Path stateFile = serverRoot.resolve("config/gdrivebackup/state.json");

        this.state = BackupState.load(stateFile);
        this.state.ensureServerId();

        String previousServerName = state.serverName;
        String configuredName = Config.SERVER_NAME.get();
        String serverName = configuredName == null || configuredName.isBlank()
                ? server.getWorldData().getLevelName()
                : configuredName;
        this.state.serverName = dev.xnaq.gdrivebackup.util.Text.sanitize(serverName, "minecraft");
        if (previousServerName != null && !previousServerName.equals(state.serverName)) {
            // The server folder name is derived from this value; use a fresh folder.
            state.driveFolderId = null;
        }
        try {
            this.state.save(stateFile);
        } catch (java.io.IOException e) {
            Log.warn("Could not save initial backup state to {}", stateFile, e);
        }

        this.drive = new DriveService(serverRoot, state, stateFile);
        this.manager = new BackupManager(server, state, drive);
        this.schedule = new ScheduleService(server, manager, state);
        this.driveExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "gdrivebackup-drive");
            thread.setDaemon(true);
            return thread;
        });

        this.schedule.start();
        Log.info(drive.statusSummary());
        if (drive.isEnabled() && !drive.isAuthorized()) {
            Log.warn(
                    "Google Drive is not authorized yet. Run /gdrivebackup auth from the server console.");
        }
    }

    public void tick() {
        schedule.tick();
    }

    public void reload() {
        schedule.reload();
    }

    public void executeDrive(Runnable task) {
        try {
            driveExecutor.submit(task);
        } catch (RejectedExecutionException e) {
            Log.warn("Drive task rejected because the runtime is shutting down");
        }
    }

    public void shutdown() {
        schedule.shutdown();
        driveExecutor.shutdownNow();
        manager.shutdown();
    }

    public BackupManager manager() {
        return manager;
    }

    public DriveService drive() {
        return drive;
    }

    public BackupState state() {
        return state;
    }

    public ScheduleService schedule() {
        return schedule;
    }
}
