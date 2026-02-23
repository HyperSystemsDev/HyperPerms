package com.hyperperms.config;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Web editor service configuration.
 * <p>
 * File: {@code webeditor.json}
 */
public final class WebEditorConfig extends ConfigFile {

    private String url;
    private String apiUrl;
    private int timeoutSeconds;
    private boolean websocketEnabled;
    private int websocketReconnectMaxAttempts;
    private int websocketReconnectMaxDelaySeconds;
    private int websocketPingTimeoutSeconds;

    public WebEditorConfig(@NotNull Path dataDirectory) {
        super(dataDirectory.resolve("webeditor.json"));
    }

    @Override
    protected void createDefaults() {
        url = "https://www.hyperperms.com";
        apiUrl = "";
        timeoutSeconds = 10;
        websocketEnabled = true;
        websocketReconnectMaxAttempts = 10;
        websocketReconnectMaxDelaySeconds = 30;
        websocketPingTimeoutSeconds = 90;
    }

    @Override
    protected void loadFromJson(@NotNull JsonObject root) {
        url = getString(root, "url", "https://www.hyperperms.com");
        apiUrl = getString(root, "apiUrl", "");
        timeoutSeconds = getInt(root, "timeoutSeconds", 10);
        websocketEnabled = getBool(root, "websocketEnabled", true);
        websocketReconnectMaxAttempts = getInt(root, "websocketReconnectMaxAttempts", 10);
        websocketReconnectMaxDelaySeconds = getInt(root, "websocketReconnectMaxDelaySeconds", 30);
        websocketPingTimeoutSeconds = getInt(root, "websocketPingTimeoutSeconds", 90);
    }

    @Override
    @NotNull
    protected JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("url", url);
        root.addProperty("apiUrl", apiUrl);
        root.addProperty("timeoutSeconds", timeoutSeconds);
        root.addProperty("websocketEnabled", websocketEnabled);
        root.addProperty("websocketReconnectMaxAttempts", websocketReconnectMaxAttempts);
        root.addProperty("websocketReconnectMaxDelaySeconds", websocketReconnectMaxDelaySeconds);
        root.addProperty("websocketPingTimeoutSeconds", websocketPingTimeoutSeconds);
        return root;
    }

    @Override
    @NotNull
    public ValidationResult validate() {
        ValidationResult result = new ValidationResult();
        timeoutSeconds = validateRange(result, "timeoutSeconds", timeoutSeconds, 1, 120, 10);
        websocketReconnectMaxAttempts = validateRange(result, "websocketReconnectMaxAttempts", websocketReconnectMaxAttempts, 1, 100, 10);
        websocketReconnectMaxDelaySeconds = validateRange(result, "websocketReconnectMaxDelaySeconds", websocketReconnectMaxDelaySeconds, 1, 300, 30);
        websocketPingTimeoutSeconds = validateRange(result, "websocketPingTimeoutSeconds", websocketPingTimeoutSeconds, 10, 600, 90);
        return result;
    }

    @NotNull
    public String getUrl() { return url; }

    /**
     * Gets the API URL. Falls back to main URL if not configured.
     */
    @NotNull
    public String getApiUrl() {
        return apiUrl.isEmpty() ? url : apiUrl;
    }

    public int getTimeoutSeconds() { return timeoutSeconds; }

    public boolean isWebsocketEnabled() { return websocketEnabled; }

    public int getWebsocketReconnectMaxAttempts() { return websocketReconnectMaxAttempts; }

    public int getWebsocketReconnectMaxDelaySeconds() { return websocketReconnectMaxDelaySeconds; }

    public int getWebsocketPingTimeoutSeconds() { return websocketPingTimeoutSeconds; }
}
