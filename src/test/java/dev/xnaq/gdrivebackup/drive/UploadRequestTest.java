package dev.xnaq.gdrivebackup.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.xnaq.gdrivebackup.backup.BackupState;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadRequestTest {
    @TempDir
    Path tempDir;

    @Test
    void pendingUploadRoundTrip() {
        BackupState.PendingUpload pending = new BackupState.PendingUpload();
        pending.path = tempDir.resolve("backup.zip").toAbsolutePath().toString();
        pending.name = "backup.zip";
        pending.size = 1234;
        pending.sha256 = "sha";
        pending.md5 = "md5";
        pending.createdAt = "2026-10-22T00:00:00Z";
        pending.reason = "scheduled";
        pending.serverName = "world";
        pending.serverId = "server-id";
        pending.fileCount = 7;
        pending.sourceBytes = 5678;

        DriveService.UploadRequest request = DriveService.UploadRequest.fromPending(pending);
        BackupState.PendingUpload copy = request.toPending();

        assertEquals(pending.path, copy.path);
        assertEquals(pending.name, copy.name);
        assertEquals(pending.size, copy.size);
        assertEquals(pending.sha256, copy.sha256);
        assertEquals(pending.md5, copy.md5);
        assertEquals(pending.createdAt, copy.createdAt);
        assertEquals(pending.reason, copy.reason);
        assertEquals(pending.serverName, copy.serverName);
        assertEquals(pending.serverId, copy.serverId);
        assertEquals(pending.fileCount, copy.fileCount);
        assertEquals(pending.sourceBytes, copy.sourceBytes);
    }
}
