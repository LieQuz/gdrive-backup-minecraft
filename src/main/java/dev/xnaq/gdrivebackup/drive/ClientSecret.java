package dev.xnaq.gdrivebackup.drive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The client_id/client_secret pair from a Google Cloud "TVs and Limited Input
 * devices" OAuth client JSON file.
 */
public record ClientSecret(String clientId, String clientSecret) {
    public static ClientSecret load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new AuthException("OAuth client file not found: " + file);
        }

        JsonObject root = JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8)
        ).getAsJsonObject();

        JsonObject section = root;
        if (root.has("installed") && root.get("installed").isJsonObject()) {
            section = root.getAsJsonObject("installed");
        } else if (root.has("web") && root.get("web").isJsonObject()) {
            section = root.getAsJsonObject("web");
        }

        String clientId = string(section, "client_id");
        String clientSecret = string(section, "client_secret");
        if (clientId == null || clientId.isBlank()) {
            throw new AuthException("OAuth client JSON does not contain client_id: " + file);
        }
        return new ClientSecret(clientId, clientSecret == null ? "" : clientSecret);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }
}
