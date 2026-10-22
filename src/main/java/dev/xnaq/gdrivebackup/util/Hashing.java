package dev.xnaq.gdrivebackup.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {
    private static final int BUFFER_SIZE = 1024 * 1024;

    private Hashing() {
    }

    public static String sha256(Path file) throws IOException {
        return digest(file, "SHA-256");
    }

    public static String md5(Path file) throws IOException {
        return digest(file, "MD5");
    }

    public static String sha256(byte[] bytes) {
        MessageDigest digest = newDigest("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static String digest(Path file, String algorithm) throws IOException {
        MessageDigest digest = newDigest(algorithm);
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestIn = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (digestIn.read(buffer) != -1) {
                // Reading updates the digest.
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required digest is unavailable: " + algorithm, e);
        }
    }
}
