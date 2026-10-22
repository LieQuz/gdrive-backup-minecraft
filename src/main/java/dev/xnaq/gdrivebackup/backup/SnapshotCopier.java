package dev.xnaq.gdrivebackup.backup;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Copies one backup root into the staging directory, then performs a final
 * consistency pass that recopies anything written while the initial copy was
 * running.
 */
public final class SnapshotCopier {
    private final Path sourceRoot;
    private final Path targetRoot;
    private final Path pathPrefix;
    private final BackupPathFilter filter;
    private final boolean worldRoot;
    private final Map<Path, Entry> entries = new LinkedHashMap<>();

    private int copiedFiles;
    private long copiedBytes;
    private int recopiedFiles;
    private long recopiedBytes;

    /**
     * @param sourceRoot absolute path to the source file or directory
     * @param targetRoot absolute path in the staging directory
     * @param pathPrefix path of this root relative to the server directory
     * @param filter exclusion rules
     * @param worldRoot whether this root is the world directory
     */
    public SnapshotCopier(Path sourceRoot, Path targetRoot, Path pathPrefix,
                          BackupPathFilter filter, boolean worldRoot) {
        this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
        this.targetRoot = targetRoot.toAbsolutePath().normalize();
        this.pathPrefix = pathPrefix;
        this.filter = filter;
        this.worldRoot = worldRoot;
    }

    public Path sourceRoot() {
        return sourceRoot;
    }

    public Path targetRoot() {
        return targetRoot;
    }

    public int fileCount() {
        return entries.size();
    }

    public long byteCount() {
        return entries.values().stream().mapToLong(entry -> entry.stamp().size()).sum();
    }

    public long estimateBytes() throws IOException {
        return estimateBytes(sourceRoot, pathPrefix, filter);
    }

    public CopyResult copyInitial() throws IOException {
        if (!Files.exists(sourceRoot)) {
            throw new BackupException("Backup source does not exist: " + sourceRoot);
        }

        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceRoot.relativize(dir);
                Path serverRelative = pathPrefix.resolve(relative);
                if (!relative.toString().isEmpty()
                        && (filter.isExcluded(dir, serverRelative) || filter.isExcluded(dir, relative))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(targetRoot.resolve(relative.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = sourceRoot.relativize(file);
                Path serverRelative = pathPrefix.resolve(relative);
                if (isExcluded(file, serverRelative, relative)) {
                    return FileVisitResult.CONTINUE;
                }
                copyOne(file, relative, false);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw new IOException("Unable to read backup source: " + file, exc);
            }
        });

        return result();
    }

    /**
     * Re-walks the source and recopies files that changed since {@link #copyInitial()}.
     * Must run while the server is paused and saving is disabled.
     */
    public CopyResult finalizeSnapshot() throws IOException {
        return finalizeSnapshot(0L);
    }

    public CopyResult finalizeSnapshot(long deadlineNanos) throws IOException {
        checkDeadline(deadlineNanos);
        recopiedFiles = 0;
        recopiedBytes = 0;

        Map<Path, FileStamp> current = new LinkedHashMap<>();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceRoot.relativize(dir);
                Path serverRelative = pathPrefix.resolve(relative);
                if (!relative.toString().isEmpty()
                        && (filter.isExcluded(dir, serverRelative) || filter.isExcluded(dir, relative))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkDeadline(deadlineNanos);
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = sourceRoot.relativize(file);
                Path serverRelative = pathPrefix.resolve(relative);
                if (isExcluded(file, serverRelative, relative)) {
                    return FileVisitResult.CONTINUE;
                }
                current.put(relative, FileStamp.read(file));
                return FileVisitResult.CONTINUE;
            }
        });

        for (Map.Entry<Path, FileStamp> item : current.entrySet()) {
            checkDeadline(deadlineNanos);
            Path relative = item.getKey();
            FileStamp stamp = item.getValue();
            Entry previous = entries.get(relative);
            boolean force = worldRoot && isMutableWorldPath(relative);
            if (previous == null || previous.dirty() || force || !previous.stamp().equals(stamp)) {
                copyOne(sourceRoot.resolve(relative.toString()), relative, true);
            }
        }

        ArrayList<Path> stale = new ArrayList<>();
        for (Path relative : entries.keySet()) {
            if (!current.containsKey(relative)) {
                stale.add(relative);
            }
        }
        for (Path relative : stale) {
            Files.deleteIfExists(targetRoot.resolve(relative.toString()));
            entries.remove(relative);
        }

        removeEmptyDirectories();
        return result();
    }

    private static void checkDeadline(long deadlineNanos) {
        if (deadlineNanos > 0 && System.nanoTime() > deadlineNanos) {
            throw new BackupException(
                    "Snapshot finalization exceeded the configured freeze budget; backup aborted to protect the server.");
        }
    }

    private boolean isExcluded(Path file, Path serverRelative, Path relative) {
        return filter.isExcluded(file, serverRelative) || filter.isExcluded(file, relative);
    }

    private void copyOne(Path source, Path relative, boolean recopy) throws IOException {
        FileStamp before = FileStamp.read(source);
        Path target = targetRoot.resolve(relative.toString());
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source, java.nio.file.LinkOption.NOFOLLOW_LINKS));

        FileStamp after = FileStamp.read(source);
        boolean dirty = !before.equals(after);
        entries.put(relative, new Entry(after, dirty));

        copiedFiles++;
        copiedBytes += after.size();
        if (recopy) {
            recopiedFiles++;
            recopiedBytes += after.size();
        }
    }

    private void removeEmptyDirectories() throws IOException {
        if (!Files.isDirectory(targetRoot)) {
            return;
        }
        Files.walkFileTree(targetRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                if (!dir.equals(targetRoot)) {
                    try (var children = Files.newDirectoryStream(dir)) {
                        if (!children.iterator().hasNext()) {
                            Files.deleteIfExists(dir);
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isMutableWorldPath(Path relative) {
        String path = relative.toString().replace('\\', '/');
        return path.equals("level.dat")
                || path.equals("level.dat_old")
                || path.endsWith("/level.dat")
                || path.endsWith("/level.dat_old")
                || path.startsWith("playerdata/")
                || path.contains("/playerdata/")
                || path.startsWith("advancements/")
                || path.contains("/advancements/")
                || path.startsWith("stats/")
                || path.contains("/stats/");
    }

    private CopyResult result() {
        long bytes = entries.values().stream().mapToLong(entry -> entry.stamp().size()).sum();
        return new CopyResult(entries.size(), bytes, recopiedFiles, recopiedBytes);
    }

    public static long estimateBytes(Path sourceRoot, Path pathPrefix, BackupPathFilter filter) throws IOException {
        if (!Files.exists(sourceRoot)) {
            return 0;
        }
        long[] total = {0L};
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceRoot.relativize(dir);
                Path serverRelative = pathPrefix.resolve(relative);
                if (!relative.toString().isEmpty()
                        && (filter.isExcluded(dir, serverRelative) || filter.isExcluded(dir, relative))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = sourceRoot.relativize(file);
                Path serverRelative = pathPrefix.resolve(relative);
                if (!(filter.isExcluded(file, serverRelative) || filter.isExcluded(file, relative))) {
                    total[0] += attrs.size();
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    public record Entry(FileStamp stamp, boolean dirty) {
    }

    public record CopyResult(int files, long bytes, int recopiedFiles, long recopiedBytes) {
        public CopyResult plus(CopyResult other) {
            return new CopyResult(
                    files + other.files,
                    bytes + other.bytes,
                    recopiedFiles + other.recopiedFiles,
                    recopiedBytes + other.recopiedBytes
            );
        }
    }
}
