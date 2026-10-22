package dev.xnaq.gdrivebackup.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionPolicyTest {
    @TempDir
    Path tempDir;

    @Test
    void newestFirstAndOldestDeleted() throws IOException {
        Path old = create("minecraft-backup-20261020-000000.zip", 1_000);
        Path middle = create("minecraft-backup-20261021-000000.zip", 2_000);
        Path newest = create("minecraft-backup-20261022-000000.zip", 3_000);

        List<Path> sorted = RetentionPolicy.sortNewestFirst(List.of(old, middle, newest));
        assertEquals(List.of(newest, middle, old), sorted);

        List<Path> toDelete = RetentionPolicy.selectOldArchives(List.of(old, middle, newest), 2);
        assertEquals(List.of(old), toDelete);
    }

    @Test
    void atLeastOneArchiveIsKept() throws IOException {
        Path only = create("minecraft-backup-20261020-000000.zip", 1_000);
        assertTrue(RetentionPolicy.selectOldArchives(List.of(only), 0).isEmpty());
    }

    private Path create(String name, long modified) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, name);
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified));
        return path;
    }
}
