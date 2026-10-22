package dev.xnaq.gdrivebackup.drive;

import dev.xnaq.gdrivebackup.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Minimal Google Drive v3 REST client. It deliberately avoids the Google Java
 * SDK so the mod does not need to shade Guava or other large dependencies.
 */
public final class DriveClient {
    private static final String USER_AGENT = "gdrivebackup/1.0.0 (NeoForge 1.21.1)";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final HttpClient downloadHttp;
    private final AccessTokenProvider tokens;
    private final String apiBase;
    private final String uploadBase;
    private final int maxRetries;
    private final int chunkSize;

    public DriveClient(GoogleOAuth oauth, int maxRetries, int chunkSizeBytes) {
        this(oauth, maxRetries, chunkSizeBytes,
                "https://www.googleapis.com/drive/v3",
                "https://www.googleapis.com/upload/drive/v3");
    }

    DriveClient(AccessTokenProvider tokens, int maxRetries, int chunkSizeBytes,
                String apiBase, String uploadBase) {
        this.tokens = tokens;
        this.apiBase = apiBase;
        this.uploadBase = uploadBase;
        this.maxRetries = Math.max(1, maxRetries);
        int normalized = Math.max(256 * 1024, chunkSizeBytes);
        normalized = (normalized / (256 * 1024)) * (256 * 1024);
        this.chunkSize = Math.min(normalized, 64 * 1024 * 1024);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.downloadHttp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String findFolder(String name, String parentId) {
        String query = "name='" + escapeQuery(name) + "'"
                + " and mimeType='" + FOLDER_MIME + "'"
                + " and trashed=false"
                + " and '" + escapeQuery(parentId) + "' in parents";
        String url = apiBase + "/files?q=" + encode(query)
                + "&fields=files(id,name)&pageSize=10&spaces=drive";
        JsonObject response = getJson(url);
        JsonArray files = response == null ? null : response.getAsJsonArray("files");
        if (files != null && !files.isEmpty()) {
            return files.get(0).getAsJsonObject().get("id").getAsString();
        }
        return null;
    }

    public boolean folderExists(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        JsonObject response = getJson(apiBase + "/files/" + encode(id) + "?fields=id,name,trashed");
        return response != null && (!response.has("trashed") || !response.get("trashed").getAsBoolean());
    }

    public String createFolder(String name, String parentId, Map<String, String> appProperties) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("mimeType", FOLDER_MIME);
        metadata.put("parents", List.of(parentId));
        metadata.put("appProperties", appProperties);

        JsonObject response = postJson(
                apiBase + "/files?fields=id,name",
                GSON.toJson(metadata)
        );
        if (response == null || !response.has("id")) {
            throw new DriveException("Google Drive did not return a folder id for " + name);
        }
        return response.get("id").getAsString();
    }

