package com.hyperperms.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.hyperperms.util.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Main configuration wrapper for HyperPerms.
 */
public final class HyperPermsConfig {

    private static final String CONFIG_FILE = "config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configFile;
    private JsonObject config;

    public HyperPermsConfig(@NotNull Path dataDirectory) {
        this.configFile = dataDirectory.resolve(CONFIG_FILE);
    }

    /**
     * Loads the configuration from disk, creating defaults if necessary.
     */
    public void load() {
        try {
            if (!Files.exists(configFile)) {
                saveDefaultConfig();
            }
            String json = Files.readString(configFile);
            config = GSON.fromJson(json, JsonObject.class);
            if (config == null) {
                Logger.warn("Configuration file was empty, using defaults");
                config = createDefaultConfig();
            }

            // Migrate older configs to add new fields
            if (migrateConfig()) {
                save();
                Logger.info("Configuration migrated to latest version");
            }

            Logger.info("Configuration loaded");
        } catch (JsonSyntaxException e) {
            Logger.severe("Configuration file is corrupted (invalid JSON), using defaults", e);
            config = createDefaultConfig();
        } catch (IOException e) {
            Logger.severe("Failed to load configuration", e);
            config = createDefaultConfig();
        }
    }

    private static final String CURRENT_CONFIG_VERSION = "2.7.5";

    /**
     * Migrates older config versions to the latest format.
     * @return true if any migrations were applied
     */
    private boolean migrateConfig() {
        boolean migrated = false;

        // Get current config version (default to "0.0.0" if not present)
        String configVersion = "0.0.0";
        if (config.has("configVersion") && config.get("configVersion").isJsonPrimitive()) {
            configVersion = config.get("configVersion").getAsString();
        }

        // Migration: Add webEditor.apiUrl if missing (v2.7.4+)
        if (config.has("webEditor") && config.get("webEditor").isJsonObject()) {
            JsonObject webEditor = config.getAsJsonObject("webEditor");
            if (!webEditor.has("apiUrl")) {
                // Default to Cloudflare Worker endpoint for new installs
                webEditor.addProperty("apiUrl", "https://api.hyperperms.com");
                Logger.info("Config migration: Added webEditor.apiUrl (Cloudflare Workers API endpoint)");
                migrated = true;
            }
        }

        // Migration: Add console settings if missing (v2.7.4+)
        if (!config.has("console")) {
            JsonObject console = new JsonObject();
            console.addProperty("clickableLinks", true);
            console.addProperty("forceOsc8", false);
            config.add("console", console);
            Logger.info("Config migration: Added console settings for clickable links");
            migrated = true;
        }

        // Migration: Add templates settings if missing (v2.7.4+)
        if (!config.has("templates")) {
            JsonObject templates = new JsonObject();
            templates.addProperty("customDirectory", "templates");
            config.add("templates", templates);
            Logger.info("Config migration: Added templates settings");
            migrated = true;
        }

        // Migration: Add analytics settings if missing (disabled by default - opt-in feature)
        if (!config.has("analytics")) {
            JsonObject analytics = new JsonObject();
            analytics.addProperty("enabled", false);
            analytics.addProperty("trackChecks", true);
            analytics.addProperty("trackChanges", true);
            analytics.addProperty("flushIntervalSeconds", 60);
            analytics.addProperty("retentionDays", 90);
            config.add("analytics", analytics);
            Logger.info("Config migration: Added analytics settings (disabled by default)");
            migrated = true;
        }

        // Migration: Add PlaceholderAPI settings if missing
        if (!config.has("placeholderapi")) {
            JsonObject placeholderapi = new JsonObject();
            placeholderapi.addProperty("enabled", true);
            placeholderapi.addProperty("parseExternal", true);
            config.add("placeholderapi", placeholderapi);
            Logger.info("Config migration: Added PlaceholderAPI integration settings");
            migrated = true;
        }

        // Migration: Add MysticNameTags integration settings if missing
        if (!config.has("mysticnametags")) {
            JsonObject mysticnametags = new JsonObject();
            mysticnametags.addProperty("enabled", true);
            mysticnametags.addProperty("refreshOnPermissionChange", true);
            mysticnametags.addProperty("refreshOnGroupChange", true);
            mysticnametags.addProperty("tagPermissionPrefix", "mysticnametags.tag.");
            config.add("mysticnametags", mysticnametags);
            Logger.info("Config migration: Added MysticNameTags integration settings");
            migrated = true;
        }

        // Migration: Add useSSL to mysql storage config if missing
        if (config.has("storage") && config.get("storage").isJsonObject()) {
            JsonObject storage = config.getAsJsonObject("storage");
            if (storage.has("mysql") && storage.get("mysql").isJsonObject()) {
                JsonObject mysql = storage.getAsJsonObject("mysql");
                if (!mysql.has("useSSL")) {
                    mysql.addProperty("useSSL", false);
                    Logger.info("Config migration: Added storage.mysql.useSSL setting");
                    migrated = true;
                }
            }
        }

        // Update config version if migration occurred or version is outdated
        if (migrated || compareVersions(configVersion, CURRENT_CONFIG_VERSION) < 0) {
            config.addProperty("configVersion", CURRENT_CONFIG_VERSION);
            migrated = true;
        }

        return migrated;
    }

