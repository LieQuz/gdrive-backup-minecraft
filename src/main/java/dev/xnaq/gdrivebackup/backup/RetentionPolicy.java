package dev.xnaq.gdrivebackup.backup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RetentionPolicy {
    private RetentionPolicy() {
    }

    /**
     * Returns archives newest first, using the filesystem modification time so
     * that archives created in the same second are still ordered correctly.
     */
    public static List<Path> sortNewestFirst(List<Path> archives) {
        List<Path> sorted = new ArrayList<>(archives);
        sorted.sort(Comparator
                .comparingLong(RetentionPolicy::lastModified)
                .reversed()
                .thenComparing(path -> path.getFileName().toString(), Comparator.reverseOrder()));
        return sorted;
    }

    /**
     * Returns archives to delete, oldest first. At least one archive is always kept.
     */
    public static List<Path> selectOldArchives(List<Path> archives, int keep) {
        int keepCount = Math.max(1, keep);
        List<Path> sorted = sortNewestFirst(archives);
        if (sorted.size() <= keepCount) {
            return List.of();
        }
        return List.copyOf(sorted.subList(keepCount, sorted.size()));
    }

    private static long lastModified(Path path) {
        try {
            return java.nio.file.Files.getLastModifiedTime(path).toMillis();
        } catch (java.io.IOException e) {
            return 0L;
        }
    }
}
