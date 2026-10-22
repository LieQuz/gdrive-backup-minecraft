package dev.xnaq.gdrivebackup.backup;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams the staging tree into a ZIP file and computes SHA-256/MD5 while
 * writing, so the archive does not need to be read a second time.
 */
public final class ZipArchiver {
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final long PROGRESS_INTERVAL = 512L * 1024L * 1024L;
    private static final Set<String> ALREADY_COMPRESSED_EXTENSIONS = Set.of(
            "mca", "mcr", "dat", "nbt", "zip", "jar", "png", "jpg", "jpeg",
            "ogg", "gz", "xz", "zst", "lz4", "webp"
    );

    private ZipArchiver() {
    }

    public static ArchiveResult create(Path stagingRoot, Path outputDir, String baseName,
                                       int compressionLevel, LongConsumer progress) throws IOException {
        Files.createDirectories(outputDir);

        Path part = outputDir.resolve(baseName + ".zip.part");
        Path finalPath = uniqueArchivePath(outputDir, baseName);
        Files.deleteIfExists(part);

        MessageDigest sha256 = digest("SHA-256");
        MessageDigest md5 = digest("MD5");
        long processed = 0;

        try {
            try (OutputStream fileOut = Files.newOutputStream(part);
                 BufferedOutputStream buffered = new BufferedOutputStream(fileOut, BUFFER_SIZE);
                 DigestOutputStream shaOut = new DigestOutputStream(buffered, sha256);
                 DigestOutputStream md5Out = new DigestOutputStream(shaOut, md5);
                 ZipOutputStream zip = new ZipOutputStream(md5Out, StandardCharsets.UTF_8)) {

                List<Path> paths;
                try (Stream<Path> stream = Files.walk(stagingRoot)) {
                    paths = stream
                            .sorted(Comparator.comparing(Path::toString))
                            .toList();
                }

                long lastProgress = 0;

                for (Path path : paths) {
                    if (path.equals(stagingRoot)) {
                        continue;
                    }
                    String name = stagingRoot.relativize(path).toString().replace('\\', '/');
                    if (Files.isDirectory(path)) {
                        if (!name.endsWith("/")) {
                            name += "/";
                        }
                        ZipEntry entry = new ZipEntry(name);
                        entry.setLastModifiedTime(Files.getLastModifiedTime(path));
                        zip.putNextEntry(entry);
                        zip.closeEntry();
                        continue;
                    }
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }

                    ZipEntry entry = new ZipEntry(name);
                    entry.setLastModifiedTime(Files.getLastModifiedTime(path));
                    zip.setLevel(levelFor(name, compressionLevel));
                    zip.putNextEntry(entry);
                    Files.copy(path, zip);
                    zip.closeEntry();

                    processed += Files.size(path);
                    if (processed - lastProgress >= PROGRESS_INTERVAL) {
                        lastProgress = processed;
                        if (progress != null) {
                            progress.accept(processed);
                        }
                    }
                }
            }

            try {
                Files.move(part, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(part, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(finalPath);
            if (progress != null) {
                progress.accept(processed);
            }
            return new ArchiveResult(
                    finalPath,
                    size,
                    HexFormat.of().formatHex(sha256.digest()),
                    HexFormat.of().formatHex(md5.digest())
            );
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }

    private static Path uniqueArchivePath(Path outputDir, String baseName) {
        Path candidate = outputDir.resolve(baseName + ".zip");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = outputDir.resolve(baseName + "-" + suffix + ".zip");
            suffix++;
        }
        return candidate;
    }

    private static int levelFor(String name, int configuredLevel) {
        if (configuredLevel <= 0) {
            return Deflater.NO_COMPRESSION;
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ALREADY_COMPRESSED_EXTENSIONS.contains(extension)) {
                return Deflater.NO_COMPRESSION;
            }
        }
        return configuredLevel;
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required digest is unavailable: " + algorithm, e);
        }
    }

    public record ArchiveResult(Path archive, long size, String sha256, String md5) {
    }
}
