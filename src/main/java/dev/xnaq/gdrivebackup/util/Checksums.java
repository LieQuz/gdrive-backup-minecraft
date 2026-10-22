package dev.xnaq.gdrivebackup.util;

import java.util.Base64;
import java.util.HexFormat;

public final class Checksums {
    private Checksums() {
    }

    /**
     * Google Drive API v3 returns md5Checksum as a lowercase hex string.
     * Some other APIs use Base64, so accept both forms defensively.
     */
    public static boolean md5Matches(String remoteChecksum, String localHex) {
        if (remoteChecksum == null || remoteChecksum.isBlank()
                || localHex == null || localHex.isBlank()) {
            return true;
        }

        String remote = remoteChecksum.trim();
        if (remote.equalsIgnoreCase(localHex)) {
            return true;
        }

        try {
            byte[] digest = HexFormat.of().parseHex(localHex);
            if (remote.equals(Base64.getEncoder().encodeToString(digest))) {
                return true;
            }
            if (remote.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest))) {
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            // localHex is not valid hex; fall through to mismatch.
        }
        return false;
    }
}
