package dev.xnaq.gdrivebackup.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipArchiverTest {
    @TempDir
    Path tempDir;

    @Test
    void createsVerifiedArchive() throws IOException {
        Path staging = tempDir.resolve("staging");
        Files.createDirectories(staging.resolve("world/region"));
        Files.createDirectories(staging.resolve("world/empty"));
        Files.writeString(staging.resolve("backup-info.json"), "{\"formatVersion\":1}");
        byte[] region = new byte[2048];
        for (int i = 0; i < region.length; i++) {
            region[i] = (byte) (i * 31);
        }
        Files.write(staging.resolve("world/region/r.0.0.mca"), region);

        Path output = tempDir.resolve("out");
        ZipArchiver.ArchiveResult result = ZipArchiver.create(
                staging, output, "minecraft-backup-test", 1, bytes -> {
                });

        assertTrue(Files.exists(result.archive()));
        assertTrue(result.size() > 0);
        assertEquals(64, result.sha256().length());
        assertEquals(32, result.md5().length());

        try (ZipFile zip = new ZipFile(result.archive().toFile())) {
            assertTrue(zip.getEntry("backup-info.json") != null);
            assertTrue(zip.getEntry("world/empty/") != null);
            ZipEntry entry = zip.getEntry("world/region/r.0.0.mca");
            assertTrue(entry != null);
            try (InputStream in = zip.getInputStream(entry)) {
                assertArrayEquals(region, in.readAllBytes());
            }
        }

        ZipArchiver.ArchiveResult second = ZipArchiver.create(
                staging, output, "minecraft-backup-test", 1, bytes -> {
                });
        assertNotEquals(result.archive(), second.archive());
    }
}