    public RemoteFile upload(Path archive, String name, String folderId,
                             Map<String, String> appProperties, String md5Hex) {
        long size;
        try {
            size = Files.size(archive);
        } catch (IOException e) {
            throw new DriveException("Could not read archive size: " + archive, e);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("parents", List.of(folderId));
        metadata.put("mimeType", "application/zip");
        metadata.put("appProperties", appProperties);

        String sessionUri = createUploadSession(GSON.toJson(metadata), size);
        try {
            JsonObject completed = uploadContent(sessionUri, archive, size);
            RemoteFile file = parseFile(completed);
            verifyRemote(file, size, md5Hex);
            return file;
        } catch (SessionExpiredException e) {
            Log.warn("Upload session expired; starting a new session for {}", name);
            String retrySession = createUploadSession(GSON.toJson(metadata), size);
            JsonObject completed = uploadContent(retrySession, archive, size);
            RemoteFile file = parseFile(completed);
            verifyRemote(file, size, md5Hex);
            return file;
        }
    }

    public List<RemoteFile> listFiles(String folderId) {
        List<RemoteFile> result = new ArrayList<>();
        String pageToken = null;
        do {
            String query = "'" + escapeQuery(folderId) + "' in parents"
                    + " and trashed=false"
                    + " and appProperties has { key='managedBy' and value='gdrivebackup' }";
            String url = apiBase + "/files?q=" + encode(query)
                    + "&fields=files(id,name,size,createdTime,md5Checksum,appProperties),nextPageToken"
                    + "&pageSize=1000&orderBy=createdTime desc&spaces=drive"
                    + (pageToken == null ? "" : "&pageToken=" + encode(pageToken));
            JsonObject response = getJson(url);
            if (response == null) {
                break;
            }
            JsonArray files = response.getAsJsonArray("files");
            if (files != null) {
                for (JsonElement element : files) {
                    result.add(parseFile(element.getAsJsonObject()));
                }
            }
            pageToken = response.has("nextPageToken") && !response.get("nextPageToken").isJsonNull()
                    ? response.get("nextPageToken").getAsString()
                    : null;
        } while (pageToken != null && !pageToken.isBlank());
        return result;
    }

    public void deleteFile(String id) {
        delete(apiBase + "/files/" + encode(id));
    }

    public void downloadFile(RemoteFile file, Path target) {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.deleteIfExists(part);

            MessageDigest md5 = MessageDigest.getInstance("MD5");
            HttpResponse<InputStream> response = getStream(apiBase + "/files/" + encode(file.id()) + "?alt=media");
            try (InputStream in = response.body();
                 var out = Files.newOutputStream(part);
                 DigestOutputStream digestOut = new DigestOutputStream(out, md5)) {
                in.transferTo(digestOut);
            }

            long downloadedSize = Files.size(part);
            if (file.size() > 0 && downloadedSize != file.size()) {
                Files.deleteIfExists(part);
                throw new DriveException(
                        "Downloaded size " + downloadedSize + " does not match Drive metadata " + file.size()
                                + " for " + file.name());
            }

            String actual = Base64.getEncoder().encodeToString(md5.digest());
            if (file.md5Checksum() != null && !file.md5Checksum().isBlank()
                    && !file.md5Checksum().equals(actual)) {
                Files.deleteIfExists(part);
                throw new DriveException("Downloaded file checksum does not match Drive metadata: " + file.name());
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // ignore cleanup failure
            }
            throw new DriveException("Could not download " + file.name(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }

    private String createUploadSession(String metadataJson, long size) {
        String url = uploadBase + "/files?uploadType=resumable&fields=id,name,size,md5Checksum,createdTime,appProperties";
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("X-Upload-Content-Type", "application/zip")
                    .header("X-Upload-Content-Length", Long.toString(size))
                    .POST(HttpRequest.BodyPublishers.ofString(metadataJson, StandardCharsets.UTF_8))
                    .build();

            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 401) {
                    tokens.invalidateAccessToken();
                    continue;
                }
                if (isRetryable(response.statusCode())) {
                    backoff(attempt, response);
                    continue;
                }
                if (response.statusCode() != 200 && response.statusCode() != 201) {
                    throw new DriveException("Could not create Drive upload session: " + errorMessage(response));
                }
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new DriveException("Drive upload session response did not include a Location header.");
                }
                return location;
            } catch (IOException e) {
                if (attempt == maxRetries) {
                    throw new DriveException("Could not contact Google Drive", e);
                }
                sleep(backoffMillis(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DriveException("Upload was interrupted", e);
            }
        }
        throw new DriveException("Could not create Drive upload session after retries.");
    }

    private JsonObject uploadContent(String sessionUri, Path archive, long size) {
        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ)) {
            byte[] buffer = new byte[chunkSize];
            long offset = 0;
            int attempts = 0;

            while (offset < size) {
                int length = (int) Math.min(buffer.length, size - offset);
                readFully(channel, offset, buffer, length);

                String range = "bytes " + offset + "-" + (offset + length - 1) + "/" + size;
                HttpRequest request = HttpRequest.newBuilder(URI.create(sessionUri))
                        .timeout(Duration.ofMinutes(10))
                        .header("Content-Range", range)
                        .header("User-Agent", USER_AGENT)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(buffer, 0, length))
                        .build();

                try {
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    int status = response.statusCode();
                    if (status == 308) {
                        offset = nextOffset(response, offset + length);
                        attempts = 0;
                        continue;
                    }
                    if (status == 200 || status == 201) {
                        return JsonParser.parseString(response.body()).getAsJsonObject();
                    }
                    if (status == 401) {
                        tokens.invalidateAccessToken();
                        throw new SessionExpiredException();
                    }
                    if (status == 404 || status == 410) {
                        throw new SessionExpiredException();
                    }
                    if (isRetryable(status)) {
                        if (++attempts > maxRetries) {
                            throw new DriveException("Drive upload failed after retries: " + errorMessage(response));
                        }
                        backoff(attempts, response);
                        continue;
                    }
                    throw new DriveException("Drive upload failed: " + errorMessage(response));
                } catch (IOException e) {
                    if (++attempts > maxRetries) {
                        throw new DriveException("Drive upload failed after retries", e);
                    }
                    UploadStatus status = queryStatus(sessionUri, size, offset);
                    if (status.completed() != null) {
                        return status.completed();
                    }
                    offset = status.offset();
                    sleep(backoffMillis(attempts));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DriveException("Upload was interrupted", e);
                }
            }
            throw new DriveException("Upload finished without a Drive response.");
        } catch (IOException e) {
            throw new DriveException("Could not read archive for upload: " + archive, e);
        }
    }

    private UploadStatus queryStatus(String sessionUri, long total, long fallback) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(sessionUri))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Range", "bytes */" + total)
                    .header("User-Agent", USER_AGENT)
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 308) {
                return new UploadStatus(nextOffset(response, fallback), null);
            }
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return new UploadStatus(total, JsonParser.parseString(response.body()).getAsJsonObject());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return new UploadStatus(fallback, null);
    }

    private long nextOffset(HttpResponse<String> response, long fallback) {
        String range = response.headers().firstValue("Range").orElse(null);
        if (range == null || !range.startsWith("bytes=")) {
            return fallback;
        }
        int dash = range.indexOf('-');
        if (dash < 0) {
            return fallback;
        }
        try {
            return Long.parseLong(range.substring(dash + 1)) + 1;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void verifyRemote(RemoteFile file, long size, String md5Hex) {
        if (file.size() > 0 && file.size() != size) {
            deleteFile(file.id());
            throw new DriveException("Drive reported size " + file.size() + " but local size is " + size);
        }
        if (file.md5Checksum() != null && md5Hex != null) {
            String expected = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(md5Hex));
            if (!expected.equals(file.md5Checksum())) {
                deleteFile(file.id());
                throw new DriveException("Drive checksum does not match the local archive.");
            }
        }
    }

    private JsonObject getJson(String url) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 401) {
                    tokens.invalidateAccessToken();
                    continue;
                }
                if (response.statusCode() == 404) {
                    return null;
                }
                if (isRetryable(response.statusCode())) {
                    backoff(attempt, response);
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new DriveException("Drive request failed: " + errorMessage(response));
                }
                return JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (IOException e) {
                if (attempt == maxRetries) {
                    throw new DriveException("Could not contact Google Drive", e);
                }
                sleep(backoffMillis(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DriveException("Drive request was interrupted", e);
            }
        }
        throw new DriveException("Drive request failed after retries.");
    }

    private JsonObject postJson(String url, String json) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 401) {
                    tokens.invalidateAccessToken();
                    continue;
                }
                if (isRetryable(response.statusCode())) {
                    backoff(attempt, response);
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new DriveException("Drive request failed: " + errorMessage(response));
                }
                return JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (IOException e) {
                if (attempt == maxRetries) {
                    throw new DriveException("Could not contact Google Drive", e);
                }
                sleep(backoffMillis(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DriveException("Drive request was interrupted", e);
            }
        }
        throw new DriveException("Drive request failed after retries.");
    }

    private void delete(String url) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("User-Agent", USER_AGENT)
                    .DELETE()
                    .build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 204 || response.statusCode() == 200
                        || response.statusCode() == 404) {
                    return;
                }
                if (response.statusCode() == 401) {
                    tokens.invalidateAccessToken();
                    continue;
                }
                if (isRetryable(response.statusCode())) {
                    backoff(attempt, response);
                    continue;
                }
                throw new DriveException("Could not delete Drive file: " + errorMessage(response));
            } catch (IOException e) {
                if (attempt == maxRetries) {
                    throw new DriveException("Could not contact Google Drive", e);
                }
                sleep(backoffMillis(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DriveException("Drive request was interrupted", e);
            }
        }
    }

    private HttpResponse<InputStream> getStream(String url) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofHours(2))
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response = downloadHttp.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() == 401) {
                    response.body().close();
                    tokens.invalidateAccessToken();
                    continue;
                }
                if (isRetryable(response.statusCode())) {
                    response.body().close();
                    backoff(attempt, response);
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String body;
                    try (InputStream in = response.body()) {
                        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    throw new DriveException("Drive download failed: HTTP " + response.statusCode() + " " + truncate(body));
                }
                return response;
            } catch (IOException e) {
                if (attempt == maxRetries) {
                    throw new DriveException("Could not contact Google Drive", e);
                }
                sleep(backoffMillis(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DriveException("Drive request was interrupted", e);
            }
        }
        throw new DriveException("Drive download failed after retries.");
    }

    private static void readFully(FileChannel channel, long offset, byte[] buffer, int length) throws IOException {
        ByteBuffer target = ByteBuffer.wrap(buffer, 0, length);
        long position = offset;
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) {
                throw new IOException("Unexpected end of archive at " + position);
            }
            position += read;
        }
    }

    private static RemoteFile parseFile(JsonObject object) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (object.has("appProperties") && object.get("appProperties").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("appProperties").entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    properties.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        long size = 0;
        if (object.has("size") && !object.get("size").isJsonNull()) {
            try {
                size = object.get("size").getAsLong();
            } catch (RuntimeException ignored) {
                // leave zero
            }
        }
        return new RemoteFile(
                string(object, "id"),
                string(object, "name"),
                size,
                string(object, "createdTime"),
                string(object, "md5Checksum"),
                properties
        );
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private void backoff(int attempt, HttpResponse<?> response) {
        long millis = backoffMillis(attempt);
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        if (retryAfter != null) {
            try {
                millis = Math.max(millis, Long.parseLong(retryAfter) * 1000L);
            } catch (NumberFormatException ignored) {
                // use exponential backoff
            }
        }
        sleep(millis);
    }

    private static long backoffMillis(int attempt) {
        long base = Math.min(30_000L, 500L * (1L << Math.min(attempt, 6)));
        return base + ThreadLocalRandom.current().nextLong(0, 250);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DriveException("Drive request was interrupted", e);
        }
    }

    private static String errorMessage(HttpResponse<String> response) {
        String body = response.body();
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            if (object.has("error") && object.get("error").isJsonObject()) {
                JsonObject error = object.getAsJsonObject("error");
                String message = string(error, "message");
                if (message != null) {
                    return "HTTP " + response.statusCode() + " " + message;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "HTTP " + response.statusCode() + " " + truncate(body);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String escapeQuery(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static final class SessionExpiredException extends RuntimeException {
    }

    private record UploadStatus(long offset, JsonObject completed) {
    }
}
