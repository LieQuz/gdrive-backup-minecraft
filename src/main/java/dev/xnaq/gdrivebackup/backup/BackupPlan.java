package dev.xnaq.gdrivebackup.backup;

import dev.xnaq.gdrivebackup.Config;
import dev.xnaq.gdrivebackup.util.Text;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;

/**
 * Immutable description of one backup run: what to copy, where to stage it,
 * and which archive name to use.
 */
public record BackupPlan(
        Path serverRoot,
        Path worldRoot,
        String worldName,
        String serverName,
        String serverId,
        String minecraftVersion,
        String neoForgeVersion,
        String reason,
        Instant createdAt,
        Path localDir,
        Path stagingRoot,
        String archiveBaseName,
        List<Root> roots,
        Set<Path> excludedRoots
) {
    public record Root(Path source, Path relative, boolean worldRoot) {
    }

    public static BackupPlan create(MinecraftServer server, String serverId, String reason, Instant now) {
        Path serverRoot = server.getServerDirectory().toAbsolutePath().normalize();
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (!Files.isDirectory(worldRoot)) {
            throw new BackupException("World directory does not exist: " + worldRoot);
        }
        if (!worldRoot.startsWith(serverRoot)) {
            throw new BackupException("World directory is outside the server directory: " + worldRoot);
        }

        String worldName = server.getWorldData().getLevelName();
        String configuredName = Config.SERVER_NAME.get();
        String serverName = Text.sanitize(
                configuredName == null || configuredName.isBlank() ? worldName : configuredName,
                "minecraft"
        );

        Path localDir = resolve(serverRoot, Config.LOCAL_DIR.get());
        Path stagingRoot = localDir.resolve("staging").resolve(Text.timestamp(now));
        String archiveBaseName = "minecraft-backup-" + Text.timestamp(now) + "-" + Text.reason(reason);

        List<Path> candidates = new ArrayList<>();
        candidates.add(worldRoot);
        candidates.add(serverRoot.resolve("server.properties"));
        candidates.add(serverRoot.resolve("ops.json"));
        candidates.add(serverRoot.resolve("whitelist.json"));
        candidates.add(serverRoot.resolve("banned-players.json"));
        candidates.add(serverRoot.resolve("banned-ips.json"));
        candidates.add(serverRoot.resolve("usercache.json"));

        if (Config.INCLUDE_CONFIG.get()) {
            candidates.add(serverRoot.resolve("config"));
            candidates.add(serverRoot.resolve("defaultconfigs"));
        }
        if (Config.INCLUDE_MODS.get()) {
            candidates.add(serverRoot.resolve("mods"));
        }

        for (String extra : Config.EXTRA_PATHS.get()) {
            if (extra == null || extra.isBlank()) {
                continue;
            }
            Path path = resolve(serverRoot, extra);
            if (!path.startsWith(serverRoot)) {
                throw new BackupException("Extra backup path must be inside the server directory: " + path);
            }
            candidates.add(path);
        }

        List<Root> roots = new ArrayList<>();
        for (Path candidate : candidates) {
            Path source = candidate.toAbsolutePath().normalize();
            if (!Files.exists(source)) {
                continue;
            }
            if (!source.startsWith(serverRoot)) {
                throw new BackupException("Backup source is outside the server directory: " + source);
            }

            boolean covered = false;
            for (Root existing : roots) {
                if (source.startsWith(existing.source())) {
                    covered = true;
                    break;
                }
            }
            if (covered) {
                continue;
            }

            roots.removeIf(existing -> existing.source().startsWith(source));
            Path relative = serverRoot.relativize(source);
            if (relative.startsWith("..")) {
                throw new BackupException("Invalid backup source: " + source);
            }
            boolean isWorldRoot = source.equals(worldRoot) || source.equals(serverRoot);
            roots.add(new Root(source, relative, isWorldRoot));
        }

        if (roots.isEmpty()) {
            throw new BackupException("No backup sources were found under " + serverRoot);
        }

        Set<Path> excludedRoots = new LinkedHashSet<>();
        excludedRoots.add(localDir.toAbsolutePath().normalize());
        excludedRoots.add(stagingRoot.toAbsolutePath().normalize());

        String neoForgeVersion = ModList.get().getModContainerById("neoforge")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");

        return new BackupPlan(
                serverRoot,
                worldRoot,
                worldName,
                serverName,
                serverId,
                server.getServerVersion(),
                neoForgeVersion,
                reason,
                now,
                localDir,
                stagingRoot,
                archiveBaseName,
                List.copyOf(roots),
                Set.copyOf(excludedRoots)
        );
    }

    public static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            path = base.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }
}