    /**
     * Compares two semantic version strings.
     * @return negative if v1 < v2, zero if equal, positive if v1 > v2
     */
    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLength = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < maxLength; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
            if (num1 != num2) {
                return num1 - num2;
            }
        }
        return 0;
    }

    private int parseVersionPart(String part) {
        try {
            // Remove any non-numeric suffix (e.g., "-SNAPSHOT", "-beta")
            String numericPart = part.replaceAll("[^0-9].*", "");
            return numericPart.isEmpty() ? 0 : Integer.parseInt(numericPart);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Reloads the configuration from disk.
     */
    public void reload() {
        load();
    }

    /**
     * Saves the current configuration to disk.
     */
    public void save() {
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, GSON.toJson(config));
        } catch (IOException e) {
            Logger.severe("Failed to save configuration", e);
        }
    }

    private void saveDefaultConfig() throws IOException {
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, GSON.toJson(createDefaultConfig()));
        Logger.info("Created default configuration file");
    }

    private JsonObject createDefaultConfig() {
        JsonObject root = new JsonObject();

        // Config version for migrations
        root.addProperty("configVersion", CURRENT_CONFIG_VERSION);

        // Storage settings
        JsonObject storage = new JsonObject();
        storage.addProperty("type", "json");

        JsonObject jsonSettings = new JsonObject();
        jsonSettings.addProperty("directory", "data");
        jsonSettings.addProperty("prettyPrint", true);
        storage.add("json", jsonSettings);

        JsonObject sqliteSettings = new JsonObject();
        sqliteSettings.addProperty("file", "hyperperms.db");
        storage.add("sqlite", sqliteSettings);

        JsonObject mysqlSettings = new JsonObject();
        mysqlSettings.addProperty("host", "localhost");
        mysqlSettings.addProperty("port", 3306);
        mysqlSettings.addProperty("database", "hyperperms");
        mysqlSettings.addProperty("username", "root");
        mysqlSettings.addProperty("password", "");
        mysqlSettings.addProperty("poolSize", 10);
        mysqlSettings.addProperty("useSSL", false);
        storage.add("mysql", mysqlSettings);

        root.add("storage", storage);

        // Cache settings
        JsonObject cache = new JsonObject();
        cache.addProperty("enabled", true);
        cache.addProperty("expirySeconds", 300);
        cache.addProperty("maxSize", 10000);
        root.add("cache", cache);

        // Chat settings
        JsonObject chat = new JsonObject();
        chat.addProperty("enabled", true);
        chat.addProperty("format", "%prefix%%player%%suffix%&8: &f%message%");
        chat.addProperty("allowPlayerColors", true);
        chat.addProperty("colorPermission", "hyperperms.chat.color");
        root.add("chat", chat);

        // Backup settings
        JsonObject backup = new JsonObject();
        backup.addProperty("autoBackup", true);
        backup.addProperty("maxBackups", 10);
        backup.addProperty("backupOnSave", false);
        backup.addProperty("intervalSeconds", 3600);
        root.add("backup", backup);

        // Default settings
        JsonObject defaults = new JsonObject();
        defaults.addProperty("group", "default");
        defaults.addProperty("createDefaultGroup", true);
        defaults.addProperty("prefix", "&7");
        defaults.addProperty("suffix", "");
        root.add("defaults", defaults);

        // Task settings
        JsonObject tasks = new JsonObject();
        tasks.addProperty("expiryCheckIntervalSeconds", 60);
        tasks.addProperty("autoSaveIntervalSeconds", 300);
        root.add("tasks", tasks);

        // Verbose settings
        JsonObject verbose = new JsonObject();
        verbose.addProperty("enabledByDefault", false);
        verbose.addProperty("logToConsole", true);
        root.add("verbose", verbose);

        // Server settings (for context)
        JsonObject server = new JsonObject();
        server.addProperty("name", "");
        root.add("server", server);

        // Web editor settings
        JsonObject webEditor = new JsonObject();
        webEditor.addProperty("url", "https://www.hyperperms.com");
        webEditor.addProperty("apiUrl", "");  // Empty = use main URL for backward compatibility
        webEditor.addProperty("timeoutSeconds", 10);
        webEditor.addProperty("websocketEnabled", true);
        webEditor.addProperty("websocketReconnectMaxAttempts", 10);
        webEditor.addProperty("websocketReconnectMaxDelaySeconds", 30);
        webEditor.addProperty("websocketPingTimeoutSeconds", 90);
        root.add("webEditor", webEditor);

        // Tab list settings
        JsonObject tabList = new JsonObject();
        tabList.addProperty("enabled", true);
        tabList.addProperty("format", "%prefix%%player%");
        tabList.addProperty("sortByWeight", true);
        tabList.addProperty("updateIntervalTicks", 20);
        root.add("tabList", tabList);

        // Faction integration settings (HyFactions)
        JsonObject factions = new JsonObject();
        factions.addProperty("enabled", true);
        factions.addProperty("noFactionDefault", "");
        factions.addProperty("noRankDefault", "");
        factions.addProperty("format", "%s");
        factions.addProperty("prefixEnabled", true);
        factions.addProperty("prefixFormat", "&7[&b%s&7] ");
        factions.addProperty("showRank", false);
        factions.addProperty("prefixWithRankFormat", "&7[&b%s&7|&e%r&7] ");
        root.add("factions", factions);

        // VaultUnlocked integration settings
        JsonObject vault = new JsonObject();
        vault.addProperty("enabled", true);
        root.add("vault", vault);

        // Console settings
        JsonObject console = new JsonObject();
        console.addProperty("clickableLinks", true);
        console.addProperty("forceOsc8", false);
        root.add("console", console);

        // Templates settings
        JsonObject templates = new JsonObject();
        templates.addProperty("customDirectory", "templates");
        root.add("templates", templates);

        // Analytics settings
        JsonObject analytics = new JsonObject();
        analytics.addProperty("enabled", false);
        analytics.addProperty("trackChecks", true);
        analytics.addProperty("trackChanges", true);
        analytics.addProperty("flushIntervalSeconds", 60);
        analytics.addProperty("retentionDays", 90);
        root.add("analytics", analytics);

        // PlaceholderAPI integration settings
        JsonObject placeholderapi = new JsonObject();
        placeholderapi.addProperty("enabled", true);
        placeholderapi.addProperty("parseExternal", true);
        root.add("placeholderapi", placeholderapi);

        // MysticNameTags integration settings
        JsonObject mysticnametags = new JsonObject();
        mysticnametags.addProperty("enabled", true);
        mysticnametags.addProperty("refreshOnPermissionChange", true);
        mysticnametags.addProperty("refreshOnGroupChange", true);
        mysticnametags.addProperty("tagPermissionPrefix", "mysticnametags.tag.");
        root.add("mysticnametags", mysticnametags);

        return root;
    }

    // ==================== Getters ====================

    @NotNull
    public String getStorageType() {
        return getNestedString("storage", "type", "json");
    }

    @NotNull
    public String getJsonDirectory() {
        return getNestedString("storage", "json", "directory", "data");
    }

    @NotNull
    public String getSqliteFile() {
        return getNestedString("storage", "sqlite", "file", "hyperperms.db");
    }

    @NotNull
    public String getMysqlHost() {
        return getNestedString("storage", "mysql", "host", "localhost");
    }

    public int getMysqlPort() {
        return getNestedInt("storage", "mysql", "port", 3306);
    }

    @NotNull
    public String getMysqlDatabase() {
        return getNestedString("storage", "mysql", "database", "hyperperms");
    }

    @NotNull
    public String getMysqlUsername() {
        return getNestedString("storage", "mysql", "username", "root");
    }

    @NotNull
    public String getMysqlPassword() {
        return getNestedString("storage", "mysql", "password", "");
    }

    public int getMysqlPoolSize() {
        return getNestedInt("storage", "mysql", "poolSize", 10);
    }

    public boolean getMysqlUseSSL() {
        return getNestedBoolean("storage", "mysql", "useSSL", false);
    }

    public boolean isCacheEnabled() {
        return getNestedBoolean("cache", "enabled", true);
    }

    public int getCacheExpirySeconds() {
        return getNestedInt("cache", "expirySeconds", 300);
    }

    public int getCacheMaxSize() {
        return getNestedInt("cache", "maxSize", 10000);
    }

    @NotNull
    public String getDefaultGroup() {
        return getNestedString("defaults", "group", "default");
    }

    public boolean shouldCreateDefaultGroup() {
        return getNestedBoolean("defaults", "createDefaultGroup", true);
    }

    /**
     * Gets the default prefix for users without a group prefix.
     *
     * @return the default prefix
     */
    @NotNull
    public String getDefaultPrefix() {
        return getNestedString("defaults", "prefix", "&7");
    }

    /**
     * Gets the default suffix for users without a group suffix.
     *
     * @return the default suffix
     */
    @NotNull
    public String getDefaultSuffix() {
        return getNestedString("defaults", "suffix", "");
    }

    // ==================== Chat Settings ====================

    /**
     * Checks if chat formatting is enabled.
     *
     * @return true if chat formatting is enabled
     */
    public boolean isChatEnabled() {
        return getNestedBoolean("chat", "enabled", false);
    }

    /**
     * Gets the chat format string.
     * Supports placeholders: %prefix%, %player%, %suffix%, %message%, %group%, etc.
     *
     * @return the chat format string
     */
    @NotNull
    public String getChatFormat() {
        return getNestedString("chat", "format", "%prefix%%player%%suffix%&8: &f%message%");
    }

    /**
     * Checks if players can use color codes in their messages.
     *
     * @return true if player colors are allowed
     */
    public boolean isAllowPlayerColors() {
        return getNestedBoolean("chat", "allowPlayerColors", true);
    }

    /**
     * Gets the permission required for players to use colors in chat.
     *
     * @return the color permission node
     */
    @NotNull
    public String getColorPermission() {
        return getNestedString("chat", "colorPermission", "hyperperms.chat.color");
    }

    // ==================== Backup Settings ====================

    /**
     * Checks if automatic backups are enabled.
     *
     * @return true if auto-backup is enabled
     */
    public boolean isAutoBackupEnabled() {
        return getNestedBoolean("backup", "autoBackup", true);
    }

    /**
     * Gets the maximum number of backups to keep.
     *
     * @return the maximum backup count
     */
    public int getMaxBackups() {
        return getNestedInt("backup", "maxBackups", 10);
    }

    /**
     * Checks if backups should be created on every save.
     *
     * @return true if backup-on-save is enabled
     */
    public boolean isBackupOnSave() {
        return getNestedBoolean("backup", "backupOnSave", false);
    }

    /**
     * Gets the interval in seconds between automatic backups.
     *
     * @return the auto-backup interval in seconds
     */
    public int getAutoBackupIntervalSeconds() {
        return getNestedInt("backup", "intervalSeconds", 3600);
    }

    // ==================== Task Settings ====================

    public int getExpiryCheckInterval() {
        return getNestedInt("tasks", "expiryCheckIntervalSeconds", 60);
    }

    public int getAutoSaveInterval() {
        return getNestedInt("tasks", "autoSaveIntervalSeconds", 300);
    }

    public boolean isVerboseEnabledByDefault() {
        return getNestedBoolean("verbose", "enabledByDefault", false);
    }

    public boolean shouldLogVerboseToConsole() {
        return getNestedBoolean("verbose", "logToConsole", true);
    }

    /**
     * Gets the server name for context-based permissions.
     *
     * @return the server name, or empty string if not configured
     */
    @NotNull
    public String getServerName() {
        return getNestedString("server", "name", "");
    }


    // ==================== Web Editor Settings ====================

    /**
     * Gets the web editor URL for remote permission management.
     * This is used for browser URLs (where users access the editor).
     *
     * @return the web editor URL
     */
    @NotNull
    public String getWebEditorUrl() {
        return getNestedString("webEditor", "url", "https://www.hyperperms.com");
    }

    /**
     * Gets the API URL for web editor API calls.
     * If not configured, falls back to the main web editor URL for backward compatibility.
     * This allows using a separate API endpoint (e.g., Cloudflare Workers) for API calls
     * while keeping the main URL for browser access.
     *
     * @return the API URL
     */
    @NotNull
    public String getWebEditorApiUrl() {
        String apiUrl = getNestedString("webEditor", "apiUrl", "");
        // If apiUrl is empty, fall back to main URL for backward compatibility
        return apiUrl.isEmpty() ? getWebEditorUrl() : apiUrl;
    }

    /**
     * Gets the HTTP timeout in seconds for web editor API calls.
     *
     * @return the timeout in seconds
     */
    public int getWebEditorTimeoutSeconds() {
        return getNestedInt("webEditor", "timeoutSeconds", 10);
    }

    /**
     * Checks if WebSocket realtime sync is enabled.
     *
     * @return true if WebSocket is enabled
     */
    public boolean isWebEditorWebsocketEnabled() {
        return getNestedBoolean("webEditor", "websocketEnabled", true);
    }

    /**
     * Gets the maximum number of WebSocket reconnect attempts.
     *
     * @return the max reconnect attempts
     */
    public int getWebEditorWebsocketReconnectMaxAttempts() {
        return getNestedInt("webEditor", "websocketReconnectMaxAttempts", 10);
    }

    /**
     * Gets the maximum delay in seconds between WebSocket reconnect attempts.
     *
     * @return the max reconnect delay in seconds
     */
    public int getWebEditorWebsocketReconnectMaxDelaySeconds() {
        return getNestedInt("webEditor", "websocketReconnectMaxDelaySeconds", 30);
    }

    /**
     * Gets the WebSocket ping timeout in seconds.
     * If no ping is received within this period, the connection is considered dead.
     *
     * @return the ping timeout in seconds
     */
    public int getWebEditorWebsocketPingTimeoutSeconds() {
        return getNestedInt("webEditor", "websocketPingTimeoutSeconds", 90);
    }

    // ==================== Faction Integration Settings ====================

    /**
     * Checks if HyFactions integration is enabled.
     *
     * @return true if faction integration is enabled
     */
    public boolean isFactionIntegrationEnabled() {
        return getNestedBoolean("factions", "enabled", true);
    }

    /**
     * Gets the default text to display when a player has no faction.
     *
     * @return the no-faction default text (empty string shows nothing)
     */
    public String getFactionNoFactionDefault() {
        return getNestedString("factions", "noFactionDefault", "");
    }

    /**
     * Gets the default text to display when a player has no rank.
     *
     * @return the no-rank default text
     */
    public String getFactionNoRankDefault() {
        return getNestedString("factions", "noRankDefault", "");
    }

    /**
     * Gets the format string for faction name display.
     * Use %s as placeholder for the faction name.
     * Example: "[%s] " would display as "[FactionName] "
     *
     * @return the faction format string
     */
    public String getFactionFormat() {
        return getNestedString("factions", "format", "%s");
    }

    /**
     * Checks if faction prefix should be automatically added to chat.
     * When enabled, faction name is prepended to the player's prefix.
     *
     * @return true if automatic faction prefix is enabled
     */
    public boolean isFactionPrefixEnabled() {
        return getNestedBoolean("factions", "prefixEnabled", true);
    }

    /**
     * Gets the format string for the faction prefix in chat.
     * Use %s for faction name and %r for rank (Owner, Officer, Member).
     * Example: "&7[&b%s&7] " shows as "[FactionName] "
     * Example: "&7[&b%s&7|&e%r&7] " shows as "[FactionName|Owner] "
     *
     * @return the faction prefix format string
     */
    public String getFactionPrefixFormat() {
        return getNestedString("factions", "prefixFormat", "&7[&b%s&7] ");
    }

    /**
     * Checks if the player's rank should be shown in the faction prefix.
     *
     * @return true if rank should be shown
     */
    public boolean isFactionShowRank() {
        return getNestedBoolean("factions", "showRank", false);
    }

    /**
     * Gets the format when both faction name and rank are shown.
     * Use %s for faction name and %r for rank.
     * Example: "&7[&b%s&7|&e%r&7] " shows as "[Warriors|Owner] "
     *
     * @return the faction prefix format with rank
     */
    public String getFactionPrefixWithRankFormat() {
        return getNestedString("factions", "prefixWithRankFormat", "&7[&b%s&7|&e%r&7] ");
    }

    // ==================== WerChat Integration Settings ====================

    /**
     * Checks if WerChat integration is enabled.
     *
     * @return true if WerChat integration is enabled
     */
    public boolean isWerChatIntegrationEnabled() {
        return getNestedBoolean("werchat", "enabled", true);
    }

    /**
     * Gets the default text to display when a player has no channel.
     *
     * @return the no-channel default text (empty string shows nothing)
     */
    public String getWerChatNoChannelDefault() {
        return getNestedString("werchat", "noChannelDefault", "");
    }

    /**
     * Gets the format string for channel name display.
     * Use %s as placeholder for the channel name.
     * Example: "[%s] " would display as "[ChannelName] "
     *
     * @return the channel format string
     */
    public String getWerChatChannelFormat() {
        return getNestedString("werchat", "channelFormat", "%s");
    }

    // ==================== VaultUnlocked Integration Settings ====================

    /**
     * Checks if VaultUnlocked integration is enabled.
     *
     * @return true if VaultUnlocked integration is enabled
     */
    public boolean isVaultIntegrationEnabled() {
        return getNestedBoolean("vault", "enabled", true);
    }

    // ==================== Update Check Settings ====================

    /**
     * Checks if update checking is enabled.
     *
     * @return true if update checking is enabled
     */
    public boolean isUpdateCheckEnabled() {
        return getNestedBoolean("updates", "enabled", true);
    }

    /**
     * Gets the URL for checking updates.
     *
     * @return the update check URL
     */
    @NotNull
    public String getUpdateCheckUrl() {
        return getNestedString("updates", "checkUrl", "https://api.github.com/repos/HyperSystemsDev/HyperPerms/releases/latest");
    }

    /**
     * Checks if changelog details should be shown in the console on update notification.
     *
     * @return true if changelog should be displayed
     */
    public boolean isUpdateChangelogEnabled() {
        return getNestedBoolean("updates", "showChangelog", true);
    }

    // ==================== Tab List Settings ====================

    /**
     * Checks if tab list formatting is enabled.
     *
     * @return true if tab list formatting is enabled
     */
    public boolean isTabListEnabled() {
        return getNestedBoolean("tabList", "enabled", true);
    }

    /**
     * Gets the tab list format string.
     * Supports placeholders: %prefix%, %player%, %suffix%, %group%, etc.
     *
     * @return the tab list format string
     */
    @NotNull
    public String getTabListFormat() {
        return getNestedString("tabList", "format", "%prefix%%player%");
    }

    /**
     * Checks if tab list should be sorted by group weight.
     *
     * @return true if sorting by weight is enabled
     */
    public boolean isTabListSortByWeight() {
        return getNestedBoolean("tabList", "sortByWeight", true);
    }

    /**
     * Gets the tab list update interval in ticks.
     *
     * @return the update interval in ticks (20 ticks = 1 second)
     */
    public int getTabListUpdateIntervalTicks() {
        return getNestedInt("tabList", "updateIntervalTicks", 20);
    }

    // ==================== Console Settings ====================

    /**
     * Checks if clickable console links are enabled.
     *
     * @return true if clickable links are enabled
     */
    public boolean isConsoleClickableLinksEnabled() {
        return getNestedBoolean("console", "clickableLinks", true);
    }

    /**
     * Checks if OSC 8 should be forced regardless of terminal detection.
     *
     * @return true if OSC 8 should be forced
     */
    public boolean isConsoleForceOsc8() {
        return getNestedBoolean("console", "forceOsc8", false);
    }

    // ==================== Templates Settings ====================

    /**
     * Gets the directory for custom templates.
     *
     * @return the custom templates directory
     */
    @NotNull
    public String getTemplatesCustomDirectory() {
        return getNestedString("templates", "customDirectory", "templates");
    }

    // ==================== Analytics Settings ====================

    /**
     * Checks if analytics tracking is enabled.
     *
     * @return true if analytics is enabled
     */
    public boolean isAnalyticsEnabled() {
        return getNestedBoolean("analytics", "enabled", false);
    }

    /**
     * Checks if permission check tracking is enabled.
     *
     * @return true if check tracking is enabled
     */
    public boolean isAnalyticsTrackChecks() {
        return getNestedBoolean("analytics", "trackChecks", true);
    }

    /**
     * Checks if permission change tracking is enabled.
     *
     * @return true if change tracking is enabled
     */
    public boolean isAnalyticsTrackChanges() {
        return getNestedBoolean("analytics", "trackChanges", true);
    }

    /**
     * Gets the interval in seconds for flushing analytics data to storage.
     *
     * @return the flush interval in seconds
     */
    public int getAnalyticsFlushIntervalSeconds() {
        return getNestedInt("analytics", "flushIntervalSeconds", 60);
    }

    /**
     * Gets the number of days to retain analytics data.
     *
     * @return the retention period in days
     */
    public int getAnalyticsRetentionDays() {
        return getNestedInt("analytics", "retentionDays", 90);
    }

    // ==================== PlaceholderAPI Integration Settings ====================

    /**
     * Checks if PlaceholderAPI integration is enabled.
     * When enabled, HyperPerms will register its own expansion with PlaceholderAPI
     * to expose placeholders like %hyperperms_prefix%, %hyperperms_group%, etc.
     *
     * @return true if PlaceholderAPI integration is enabled
     */
    public boolean isPlaceholderAPIEnabled() {
        return getNestedBoolean("placeholderapi", "enabled", true);
    }

    /**
     * Checks if external PlaceholderAPI placeholder parsing is enabled.
     * When enabled, HyperPerms will parse external PAPI placeholders
     * (from other plugins) in chat format strings.
     *
     * @return true if external placeholder parsing is enabled
     */
    public boolean isPlaceholderAPIParseExternal() {
        return getNestedBoolean("placeholderapi", "parseExternal", true);
    }

    // ==================== MysticNameTags Integration Settings ====================

    /**
     * Checks if MysticNameTags integration is enabled.
     * When enabled, HyperPerms will sync tag caches when permissions change.
     *
     * @return true if MysticNameTags integration is enabled
     */
    public boolean isMysticNameTagsEnabled() {
        return getNestedBoolean("mysticnametags", "enabled", true);
    }

    /**
     * Checks if tag caches should be refreshed when permissions change.
     *
     * @return true if refresh on permission change is enabled
     */
    public boolean isMysticNameTagsRefreshOnPermissionChange() {
        return getNestedBoolean("mysticnametags", "refreshOnPermissionChange", true);
    }

    /**
     * Checks if tag caches should be refreshed when group membership changes.
     *
     * @return true if refresh on group change is enabled
     */
    public boolean isMysticNameTagsRefreshOnGroupChange() {
        return getNestedBoolean("mysticnametags", "refreshOnGroupChange", true);
    }

    /**
     * Gets the permission prefix used to identify tag permissions.
     * Tags in MysticNameTags typically use permissions like "mysticnametags.tag.vip".
     *
     * @return the tag permission prefix
     */
    @NotNull
    public String getMysticNameTagsPermissionPrefix() {
        return getNestedString("mysticnametags", "tagPermissionPrefix", "mysticnametags.tag.");
    }

    // ==================== Helper Methods ====================

    private String getNestedString(String... path) {
        JsonObject current = config;
        for (int i = 0; i < path.length - 2; i++) {
            if (current.has(path[i]) && current.get(path[i]).isJsonObject()) {
                current = current.getAsJsonObject(path[i]);
            } else {
                return path[path.length - 1];
            }
        }
        String key = path[path.length - 2];
        String defaultValue = path[path.length - 1];
        if (current.has(key) && current.get(key).isJsonPrimitive()) {
            return current.get(key).getAsString();
        }
        return defaultValue;
    }

    private int getNestedInt(String section, String key, int defaultValue) {
        if (config.has(section) && config.get(section).isJsonObject()) {
            JsonObject obj = config.getAsJsonObject(section);
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                return obj.get(key).getAsInt();
            }
        }
        return defaultValue;
    }

    private int getNestedInt(String section, String subsection, String key, int defaultValue) {
        if (config.has(section) && config.get(section).isJsonObject()) {
            JsonObject obj = config.getAsJsonObject(section);
            if (obj.has(subsection) && obj.get(subsection).isJsonObject()) {
                JsonObject sub = obj.getAsJsonObject(subsection);
                if (sub.has(key) && sub.get(key).isJsonPrimitive()) {
                    return sub.get(key).getAsInt();
                }
            }
        }
        return defaultValue;
    }

    private boolean getNestedBoolean(String section, String key, boolean defaultValue) {
        if (config.has(section) && config.get(section).isJsonObject()) {
            JsonObject obj = config.getAsJsonObject(section);
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                return obj.get(key).getAsBoolean();
            }
        }
        return defaultValue;
    }

    private boolean getNestedBoolean(String section, String subsection, String key, boolean defaultValue) {
        if (config.has(section) && config.get(section).isJsonObject()) {
            JsonObject obj = config.getAsJsonObject(section);
            if (obj.has(subsection) && obj.get(subsection).isJsonObject()) {
                JsonObject sub = obj.getAsJsonObject(subsection);
                if (sub.has(key) && sub.get(key).isJsonPrimitive()) {
                    return sub.get(key).getAsBoolean();
                }
            }
        }
        return defaultValue;
    }

    private String getNestedString(String section, String key, String defaultValue) {
        if (config.has(section) && config.get(section).isJsonObject()) {
            JsonObject obj = config.getAsJsonObject(section);
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                return obj.get(key).getAsString();
            }
        }
        return defaultValue;
    }

    private String getNestedString(String section, String subsection, String key, String defaultValue) {
        if (config.has(section) && config.get(section).isJsonObject()) {
            JsonObject obj = config.getAsJsonObject(section);
            if (obj.has(subsection) && obj.get(subsection).isJsonObject()) {
                JsonObject sub = obj.getAsJsonObject(subsection);
                if (sub.has(key) && sub.get(key).isJsonPrimitive()) {
                    return sub.get(key).getAsString();
                }
            }
        }
        return defaultValue;
    }
}
