package dev.xnaq.gdrivebackup.drive;

import java.time.Instant;
import java.util.Map;

public record RemoteFile(
        String id,
        String name,
        long size,
        String createdTime,
        String md5Checksum,
        Map<String, String> appProperties
) {
    public Instant createdAt() {
        try {
            return createdTime == null ? Instant.EPOCH : Instant.parse(createdTime);
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
