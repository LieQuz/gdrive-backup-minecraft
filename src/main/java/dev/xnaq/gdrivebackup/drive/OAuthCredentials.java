package dev.xnaq.gdrivebackup.drive;

import dev.xnaq.gdrivebackup.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Locally stored OAuth token state. The refresh token is the long-lived secret;
 * the file is restricted to the current user where the platform supports it.
 */
public final class OAuthCredentials {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String clientId;
    public String clientSecret;
    public String refreshToken;
    public String accessToken;
    public String accessTokenExpiresAt;
    public String scope;
    public String tokenType;

    public static OAuthCredentials load(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            OAuthCredentials credentials = GSON.fromJson(
                    Files.readString(file, StandardCharsets.UTF_8),
                    OAuthCredentials.class
            );
            if (credentials == null) {
                return null;
            }
            credentials.warnIfPermissionsAreOpen(file);
            return credentials;
        } catch (Exception e) {
            Log.warn("Could not read OAuth credentials from {}", file, e);
            return null;
        }
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    public boolean accessTokenValid() {
        if (accessToken == null || accessToken.isBlank() || accessTokenExpiresAt == null) {
            return false;
        }
        try {
            return Instant.parse(accessTokenExpiresAt).isAfter(Instant.now().plusSeconds(60));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public void save(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictPermissions(file);
    }

    private void warnIfPermissionsAreOpen(Path file) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                Log.warn(
                        "OAuth credentials {} are readable by other users; restricting to owner-only", file);
                restrictPermissions(file);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX permissions are not available on this platform.
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Best effort on Windows and unusual filesystems.
        }
    }
}
