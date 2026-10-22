package dev.xnaq.gdrivebackup.drive;

import dev.xnaq.gdrivebackup.util.Log;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OAuth 2.0 Device Flow for installed/headless applications.
 *
 * <p>Only the non-sensitive drive.file scope is requested. The user creates a
 * "TVs and Limited Input devices" OAuth client in their own Google Cloud
 * project and authorizes it once with /gdrivebackup auth.</p>
 */
public final class GoogleOAuth implements AccessTokenProvider {
    public static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String DEFAULT_DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String DEFAULT_REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    private static final String USER_AGENT = "gdrivebackup/1.0.0 (NeoForge 1.21.1)";

    private final HttpClient http;
    private final Path clientSecretFile;
    private final Path credentialsFile;
    private final String deviceCodeUrl;
    private final String tokenUrl;
    private final String revokeUrl;
    private volatile OAuthCredentials credentials;

    public GoogleOAuth(Path clientSecretFile, Path credentialsFile) {
        this(clientSecretFile, credentialsFile,
                DEFAULT_DEVICE_CODE_URL, DEFAULT_TOKEN_URL, DEFAULT_REVOKE_URL);
    }

    GoogleOAuth(Path clientSecretFile, Path credentialsFile,
                String deviceCodeUrl, String tokenUrl, String revokeUrl) {
        this.clientSecretFile = clientSecretFile;
        this.credentialsFile = credentialsFile;
        this.deviceCodeUrl = deviceCodeUrl;
        this.tokenUrl = tokenUrl;
        this.revokeUrl = revokeUrl;
        this.credentials = OAuthCredentials.load(credentialsFile);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean isAuthorized() {
        return credentials != null && credentials.hasRefreshToken();
    }

    public OAuthCredentials credentials() {
        return credentials;
    }

    public synchronized void authorize(Consumer<DeviceCode> onCode, Duration timeout) {
        try {
            ClientSecret client = ClientSecret.load(clientSecretFile);

            Map<String, String> deviceRequest = new LinkedHashMap<>();
            deviceRequest.put("client_id", client.clientId());
            deviceRequest.put("scope", SCOPE);

            HttpResponse<String> deviceResponse = postForm(deviceCodeUrl, deviceRequest);
            if (deviceResponse.statusCode() != 200) {
                throw new AuthException("Google device authorization failed: " + errorMessage(deviceResponse));
            }

            JsonObject deviceJson = JsonParser.parseString(deviceResponse.body()).getAsJsonObject();
            String deviceCode = requiredString(deviceJson, "device_code");
            String userCode = requiredString(deviceJson, "user_code");
            String verificationUrl = firstString(deviceJson, "verification_url", "verification_uri");
            if (verificationUrl == null) {
                throw new AuthException("Google device authorization response did not contain a verification URL.");
            }
            int expiresIn = intValue(deviceJson, "expires_in", 1800);
            int interval = Math.max(1, intValue(deviceJson, "interval", 5));

            DeviceCode code = new DeviceCode(verificationUrl, userCode, expiresIn, interval);
            onCode.accept(code);
            Log.info(
                    "Google Drive authorization: open {} and enter code {} (expires in {} seconds)",
                    verificationUrl, userCode, expiresIn);

            Instant deadline = Instant.now().plusSeconds(Math.max(1, expiresIn));
            while (Instant.now().isBefore(deadline)) {
                sleepSeconds(interval);

                Map<String, String> tokenRequest = new LinkedHashMap<>();
                tokenRequest.put("client_id", client.clientId());
                tokenRequest.put("client_secret", client.clientSecret());
                tokenRequest.put("device_code", deviceCode);
                tokenRequest.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");

                HttpResponse<String> tokenResponse = postForm(tokenUrl, tokenRequest);
                if (tokenResponse.statusCode() == 200) {
                    JsonObject token = JsonParser.parseString(tokenResponse.body()).getAsJsonObject();
                    saveFromTokenResponse(token, client, null);
                    Log.info("Google Drive authorization completed.");
                    return;
                }

                JsonObject error = parseObject(tokenResponse.body());
                String codeValue = error == null ? null : string(error, "error");
                if ("authorization_pending".equals(codeValue) || tokenResponse.statusCode() == 428) {
                    continue;
                }
                if ("slow_down".equals(codeValue)) {
                    interval += 5;
                    continue;
                }
                if ("access_denied".equals(codeValue)) {
                    throw new AuthException("Google Drive authorization was denied.");
                }
                if ("expired_token".equals(codeValue)) {
                    throw new AuthException("The Google device code expired. Run /gdrivebackup auth again.");
                }
                throw new AuthException("Google token exchange failed: " + errorMessage(tokenResponse));
            }

            throw new AuthException("Timed out waiting for Google Drive authorization.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Google Drive authorization was interrupted.", e);
        } catch (IOException e) {
            throw new AuthException("Could not contact Google's OAuth service.", e);
        }
    }

    @Override
    public synchronized String accessToken() {
        OAuthCredentials current = credentials;
        if (current != null && current.accessTokenValid()) {
            return current.accessToken;
        }
        return refreshAccessToken();
    }

    public synchronized String refreshAccessToken() {
        OAuthCredentials current = credentials;
        if (current == null || !current.hasRefreshToken()) {
            throw new AuthException("Google Drive is not authorized. Run /gdrivebackup auth.");
        }

        String clientId = current.clientId;
        String clientSecret = current.clientSecret;
        if (clientId == null || clientId.isBlank()) {
            try {
                ClientSecret loaded = ClientSecret.load(clientSecretFile);
                clientId = loaded.clientId();
                clientSecret = loaded.clientSecret();
            } catch (IOException e) {
                throw new AuthException("OAuth client file is unavailable: " + clientSecretFile, e);
            }
        }

        try {
            Map<String, String> request = new LinkedHashMap<>();
            request.put("client_id", clientId);
            request.put("client_secret", clientSecret == null ? "" : clientSecret);
            request.put("refresh_token", current.refreshToken);
            request.put("grant_type", "refresh_token");

            HttpResponse<String> response = postForm(tokenUrl, request);
            if (response.statusCode() == 200) {
                JsonObject token = JsonParser.parseString(response.body()).getAsJsonObject();
                saveFromTokenResponse(token, new ClientSecret(clientId, clientSecret), current.refreshToken);
                return credentials.accessToken;
            }

            JsonObject error = parseObject(response.body());
            String code = error == null ? null : string(error, "error");
            if ("invalid_grant".equals(code)) {
                throw new AuthException(
                        "Google Drive refresh token is expired or revoked. Run /gdrivebackup auth again.");
            }
            throw new AuthException("Google token refresh failed: " + errorMessage(response));
        } catch (IOException e) {
            throw new AuthException("Could not contact Google's token service.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Google token refresh was interrupted.", e);
        }
    }

    @Override
    public synchronized void invalidateAccessToken() {
        OAuthCredentials current = credentials;
        if (current != null) {
            current.accessToken = null;
            current.accessTokenExpiresAt = null;
        }
    }

    public void revoke() {
        OAuthCredentials current = credentials;
        if (current == null) {
            return;
        }
        try {
            if (current.refreshToken != null) {
                Map<String, String> request = new LinkedHashMap<>();
                request.put("token", current.refreshToken);
                postForm(revokeUrl, request);
            }
        } catch (IOException | InterruptedException e) {
            Log.warn("Could not revoke the Google Drive token", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            credentials = null;
            try {
                Files.deleteIfExists(credentialsFile);
            } catch (IOException e) {
                Log.warn("Could not delete credentials file {}", credentialsFile, e);
            }
        }
    }

    private void saveFromTokenResponse(JsonObject token, ClientSecret client, String existingRefreshToken)
            throws IOException {
        OAuthCredentials updated = new OAuthCredentials();
        updated.clientId = client.clientId();
        updated.clientSecret = client.clientSecret();
        updated.accessToken = requiredString(token, "access_token");
        updated.refreshToken = string(token, "refresh_token");
        if (updated.refreshToken == null || updated.refreshToken.isBlank()) {
            updated.refreshToken = existingRefreshToken;
        }
        updated.scope = string(token, "scope");
        updated.tokenType = string(token, "token_type");
        long expiresIn = longValue(token, "expires_in", 3600);
        updated.accessTokenExpiresAt = Instant.now()
                .plusSeconds(Math.max(0, expiresIn - 60))
                .toString();
        if (updated.refreshToken == null || updated.refreshToken.isBlank()) {
            throw new AuthException("Google did not return a refresh token.");
        }
        updated.save(credentialsFile);
        credentials = updated;
    }

    private HttpResponse<String> postForm(String url, Map<String, String> values)
            throws IOException, InterruptedException {
        String body = formEncode(values);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String formEncode(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static void sleepSeconds(int seconds) throws InterruptedException {
        Thread.sleep(Duration.ofSeconds(Math.max(1, seconds)).toMillis());
    }

    private static String errorMessage(HttpResponse<String> response) {
        JsonObject object = parseObject(response.body());
        if (object != null) {
            String error = string(object, "error");
            String description = string(object, "error_description");
            if (error != null || description != null) {
                return "HTTP " + response.statusCode() + " " + error
                        + (description == null ? "" : " (" + description + ")");
            }
        }
        return "HTTP " + response.statusCode() + " " + truncate(response.body());
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    private static JsonObject parseObject(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String requiredString(JsonObject object, String key) {
        String value = string(object, key);
        if (value == null || value.isBlank()) {
            throw new AuthException("Google response did not contain " + key);
        }
        return value;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        try {
            return object.has(key) ? object.get(key).getAsLong() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public record DeviceCode(String verificationUrl, String userCode, int expiresIn, int interval) {
    }
}
