package dev.xnaq.gdrivebackup.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;

/**
 * A lightweight change marker for a regular file. Size plus the nanosecond
 * modification time is sufficient to detect writes that happened while the
 * staging copy was in progress.
 */
public record FileStamp(long size, long modifiedNanos) {
    public static FileStamp read(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new FileStamp(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS));
    }
}
