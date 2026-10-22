package dev.xnaq.gdrivebackup.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoogleOAuthTest {
    @TempDir
    Path tempDir;

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void deviceFlowThenRefreshAndRevoke() throws Exception {
        Path secretFile = tempDir.resolve("client_secret.json");
        Files.writeString(secretFile, """
                {"installed":{"client_id":"client","client_secret":"secret"}}
                """);
        Path credentialsFile = tempDir.resolve("credentials.json");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger tokenCalls = new AtomicInteger();

        server.createContext("/device/code", exchange -> respond(exchange, 200, """
                {
                  "device_code":"device",
                  "user_code":"ABCD-EFGH",
                  "verification_url":"http://127.0.0.1:%d/device",
                  "expires_in":30,
                  "interval":1
                }
                """.formatted(port)));

        server.createContext("/token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("grant_type=refresh_token")) {
                respond(exchange, 200, """
                        {
                          "access_token":"refreshed",
                          "expires_in":3600,
                          "scope":"https://www.googleapis.com/auth/drive.file",
                          "token_type":"Bearer"
                        }
                        """);
                return;
            }
            if (tokenCalls.incrementAndGet() == 1) {
                respond(exchange, 400, "{\"error\":\"authorization_pending\"}");
                return;
            }
            respond(exchange, 200, """
                    {
                      "access_token":"access",
                      "expires_in":3600,
                      "refresh_token":"refresh",
                      "scope":"https://www.googleapis.com/auth/drive.file",
                      "token_type":"Bearer"
                    }
                    """);
        });

        server.createContext("/revoke", exchange -> respond(exchange, 200, ""));
        server.start();

        String base = "http://127.0.0.1:" + port;
        GoogleOAuth oauth = new GoogleOAuth(
                secretFile,
                credentialsFile,
                base + "/device/code",
                base + "/token",
                base + "/revoke"
        );

        AtomicReference<GoogleOAuth.DeviceCode> code = new AtomicReference<>();
        CountDownLatch codeReceived = new CountDownLatch(1);
        executor = Executors.newSingleThreadExecutor();
        var future = executor.submit(() -> oauth.authorize(deviceCode -> {
            code.set(deviceCode);
            codeReceived.countDown();
        }, Duration.ofSeconds(10)));

        assertTrue(codeReceived.await(5, TimeUnit.SECONDS));
        assertNotNull(code.get());
        assertEquals("ABCD-EFGH", code.get().userCode());
        future.get(10, TimeUnit.SECONDS);

        assertTrue(oauth.isAuthorized());
        assertEquals("access", oauth.accessToken());
        OAuthCredentials saved = OAuthCredentials.load(credentialsFile);
        assertNotNull(saved);
        assertEquals("refresh", saved.refreshToken);

        saved.accessToken = null;
        saved.accessTokenExpiresAt = null;
        saved.save(credentialsFile);
        GoogleOAuth refreshed = new GoogleOAuth(
                secretFile,
                credentialsFile,
                base + "/device/code",
                base + "/token",
                base + "/revoke"
        );
        assertEquals("refreshed", refreshed.accessToken());

        refreshed.revoke();
        assertFalse(Files.exists(credentialsFile));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
