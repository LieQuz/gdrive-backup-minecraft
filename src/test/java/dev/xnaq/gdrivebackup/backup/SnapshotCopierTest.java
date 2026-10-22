package dev.xnaq.gdrivebackup.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotCopierTest {
    @TempDir
    Path tempDir;

    @Test
    void copiesAndReconcilesChanges() throws IOException {
        Path world = tempDir.resolve("server/world");
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("playerdata"));
        Files.createDirectories(world.resolve("logs"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "region-v1");
        Files.writeString(world.resolve("playerdata/uuid.dat"), "player-v1");
        Files.writeString(world.resolve("level.dat"), "level-v1");
        Files.writeString(world.resolve("session.lock"), "lock");
        Files.writeString(world.resolve("logs/latest.log"), "log");

        Path staging = tempDir.resolve("staging/world");
        BackupPathFilter filter = new BackupPathFilter(List.of(), Set.of());
        SnapshotCopier copier = new SnapshotCopier(
                world, staging, Path.of("world"), filter, true);

        SnapshotCopier.CopyResult initial = copier.copyInitial();
        assertEquals(3, initial.files());
        assertTrue(Files.exists(staging.resolve("region/r.0.0.mca")));
        assertTrue(Files.exists(staging.resolve("playerdata/uuid.dat")));
        assertFalse(Files.exists(staging.resolve("session.lock")));
        assertFalse(Files.exists(staging.resolve("logs/latest.log")));

        Files.writeString(world.resolve("region/r.0.0.mca"), "region-v2");
        Files.writeString(world.resolve("playerdata/uuid.dat"), "player-v2");
        Files.writeString(world.resolve("region/r.0.1.mca"), "new-region");
        Files.delete(world.resolve("level.dat"));
        Files.setLastModifiedTime(
                world.resolve("region/r.0.0.mca"),
                FileTime.fromMillis(System.currentTimeMillis() + 2_000)
        );
        Files.setLastModifiedTime(
                world.resolve("playerdata/uuid.dat"),
                FileTime.fromMillis(System.currentTimeMillis() + 2_000)
        );

        SnapshotCopier.CopyResult finalized = copier.finalizeSnapshot();
        assertEquals("region-v2", Files.readString(staging.resolve("region/r.0.0.mca")));
        assertEquals("player-v2", Files.readString(staging.resolve("playerdata/uuid.dat")));
        assertEquals("new-region", Files.readString(staging.resolve("region/r.0.1.mca")));
        assertFalse(Files.exists(staging.resolve("level.dat")));
        assertEquals(3, finalized.files());
        assertTrue(finalized.recopiedFiles() >= 2);
    }

    @Test
    void freezeDeadlineAbortsFinalization() throws IOException {
        Path world = tempDir.resolve("server/world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "data");

        BackupPathFilter filter = new BackupPathFilter(List.of(), Set.of());
        SnapshotCopier copier = new SnapshotCopier(
                world, tempDir.resolve("staging"), Path.of("world"), filter, true);
        copier.copyInitial();

        assertThrows(BackupException.class,
                () -> copier.finalizeSnapshot(System.nanoTime() - 1));
    }

    @Test
    void estimatesIncludedBytes() throws IOException {
        Path world = tempDir.resolve("server/world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "12345");
        Files.writeString(world.resolve("session.lock"), "x");

        BackupPathFilter filter = new BackupPathFilter(List.of(), Set.of());
        SnapshotCopier copier = new SnapshotCopier(
                world, tempDir.resolve("staging"), Path.of("world"), filter, true);
        assertEquals(5, copier.estimateBytes());
    }
}
