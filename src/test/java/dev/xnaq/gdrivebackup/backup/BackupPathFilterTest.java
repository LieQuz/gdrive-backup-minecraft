package dev.xnaq.gdrivebackup.backup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BackupPathFilterTest {
    @Test
    void defaultExclusions() {
        BackupPathFilter filter = new BackupPathFilter(List.of(), Set.of());
        assertTrue(filter.isExcluded(Path.of("/server/logs/latest.log"), Path.of("logs/latest.log")));
        assertTrue(filter.isExcluded(Path.of("/server/world/session.lock"), Path.of("world/session.lock")));
        assertTrue(filter.isExcluded(
                Path.of("/server/config/gdrivebackup/credentials.json"),
                Path.of("config/gdrivebackup/credentials.json")));
        assertFalse(filter.isExcluded(Path.of("/server/server.properties"), Path.of("server.properties")));
    }

    @Test
    void customGlobAndPrefix() {
        BackupPathFilter filter = new BackupPathFilter(
                List.of("world/datapacks/large/**", "custom"),
                Set.of()
        );
        assertTrue(filter.isExcluded(
                Path.of("/server/world/datapacks/large/a.bin"),
                Path.of("world/datapacks/large/a.bin")));
        assertTrue(filter.isExcluded(
                Path.of("/server/custom/a.bin"),
                Path.of("custom/a.bin")));
        assertFalse(filter.isExcluded(
                Path.of("/server/world/region/r.0.0.mca"),
                Path.of("world/region/r.0.0.mca")));
    }
}
