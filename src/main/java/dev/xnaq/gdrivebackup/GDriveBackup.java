package dev.xnaq.gdrivebackup;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Entry point for the GDrive Backup server mod.
 */
@Mod(GDriveBackup.MOD_ID)
public final class GDriveBackup {
    public static final String MOD_ID = "gdrivebackup";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static volatile BackupRuntime runtime;

    public GDriveBackup(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.addListener(ServerEvents::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onServerTick);

        LOGGER.info("GDrive Backup loaded");
    }

    public static BackupRuntime runtime() {
        return runtime;
    }

    static void setRuntime(BackupRuntime newRuntime) {
        runtime = newRuntime;
    }
}
