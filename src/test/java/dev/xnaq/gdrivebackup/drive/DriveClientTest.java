package dev.xnaq.gdrivebackup.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.xnaq.gdrivebackup.util.Hashing;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriveClientTest {
    @TempDir
    Path tempDir;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resumableUploadHandles308AndVerifiesMd5() throws Exception {
        byte[] data = new byte[600_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 17);
        }
        Path archive = tempDir.resolve("test.zip");
        Files.write(archive, data);
        String md5Hex = Hashing.md5(archive);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicLong received = new AtomicLong();
        AtomicInteger putCount = new AtomicInteger();
        int port = server.getAddress().getPort();

        server.createContext("/upload/drive/v3/files", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(true, new String(body, StandardCharsets.UTF_8).contains("test.zip"));
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/session");
            respond(exchange, 200, "{}");
        });

        server.createContext("/session", exchange -> {
            try {
                String range = exchange.getRequestHeaders().getFirst("Content-Range");
                assertNotNull(range);
                putCount.incrementAndGet();
                exchange.getRequestBody().readAllBytes();

                String[] parts = range.substring("bytes ".length()).split("[/-]");
                long start = Long.parseLong(parts[0]);
                long end = Long.parseLong(parts[1]);
                long total = Long.parseLong(parts[2]);
                assertEquals(received.get(), start);
                received.set(end + 1);

                if (received.get() < total) {
                    exchange.getResponseHeaders().add("Range", "bytes=0-" + (received.get() - 1));
                    respond(exchange, 308, "{}");
                    return;
                }

                // Google Drive API v3 returns md5Checksum as a hex string.
                String json = "{\"id\":\"file-1\",\"name\":\"test.zip\",\"size\":" + total
                        + ",\"md5Checksum\":\"" + md5Hex
                        + "\",\"createdTime\":\"2026-10-22T00:00:00Z\"}";
                respond(exchange, 200, json);
            } catch (Throwable t) {
                t.printStackTrace();
                exchange.close();
            }
        });

        server.start();

        AccessTokenProvider tokens = new AccessTokenProvider() {
            @Override
            public String accessToken() {
                return "test-token";
            }

            @Override
            public void invalidateAccessToken() {
            }
        };

        DriveClient client = new DriveClient(
                tokens,
                2,
                256 * 1024,
                "http://127.0.0.1:" + port + "/drive/v3",
                "http://127.0.0.1:" + port + "/upload/drive/v3"
        );

        RemoteFile uploaded = client.upload(
                archive,
                "test.zip",
                "folder-1",
                Map.of("managedBy", "gdrivebackup"),
                md5Hex
        );

        assertEquals("file-1", uploaded.id());
        assertEquals("test.zip", uploaded.name());
        assertEquals(data.length, uploaded.size());
        assertEquals(data.length, received.get());
        assertEquals(3, putCount.get());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
