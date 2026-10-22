package dev.xnaq.gdrivebackup.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ChecksumsTest {
    private static final String HEX = "68518d16be0c6fbfab918be61d658032";

    @Test
    void acceptsGoogleDriveHexFormat() {
        assertTrue(Checksums.md5Matches(HEX, HEX));
        assertTrue(Checksums.md5Matches(HEX.toUpperCase(), HEX));
    }

    @Test
    void acceptsBase64ForCompatibility() {
        String base64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(HEX));
        assertTrue(Checksums.md5Matches(base64, HEX));
    }

    @Test
    void rejectsMismatchAndHandlesMissingValues() {
        assertFalse(Checksums.md5Matches("00000000000000000000000000000000", HEX));
        assertTrue(Checksums.md5Matches(null, HEX));
        assertTrue(Checksums.md5Matches(HEX, null));
    }
}
