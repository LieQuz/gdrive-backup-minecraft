package dev.xnaq.gdrivebackup.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BackupInfo {
    public static final String FILE_NAME = "backup-info.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BackupInfo() {
    }

    public static void write(Path stagingRoot, BackupPlan plan, int fileCount, long sourceBytes) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("formatVersion", 1);
        info.put("createdAt", plan.createdAt().toString());
        info.put("reason", plan.reason());
        info.put("serverName", plan.serverName());
        info.put("serverId", plan.serverId());
        info.put("worldName", plan.worldName());
        info.put("minecraftVersion", plan.minecraftVersion());
        info.put("neoForgeVersion", plan.neoForgeVersion());
        info.put("fileCount", fileCount);
        info.put("sourceBytes", sourceBytes);
        info.put("modsIncluded", dev.xnaq.gdrivebackup.Config.INCLUDE_MODS.get());
        info.put("modsManifest", ModsManifest.FILE_NAME);
        info.put("includedRoots", plan.roots().stream()
                .map(root -> root.relative().toString().replace('\\', '/'))
                .map(path -> path.isEmpty() ? "." : path)
                .toList());
        info.put("notes", List.of(
                "This archive is a full snapshot of the selected server data.",
                "Extract it at the root of a stopped server, not inside a running world."
        ));

        Files.writeString(
                stagingRoot.resolve(FILE_NAME),
                GSON.toJson(info),
                StandardCharsets.UTF_8
        );
    }
}
