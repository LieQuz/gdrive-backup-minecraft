package dev.xnaq.gdrivebackup.backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.xnaq.gdrivebackup.util.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class ModsManifest {
    public static final String FILE_NAME = "mods-manifest.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModsManifest() {
    }

    public static void write(Path stagingRoot, Path modsDirectory) throws IOException {
        List<Map<String, Object>> mods = new ArrayList<>();

        if (Files.isDirectory(modsDirectory)) {
            try (Stream<Path> stream = Files.list(modsDirectory)) {
                List<Path> jars = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();

                for (Path jar : jars) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("file", jar.getFileName().toString());
                    entry.put("size", Files.size(jar));
                    entry.put("sha256", Hashing.sha256(jar));
                    mods.add(entry);
                }
            }
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", 1);
        manifest.put("generatedAt", Instant.now().toString());
        manifest.put("mods", mods);

        Files.writeString(
                stagingRoot.resolve(FILE_NAME),
                GSON.toJson(manifest),
                StandardCharsets.UTF_8
        );
    }
}
