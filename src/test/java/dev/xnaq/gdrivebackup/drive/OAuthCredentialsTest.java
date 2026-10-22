package dev.xnaq.gdrivebackup.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OAuthCredentialsTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsInstalledClientSecret() throws Exception {
        Path file = tempDir.resolve("client_secret.json");
        Files.writeString(file, """
                {
                  "installed": {
                    "client_id": "client-id.apps.googleusercontent.com",
                    "client_secret": "secret"
                  }
                }
                """);

        ClientSecret secret = ClientSecret.load(file);
        assertEquals("client-id.apps.googleusercontent.com", secret.clientId());
        assertEquals("secret", secret.clientSecret());
    }

    @Test
    void savesAndReloadsCredentials() throws Exception {
        Path file = tempDir.resolve("credentials.json");
        OAuthCredentials credentials = new OAuthCredentials();
        credentials.clientId = "id";
        credentials.clientSecret = "secret";
        credentials.refreshToken = "refresh";
        credentials.accessToken = "access";
        credentials.accessTokenExpiresAt = Instant.now().plusSeconds(3600).toString();
        credentials.scope = GoogleOAuth.SCOPE;
        credentials.save(file);

        OAuthCredentials loaded = OAuthCredentials.load(file);
        assertEquals("refresh", loaded.refreshToken);
        assertEquals("access", loaded.accessToken);
        assertTrue(loaded.hasRefreshToken());
        assertTrue(loaded.accessTokenValid());

        loaded.accessTokenExpiresAt = Instant.now().minusSeconds(60).toString();
        assertFalse(loaded.accessTokenValid());
    }
}
