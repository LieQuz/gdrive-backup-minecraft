package dev.xnaq.gdrivebackup.command;

import dev.xnaq.gdrivebackup.util.Log;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.xnaq.gdrivebackup.BackupRuntime;
import dev.xnaq.gdrivebackup.Config;
import dev.xnaq.gdrivebackup.GDriveBackup;
import dev.xnaq.gdrivebackup.backup.BackupState;
import dev.xnaq.gdrivebackup.drive.RemoteFile;
import dev.xnaq.gdrivebackup.util.Text;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class BackupCommands {
    private BackupCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("gdrivebackup")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("now")
                                .executes(context -> start(context.getSource(), "manual"))
                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                        .executes(context -> start(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "note")))))
                        .then(Commands.literal("auth")
                                .executes(context -> auth(context.getSource())))
                        .then(Commands.literal("revoke")
                                .executes(context -> revoke(context.getSource())))
                        .then(Commands.literal("list")
                                .executes(context -> list(context.getSource())))
                        .then(Commands.literal("prune")
                                .executes(context -> prune(context.getSource())))
                        .then(Commands.literal("cleanup")
                                .executes(context -> cleanup(context.getSource())))
                        .then(Commands.literal("verify")
                                .executes(context -> verify(context.getSource(), null))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> verify(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name")))))
                        .then(Commands.literal("download")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> download(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name")))))
                        .then(Commands.literal("reload")
                                .executes(context -> {
                                    BackupRuntime runtime = GDriveBackup.runtime();
                                    if (runtime == null) {
                                        context.getSource().sendFailure(
                                                Component.literal("GDrive Backup is not initialized."));
                                        return 0;
                                    }
                                    runtime.reload();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("GDrive Backup schedule reloaded."), false);
                                    return 1;
                                }))
        );
    }

    private static int start(CommandSourceStack source, String note) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime == null) {
            source.sendFailure(Component.literal("GDrive Backup is not initialized."));
            return 0;
        }
        runtime.manager().requestBackup(note == null || note.isBlank() ? "manual" : note, source);
        return 1;
    }

    private static int auth(CommandSourceStack source) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime == null) {
            source.sendFailure(Component.literal("GDrive Backup is not initialized."));
            return 0;
        }
        if (!runtime.drive().isEnabled()) {
            source.sendFailure(Component.literal("Google Drive is disabled in the config."));
            return 0;
        }

        line(source, "Starting Google Drive authorization.");
        line(source, "The URL and code will appear here and in the server console.");
        runtime.executeDrive(() -> {
            try {
                runtime.drive().authorize(code -> {
                    sendAsync(source, "Open " + code.verificationUrl()
                            + " and enter code " + code.userCode(), false);
                    Log.info(
                            "Open {} and enter code {} to authorize Google Drive",
                            code.verificationUrl(), code.userCode());
                });
                sendAsync(source, "Google Drive authorization completed.", false);
                notifyOpsAsync(source.getServer(), "Google Drive authorization completed.");
            } catch (Throwable t) {
                Log.error("Google Drive authorization failed", t);
                sendAsync(source, "Google Drive authorization failed: " + t.getMessage(), true);
            }
        });
        return 1;
    }

    private static int revoke(CommandSourceStack source) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime == null) {
            source.sendFailure(Component.literal("GDrive Backup is not initialized."));
            return 0;
        }
        line(source, "Revoking Google Drive authorization...");
        runtime.executeDrive(() -> {
            try {
                runtime.drive().revoke();
                sendAsync(source, "Google Drive authorization revoked.", false);
            } catch (Throwable t) {
                Log.error("Could not revoke Google Drive authorization", t);
                sendAsync(source, "Revoke failed: " + t.getMessage(), true);
            }
        });
        return 1;
    }

    private static int list(CommandSourceStack source) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime == null) {
            source.sendFailure(Component.literal("GDrive Backup is not initialized."));
            return 0;
        }
        line(source, "Loading Google Drive backup list...");
        runtime.executeDrive(() -> {
            try {
                List<RemoteFile> files = runtime.drive().list();
                if (files.isEmpty()) {
                    sendAsync(source, "No backups found in Google Drive.", false);
                    return;
                }
                sendAsync(source, "Google Drive backups (" + files.size() + "):", false);
                int shown = 0;
                for (RemoteFile file : files) {
                    if (shown++ >= 30) {
                        sendAsync(source, "... and " + (files.size() - 30) + " more.", false);
                        break;
                    }
                    sendAsync(source, file.name() + "  " + Text.formatBytes(file.size())
                            + "  " + format(file.createdAt()), false);
                }
            } catch (Throwable t) {
                Log.error("Could not list Google Drive backups", t);
                sendAsync(source, "Drive list failed: " + t.getMessage(), true);
            }
        });
        return 1;
    }

    private static int prune(CommandSourceStack source) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime == null) {
            source.sendFailure(Component.literal("GDrive Backup is not initialized."));
            return 0;
        }
        line(source, "Pruning old Google Drive backups...");
        runtime.executeDrive(() -> {
            try {
                int deleted = runtime.drive().pruneNow();
                sendAsync(source, "Drive cleanup: removed " + deleted + " old backup(s).", false);
            } catch (Throwable t) {
                Log.error("Could not prune Google Drive backups", t);
                sendAsync(source, "Drive prune failed: " + t.getMessage(), true);
            }
        });
        return 1;
    }

    private static int cleanup(CommandSourceStack source) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime == null) {
            source.sendFailure(Component.literal("GDrive Backup is not initialized."));
            return 0;
        }
        line(source, "Cleaning old local backup archives...");
        runtime.executeDrive(() -> {
            try {
                int deleted = runtime.manager().cleanupLocalNow();
                sendAsync(source, "Local cleanup: removed " + deleted + " old archive(s).", false);
            } catch (Throwable t) {
                Log.error("Could not clean local backups", t);
                sendAsync(source, "Local cleanup failed: " + t.getMessage(), true);
            }
        });
        return 1;
    }

    private static int verify(CommandSourceStack source, String name) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime == null) {
            source.sendFailure(Component.literal("GDrive Backup is not initialized."));
            return 0;
        }
        BackupState state = runtime.state();
        String targetName = name;
        if (targetName == null || targetName.isBlank()) {
            if (state.lastArchive == null) {
                source.sendFailure(Component.literal("No previous archive is recorded."));
                return 0;
            }
            targetName = archiveName(state.lastArchive);
            if ("none".equals(targetName)) {
                source.sendFailure(Component.literal("No previous archive is recorded."));
                return 0;
            }
        }
        String finalName = targetName;
        line(source, "Verifying " + finalName + "...");
        runtime.executeDrive(() -> {
            try {
                sendAsync(source, runtime.drive().verify(finalName), false);
            } catch (Throwable t) {
                Log.error("Could not verify Drive backup {}", finalName, t);
                sendAsync(source, "Verification failed: " + t.getMessage(), true);
            }
        });
        return 1;
    }

    private static int download(CommandSourceStack source, String name) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime == null) {
            source.sendFailure(Component.literal("GDrive Backup is not initialized."));
            return 0;
        }
        line(source, "Downloading " + name + " from Google Drive...");
        runtime.executeDrive(() -> {
            try {
                var target = runtime.drive().download(name);
                sendAsync(source, "Downloaded to " + target, false);
            } catch (Throwable t) {
                Log.error("Could not download Drive backup {}", name, t);
                sendAsync(source, "Download failed: " + t.getMessage(), true);
            }
        });
        return 1;
    }

    private static int status(CommandSourceStack source) {
        BackupRuntime runtime = GDriveBackup.runtime();
        if (runtime == null) {
            source.sendFailure(Component.literal("GDrive Backup is not initialized."));
            return 0;
        }

        BackupState state = runtime.state();
        line(source, "GDrive Backup status");
        line(source, "Running: " + (runtime.manager().isRunning()
                ? "yes (" + runtime.manager().stage() + ")" : "no"));
        line(source, "Schedule: " + runtime.schedule().description());
        line(source, "Next run: " + format(runtime.schedule().nextRun()));
        line(source, "Last success: " + format(state.lastSuccessAt));
        line(source, "Last attempt: " + format(state.lastBackupAt));
        line(source, "Last archive: " + ("none".equals(archiveName(state.lastArchive))
                ? "none"
                : archiveName(state.lastArchive) + " (" + Text.formatBytes(state.lastArchiveSize) + ")"));
        line(source, "Files in last backup: " + state.lastFileCount
                + ", source " + Text.formatBytes(state.lastSourceBytes));
        line(source, "Local directory: " + Config.LOCAL_DIR.get());
        line(source, runtime.drive().statusSummary());
        if (state.pendingUpload != null) {
            line(source, "Pending upload: " + state.pendingUpload.name);
        }
        if (state.lastError != null) {
            line(source, "Last error: " + state.lastError);
        }
        return 1;
    }

    private static void sendAsync(CommandSourceStack source, String message, boolean failure) {
        try {
            MinecraftServer server = source.getServer();
            server.execute(() -> {
                try {
                    if (failure) {
                        source.sendFailure(Component.literal(message));
                    } else {
                        source.sendSuccess(() -> Component.literal(message), false);
                    }
                } catch (RuntimeException e) {
                    Log.warn("Could not send command feedback: {}", message, e);
                }
            });
        } catch (RuntimeException e) {
            Log.warn("Could not schedule command feedback: {}", message, e);
        }
    }

    private static void notifyOpsAsync(MinecraftServer server, String message) {
        try {
            server.execute(() -> server.getPlayerList().getPlayers().stream()
                    .filter(player -> player.hasPermissions(4))
                    .forEach(player -> player.sendSystemMessage(
                            Component.literal("[GDrive Backup] " + message))));
        } catch (RuntimeException e) {
            Log.warn("Could not notify operators", e);
        }
    }

    private static String archiveName(String path) {
        if (path == null || path.isBlank()) {
            return "none";
        }
        try {
            return java.nio.file.Path.of(path).getFileName().toString();
        } catch (RuntimeException e) {
            return path;
        }
    }

    private static String format(Instant instant) {
        if (instant == null || instant.equals(Instant.EPOCH)) {
            return "unknown";
        }
        try {
            return formatter().format(instant);
        } catch (RuntimeException e) {
            return instant.toString();
        }
    }

    private static String format(String instant) {
        if (instant == null || instant.isBlank()) {
            return "never";
        }
        try {
            return format(Instant.parse(instant));
        } catch (RuntimeException e) {
            return instant;
        }
    }

    private static DateTimeFormatter formatter() {
        ZoneId zone;
        try {
            zone = ZoneId.of(Config.TIMEZONE.get());
        } catch (RuntimeException e) {
            zone = ZoneId.systemDefault();
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone);
    }

    private static void line(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
