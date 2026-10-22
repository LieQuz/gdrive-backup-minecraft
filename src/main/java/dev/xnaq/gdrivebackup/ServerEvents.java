package dev.xnaq.gdrivebackup;

import dev.xnaq.gdrivebackup.util.Log;
import dev.xnaq.gdrivebackup.command.BackupCommands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class ServerEvents {
    private ServerEvents() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BackupCommands.register(event.getDispatcher());
    }

    public static void onServerStarted(ServerStartedEvent event) {
        try {
            GDriveBackup.setRuntime(new BackupRuntime(event.getServer()));
            Log.info("GDrive Backup ready on Minecraft {}", event.getServer().getServerVersion());
        } catch (Throwable t) {
            GDriveBackup.setRuntime(null);
            Log.error("GDrive Backup could not start; automatic backups are disabled", t);
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime != null) {
            try {
                runtime.shutdown();
            } catch (Throwable t) {
                Log.warn("Error while stopping GDrive Backup", t);
            } finally {
                GDriveBackup.setRuntime(null);
            }
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime != null) {
            try {
                runtime.tick();
            } catch (Throwable t) {
                Log.error("Scheduler error", t);
            }
        }
    }
}
