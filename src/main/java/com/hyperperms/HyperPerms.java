package com.hyperperms;

import com.hyperperms.api.AsyncPermissionCheckBuilder;
import com.hyperperms.api.HyperPermsAPI;
import com.hyperperms.api.MetricsAPI;
import com.hyperperms.api.PermissionCheckBuilder;
import com.hyperperms.api.QueryAPI;
import com.hyperperms.api.TriState;
import com.hyperperms.api.context.ContextSet;
import com.hyperperms.metrics.MetricsAPIImpl;
import com.hyperperms.query.QueryAPIImpl;
import com.hyperperms.api.events.EventBus;
import com.hyperperms.api.events.PermissionCheckEvent;
import com.hyperperms.cache.CacheInvalidator;
import com.hyperperms.cache.PermissionCache;
import com.hyperperms.resolver.PermissionTrace;
import com.hyperperms.config.HyperPermsConfig;
import com.hyperperms.context.ContextManager;
import com.hyperperms.context.PlayerContextProvider;
import com.hyperperms.context.calculators.BiomeContextCalculator;
import com.hyperperms.context.calculators.GameModeContextCalculator;
import com.hyperperms.context.calculators.RegionContextCalculator;
import com.hyperperms.context.calculators.ServerContextCalculator;
import com.hyperperms.context.calculators.TimeContextCalculator;
import com.hyperperms.context.calculators.WorldContextCalculator;
import com.hyperperms.integration.FactionIntegration;
import com.hyperperms.integration.MysticNameTagsIntegration;
import com.hyperperms.integration.PlaceholderAPIIntegration;
import com.hyperperms.integration.VaultUnlockedIntegration;
import com.hyperperms.integration.WerChatIntegration;
import com.hyperperms.discovery.RuntimePermissionDiscovery;
import com.hyperperms.update.UpdateChecker;
import com.hyperperms.registry.PermissionRegistry;
import com.hyperperms.manager.GroupManagerImpl;
import com.hyperperms.manager.TrackManagerImpl;
import com.hyperperms.manager.UserManagerImpl;
import com.hyperperms.model.User;
import com.hyperperms.resolver.PermissionResolver;
import com.hyperperms.resolver.WildcardMatcher;
import com.hyperperms.storage.StorageFactory;
import com.hyperperms.storage.StorageProvider;
import com.hyperperms.task.ExpiryCleanupTask;
import com.hyperperms.util.Logger;
import com.hyperperms.util.SQLiteDriverLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * Main plugin class for HyperPerms.
 * <p>
 * This is the entry point for the plugin. Use {@link #getApi()} to access the API.
 */
public final class HyperPerms implements HyperPermsAPI {

    public static final String VERSION = BuildInfo.VERSION;
    
    private static volatile HyperPerms instance;

    private final Path dataDirectory;
    private final java.util.logging.Logger parentLogger;

    // Core components
    private HyperPermsConfig config;
    private StorageProvider storage;
    private PermissionCache cache;
    private CacheInvalidator cacheInvalidator;
    private PermissionResolver resolver;
    private EventBus eventBus;
    private ContextManager contextManager;
    private PlayerContextProvider playerContextProvider;
    private com.hyperperms.registry.PermissionRegistry permissionRegistry;
    private RuntimePermissionDiscovery runtimeDiscovery;

    // Chat system
    private com.hyperperms.chat.ChatManager chatManager;

    // Tab list system
    private com.hyperperms.tablist.TabListManager tabListManager;
    
    // Faction integration (optional - soft dependency on HyFactions)
    @Nullable
    private FactionIntegration factionIntegration;
    
    // WerChat integration (optional - soft dependency on WerChat)
    @Nullable
    private WerChatIntegration werchatIntegration;

    // PlaceholderAPI integration (optional - soft dependency on PlaceholderAPI)
    @Nullable
    private PlaceholderAPIIntegration placeholderApiIntegration;

    // MysticNameTags integration (optional - soft dependency on MysticNameTags)
    @Nullable
    private MysticNameTagsIntegration mysticNameTagsIntegration;

    // Web editor
    private com.hyperperms.web.WebEditorService webEditorService;

    // Backup system
    private com.hyperperms.backup.BackupManager backupManager;

    // Update checker
    @Nullable
    private UpdateChecker updateChecker;

    // Update notification preferences
    @Nullable
    private com.hyperperms.update.UpdateNotificationPreferences notificationPreferences;

    // Analytics
    @Nullable
    private com.hyperperms.analytics.AnalyticsManager analyticsManager;

    // API implementations
    @Nullable
    private QueryAPI queryApi;
    @Nullable
    private MetricsAPI metricsApi;

    // Managers
    private UserManagerImpl userManager;
    private GroupManagerImpl groupManager;
    private TrackManagerImpl trackManager;

    // Tasks
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> expiryTask;

    // State
    private volatile boolean enabled = false;
    private volatile boolean verboseMode = false;

    /**
     * Creates a new HyperPerms instance.
     *
     * @param dataDirectory the plugin data directory
     * @param parentLogger  the parent logger
     */
    public HyperPerms(@NotNull Path dataDirectory, @NotNull java.util.logging.Logger parentLogger) {
        this.dataDirectory = dataDirectory;
        this.parentLogger = parentLogger;
    }

    /**
     * Gets the API instance.
     *
     * @return the API, or null if not enabled
     */
    @Nullable
    public static HyperPermsAPI getApi() {
        return instance;
    }

    /**
     * Gets the plugin instance.
     *
     * @return the instance, or null if not enabled
     */
    @Nullable
    public static HyperPerms getInstance() {
        return instance;
    }

    /**
     * Enables the plugin.
     */
    public void enable() {
        if (enabled) {
            return;
        }

        long startTime = System.currentTimeMillis();
        instance = this;

        try {
            // Initialize logger
            Logger.init(parentLogger);
            Logger.info("Enabling HyperPerms...");

            // Initialize lib directory for optional SQLite driver
            Path libDir = dataDirectory.resolve("lib");
            try {
                Files.createDirectories(libDir);
            } catch (IOException e) {
                Logger.warn("Failed to create lib directory: %s", e.getMessage());
            }
            SQLiteDriverLoader.setLibDirectory(libDir);
            Logger.debug("SQLite lib directory: %s", libDir);

            // Load configuration
            config = new HyperPermsConfig(dataDirectory);
            config.load();

            // Initialize storage
            storage = StorageFactory.createStorage(config, dataDirectory);
            storage.init().join();

            // Initialize cache
            cache = new PermissionCache(
                    config.getCacheMaxSize(),
                    config.getCacheExpirySeconds(),
                    config.isCacheEnabled()
            );
            cacheInvalidator = new CacheInvalidator(cache);

            // Initialize event bus
            eventBus = new EventBus();

            // Initialize managers with event bus
            groupManager = new GroupManagerImpl(storage, cacheInvalidator, eventBus);
            trackManager = new TrackManagerImpl(storage, eventBus);
            userManager = new UserManagerImpl(storage, cache, eventBus, config.getDefaultGroup());

            // Load data
            groupManager.loadAll().join();
            trackManager.loadAll().join();
            userManager.loadAll().join();

            // Load default groups on first run if no groups exist
            if (groupManager.getLoadedGroups().isEmpty()) {
                loadDefaultGroups();
            }

            // Ensure default group exists (fallback if default-groups.json missing)
            if (config.shouldCreateDefaultGroup()) {
                groupManager.ensureDefaultGroup(config.getDefaultGroup());
            }

            // Initialize resolver
            resolver = new PermissionResolver(groupManager::getGroup);

            // Initialize context system
            contextManager = new ContextManager();
            playerContextProvider = PlayerContextProvider.EMPTY; // Will be set by platform
            registerDefaultContextCalculators();

            // Initialize permission registry
            permissionRegistry = com.hyperperms.registry.PermissionRegistry.getInstance();
            permissionRegistry.registerBuiltInPermissions();

            // Initialize runtime permission discovery
            Logger.info("[Discovery] Initializing runtime permission discovery...");
            // Derive plugins directory from dataDirectory (mods/com.hyperperms_HyperPerms/data -> mods/)
            // Also supports "plugins" folder name for compatibility
            Path pluginsDir = resolvePluginsDirectory(dataDirectory);
            runtimeDiscovery = new RuntimePermissionDiscovery(dataDirectory, pluginsDir);
            runtimeDiscovery.load();
            java.util.Set<String> installedPlugins = runtimeDiscovery.scanInstalledPlugins();
            runtimeDiscovery.buildNamespaceMapping();
            runtimeDiscovery.scanJarPermissions(installedPlugins);
            runtimeDiscovery.pruneRemovedPlugins(installedPlugins);
            permissionRegistry.registerDiscoveredPermissions(runtimeDiscovery);

            // Initialize chat manager
            chatManager = new com.hyperperms.chat.ChatManager(this);
            chatManager.loadConfig();

            // Initialize tab list manager
            tabListManager = new com.hyperperms.tablist.TabListManager(this);
            tabListManager.loadConfig();

            // Initialize faction integration (soft dependency on HyFactions)
            Logger.debugIntegration("Initializing faction integration...");
            factionIntegration = new FactionIntegration(this);
            factionIntegration.setEnabled(config.isFactionIntegrationEnabled());
            factionIntegration.setNoFactionDefault(config.getFactionNoFactionDefault());
            factionIntegration.setNoRankDefault(config.getFactionNoRankDefault());
            factionIntegration.setFactionFormat(config.getFactionFormat());
            factionIntegration.setPrefixEnabled(config.isFactionPrefixEnabled());
            factionIntegration.setPrefixFormat(config.getFactionPrefixFormat());
            factionIntegration.setShowRank(config.isFactionShowRank());
            factionIntegration.setPrefixWithRankFormat(config.getFactionPrefixWithRankFormat());
            chatManager.setFactionIntegration(factionIntegration);
            
            // Initialize WerChat integration (soft dependency on WerChat)
            Logger.debugIntegration("Initializing WerChat integration...");
            werchatIntegration = new WerChatIntegration(this);
            werchatIntegration.setEnabled(config.isWerChatIntegrationEnabled());
            werchatIntegration.setNoChannelDefault(config.getWerChatNoChannelDefault());
            werchatIntegration.setChannelFormat(config.getWerChatChannelFormat());
            chatManager.setWerChatIntegration(werchatIntegration);

            // Initialize PlaceholderAPI integration (soft dependency on PlaceholderAPI)
            Logger.debugIntegration("Initializing PlaceholderAPI integration...");
            placeholderApiIntegration = new PlaceholderAPIIntegration(this);
            placeholderApiIntegration.setEnabled(config.isPlaceholderAPIEnabled());
            placeholderApiIntegration.setParseExternal(config.isPlaceholderAPIParseExternal());
            chatManager.setPlaceholderAPIIntegration(placeholderApiIntegration);
            if (placeholderApiIntegration.isAvailable()) {
                Logger.info("PlaceholderAPI integration enabled - placeholders available");
            }

            // Initialize MysticNameTags integration (soft dependency on MysticNameTags)
            Logger.debugIntegration("Initializing MysticNameTags integration...");
            mysticNameTagsIntegration = new MysticNameTagsIntegration(this);
            mysticNameTagsIntegration.setEnabled(config.isMysticNameTagsEnabled());
            mysticNameTagsIntegration.setRefreshOnPermissionChange(config.isMysticNameTagsRefreshOnPermissionChange());
            mysticNameTagsIntegration.setRefreshOnGroupChange(config.isMysticNameTagsRefreshOnGroupChange());
            mysticNameTagsIntegration.setTagPermissionPrefix(config.getMysticNameTagsPermissionPrefix());
            if (mysticNameTagsIntegration.isAvailable()) {
                Logger.info("MysticNameTags integration enabled - tag permission sync active");
            }

            // Initialize VaultUnlocked integration (soft dependency)
            if (config.isVaultIntegrationEnabled()) {
                Logger.debugIntegration("Initializing VaultUnlocked integration...");
                VaultUnlockedIntegration.init(this);
            } else {
                Logger.debugIntegration("VaultUnlocked integration disabled in config");
            }

            // Initialize web editor service
            webEditorService = new com.hyperperms.web.WebEditorService(this);

            // Initialize backup manager
            backupManager = new com.hyperperms.backup.BackupManager(this);
            backupManager.start();

            // Initialize update checker
            if (config.isUpdateCheckEnabled()) {
                updateChecker = new UpdateChecker(this, VERSION, config.getUpdateCheckUrl());
                // Check for updates asynchronously
                updateChecker.checkForUpdates().thenAccept(info -> {
                    if (info != null) {
                        Logger.info("[Update] A new version is available: v%s (current: v%s)", info.version(), VERSION);
                        if (config.isUpdateChangelogEnabled() && info.changelog() != null && !info.changelog().isEmpty()) {
                            Logger.info("[Update] Changelog: %s", info.changelog());
                        }
                    }
                });
            }

            // Initialize update notification preferences
            notificationPreferences = new com.hyperperms.update.UpdateNotificationPreferences(dataDirectory);
            notificationPreferences.load();

            // Start scheduled tasks
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HyperPerms-Scheduler");
                t.setDaemon(true);
                return t;
            });

            expiryTask = scheduler.scheduleAtFixedRate(
                    new ExpiryCleanupTask(userManager, groupManager, eventBus),
                    config.getExpiryCheckInterval(),
                    config.getExpiryCheckInterval(),
                    TimeUnit.SECONDS
            );

            // Schedule discovery auto-save (every 5 minutes)
            scheduler.scheduleAtFixedRate(
                    () -> {
                        try {
                            runtimeDiscovery.save();
                        } catch (Exception e) {
                            Logger.warn("Failed to auto-save discovered permissions: %s", e.getMessage());
                        }
                    },
                    300, 300, TimeUnit.SECONDS
            );

            // Set verbose mode
            verboseMode = config.isVerboseEnabledByDefault();

            // Initialize console links settings
            com.hyperperms.util.ConsoleLinks.setEnabled(config.isConsoleClickableLinksEnabled());
            com.hyperperms.util.ConsoleLinks.setForceOsc8(config.isConsoleForceOsc8());
            Logger.debug("Console links: enabled=%s, forceOsc8=%s, osc8Supported=%s",
                    config.isConsoleClickableLinksEnabled(),
                    config.isConsoleForceOsc8(),
                    com.hyperperms.util.ConsoleLinks.isOsc8Supported());

            // Initialize analytics manager
            analyticsManager = new com.hyperperms.analytics.AnalyticsManager(this);
            analyticsManager.start();

            // Initialize API implementations
            queryApi = new QueryAPIImpl(userManager, groupManager, () -> trackManager.getLoadedTracks());
            if (analyticsManager.isEnabled()) {
                metricsApi = new MetricsAPIImpl(
                        analyticsManager,
                        () -> cache.getStatistics(),
                        () -> (int) cache.size()  // Safe cast - cache size won't exceed Integer.MAX_VALUE
                );
            }

            enabled = true;
            long elapsed = System.currentTimeMillis() - startTime;
            Logger.info("HyperPerms enabled in %dms", elapsed);

        } catch (Exception e) {
            Logger.severe("Failed to enable HyperPerms", e);
            disable();
            throw new RuntimeException("Failed to enable HyperPerms", e);
        }
    }

    /**
     * Disables the plugin.
     */
    public void disable() {
        if (!enabled && instance == null) {
            return;
        }

        Logger.info("Disabling HyperPerms...");

        // Shutdown VaultUnlocked integration
        VaultUnlockedIntegration.shutdown();

        // Unregister MysticNameTags integration
        if (mysticNameTagsIntegration != null) {
            mysticNameTagsIntegration.unregister();
        }

        // Unregister PlaceholderAPI expansion
        if (placeholderApiIntegration != null) {
            placeholderApiIntegration.unregister();
        }

        // Stop scheduled tasks FIRST to prevent new storage executor submissions
        if (expiryTask != null) {
            expiryTask.cancel(true);
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Save all user data while storage executor is still clean
        if (userManager != null) {
            try {
                userManager.saveAll().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                Logger.warn("Failed to save users on shutdown: %s", e.getMessage());
            }
        }

        // Save discovered permissions
        if (runtimeDiscovery != null) {
            try {
                runtimeDiscovery.save();
            } catch (Exception e) {
                Logger.warn("Failed to save discovered permissions on shutdown");
            }
        }

        // Stop analytics manager
        if (analyticsManager != null) {
            analyticsManager.stop();
        }

        // Stop backup manager
        if (backupManager != null) {
            backupManager.shutdown();
        }

        // Shutdown storage
        if (storage != null) {
            try {
                storage.shutdown().get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                Logger.warn("Failed to shutdown storage cleanly: %s", e.getMessage());
            }
        }

        // Clear event bus
        if (eventBus != null) {
            eventBus.clear();
        }

        enabled = false;
        instance = null;
        Logger.info("HyperPerms disabled");
    }

    /**
     * Reloads the plugin configuration and data.
     */
    public void reload() {
        Logger.info("Reloading HyperPerms...");

        // Reload config
        config.reload();

        // Clear caches
        cache.invalidateAll();

        // Reload data
        groupManager.loadAll().join();
        trackManager.loadAll().join();

        // Update cache settings
        cache.setEnabled(config.isCacheEnabled());

        Logger.info("HyperPerms reloaded");
    }

    // ==================== HyperPermsAPI Implementation ====================

    @Override
    public boolean hasPermission(@NotNull UUID uuid, @NotNull String permission) {
        return hasPermission(uuid, permission, ContextSet.empty());
    }

    @Override
    public boolean hasPermission(@NotNull UUID uuid, @NotNull String permission, @NotNull ContextSet contexts) {
        // Try cache first
        var cachedPerms = cache.get(uuid, contexts);
        if (cachedPerms != null) {
            if (verboseMode) {
                PermissionTrace trace = cachedPerms.checkWithTrace(permission);
                fireCheckEvent(uuid, permission, contexts, trace.result(), trace);
                return trace.result().asBoolean();
            } else {
                WildcardMatcher.TriState result = cachedPerms.check(permission);
                fireCheckEvent(uuid, permission, contexts, result, null);
                return result.asBoolean();
            }
        }

        // Load user from memory, or from storage if not yet loaded
        User user = userManager.getUser(uuid);
        if (user == null) {
            // User not in memory - load from storage synchronously
            // This ensures we get the correct permissions even if called before async load completes
            var loadResult = userManager.loadUser(uuid).join();
            if (loadResult.isPresent()) {
                user = loadResult.get();
            } else {
                user = userManager.getOrCreateUser(uuid);
            }
        }

        var resolved = resolver.resolve(user, contexts);
        cache.put(uuid, contexts, resolved);

        if (verboseMode) {
            PermissionTrace trace = resolved.checkWithTrace(permission);
            fireCheckEvent(uuid, permission, contexts, trace.result(), trace);
            return trace.result().asBoolean();
        } else {
            WildcardMatcher.TriState result = resolved.check(permission);
            fireCheckEvent(uuid, permission, contexts, result, null);
            return result.asBoolean();
        }
    }

    /**
     * Checks a permission and returns the TriState result using the cache.
     * This is used by HyperPermsPermissionSet for efficient permission checks
     * that need the full TriState (TRUE/FALSE/UNDEFINED) rather than just boolean.
     *
     * @param uuid the player UUID
     * @param permission the permission to check
     * @param contexts the contexts to check in
     * @return the TriState result of the permission check
     */
    @NotNull
    public WildcardMatcher.TriState checkPermission(@NotNull UUID uuid, @NotNull String permission, @NotNull ContextSet contexts) {
        // Try cache first
        var cachedPerms = cache.get(uuid, contexts);
        if (cachedPerms != null) {
            return cachedPerms.check(permission);
        }

        // Load user from memory, or from storage if not yet loaded
        User user = userManager.getUser(uuid);
        if (user == null) {
            var loadResult = userManager.loadUser(uuid).join();
            if (loadResult.isPresent()) {
                user = loadResult.get();
            } else {
                user = userManager.getOrCreateUser(uuid);
            }
        }

        var resolved = resolver.resolve(user, contexts);
        cache.put(uuid, contexts, resolved);
        return resolved.check(permission);
    }

    /**
     * Creates a permission check builder for fluent permission checks with contexts.
     * <p>
     * Example usage:
     * <pre>
     * boolean canBuild = HyperPerms.getInstance()
     *     .check(playerUuid)
     *     .permission("build.place")
     *     .inWorld("nether")
     *     .withGamemode("survival")
     *     .result();
     * </pre>
     *
     * @param uuid the player UUID
     * @return a new permission check builder
     */
    @NotNull
    public PermissionCheckBuilder check(@NotNull UUID uuid) {
        return new PermissionCheckBuilder(this, uuid);
    }

    @Override
    @NotNull
    public CompletableFuture<Boolean> hasPermissionAsync(@NotNull UUID uuid, @NotNull String permission,
                                                          @NotNull ContextSet contexts) {
        return CompletableFuture.supplyAsync(() -> hasPermission(uuid, permission, contexts));
    }

    @Override
    @NotNull
    public TriState getPermissionValue(@NotNull UUID uuid, @NotNull String permission,
                                        @NotNull ContextSet contexts) {
        com.hyperperms.resolver.WildcardMatcher.TriState internal = checkPermission(uuid, permission, contexts);
        return TriState.fromInternal(internal);
    }

    @Override
    @NotNull
    public CompletableFuture<TriState> getPermissionValueAsync(@NotNull UUID uuid, @NotNull String permission,
                                                                @NotNull ContextSet contexts) {
        return CompletableFuture.supplyAsync(() -> getPermissionValue(uuid, permission, contexts));
    }

    @Override
    @NotNull
    public AsyncPermissionCheckBuilder checkAsync(@NotNull UUID uuid) {
        return new AsyncPermissionCheckBuilder(this, uuid);
    }

    @Override
    @NotNull
    public java.util.concurrent.Executor getSyncExecutor() {
        // Return the scheduler executor if available, otherwise the common pool
        // In a real implementation, this would be the main thread executor from the platform
        return scheduler != null ? scheduler : java.util.concurrent.ForkJoinPool.commonPool();
    }

    private void fireCheckEvent(UUID uuid, String permission, ContextSet contexts,
                                 com.hyperperms.resolver.WildcardMatcher.TriState result,
                                 PermissionTrace trace) {
        if (verboseMode) {
            if (trace != null) {
                Logger.debug("Permission check: %s has %s = %s (from %s via %s)",
                        uuid, permission, result, trace.getSourceDescription(), trace.matchType());
            } else {
                Logger.debug("Permission check: %s has %s = %s", uuid, permission, result);
            }
        }
        eventBus.fire(new PermissionCheckEvent(uuid, permission, contexts, result, "resolver", trace));
    }

    @Override
    @NotNull
    public UserManager getUserManager() {
        return userManager;
    }

    @Override
    @NotNull
    public GroupManager getGroupManager() {
        return groupManager;
    }

    @Override
    @NotNull
    public TrackManager getTrackManager() {
        return trackManager;
    }

    @Override
    @NotNull
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    @NotNull
    public ContextSet getContexts(@NotNull UUID uuid) {
        return contextManager.getContexts(uuid);
    }

    @Override
    @NotNull
    public Set<String> getResolvedPermissions(@NotNull UUID uuid) {
        User user = userManager.getUser(uuid);
        if (user == null) {
            var loadResult = userManager.loadUser(uuid).join();
            if (loadResult.isPresent()) {
                user = loadResult.get();
            } else {
                user = userManager.getOrCreateUser(uuid);
            }
        }
        ContextSet contexts = contextManager.getContexts(uuid);
        var resolved = resolver.resolve(user, contexts);
        return resolved.getGrantedPermissions();
    }

    @Override
    @NotNull
    public QueryAPI getQuery() {
        if (queryApi == null) {
            throw new IllegalStateException("QueryAPI not initialized - plugin may not be fully enabled");
        }
        return queryApi;
    }

    @Override
    @Nullable
    public MetricsAPI getMetrics() {
        return metricsApi;
    }

    // ==================== Accessors ====================

    /**
     * Gets the configuration.
     *
     * @return the config
     */
    @NotNull
    public HyperPermsConfig getConfig() {
        return config;
    }

    /**
     * Gets the storage provider.
     *
     * @return the storage
     */
    @NotNull
    public StorageProvider getStorage() {
        return storage;
    }

    /**
     * Gets the permission cache.
     *
     * @return the cache
     */
    @NotNull
    public PermissionCache getCache() {
        return cache;
    }

    /**
     * Gets the cache invalidator.
     *
     * @return the cache invalidator
     */
    @NotNull
    public CacheInvalidator getCacheInvalidator() {
        return cacheInvalidator;
    }

    /**
     * Gets the permission resolver.
     *
     * @return the resolver
     */
    @NotNull
    public PermissionResolver getResolver() {
        return resolver;
    }

    /**
     * Checks if verbose mode is enabled.
     *
     * @return true if verbose
     */
    public boolean isVerboseMode() {
        return verboseMode;
    }

    /**
     * Sets verbose mode.
     *
     * @param verbose true to enable
     */
    public void setVerboseMode(boolean verbose) {
        this.verboseMode = verbose;
    }

    /**
     * Checks if the plugin is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Gets the context manager.
     *
     * @return the context manager
     */
    @NotNull
    public ContextManager getContextManager() {
        return contextManager;
    }

    /**
     * Gets the permission registry.
     * <p>
     * The permission registry tracks all registered permissions from HyperPerms
     * and external plugins, with descriptions and categories.
     *
     * @return the permission registry
     */
    @NotNull
    public com.hyperperms.registry.PermissionRegistry getPermissionRegistry() {
        return permissionRegistry;
    }

    /**
     * Gets the runtime permission discovery system.
     * <p>
     * The discovery system captures permissions checked at runtime that are not
     * registered in the built-in registry, persists them to disk, and prunes
     * permissions from plugins that are no longer installed.
     *
     * @return the runtime discovery, or null if not yet initialized
     */
    @Nullable
    public RuntimePermissionDiscovery getRuntimeDiscovery() {
        return runtimeDiscovery;
    }

    /**
     * Gets the player context provider.
     *
     * @return the player context provider
     */
    @NotNull
    public PlayerContextProvider getPlayerContextProvider() {
        return playerContextProvider;
    }

    /**
     * Sets the player context provider.
     * <p>
     * This should be called by the platform adapter to provide
     * player-specific context data like world and game mode.
     *
     * @param provider the player context provider
     */
    public void setPlayerContextProvider(@NotNull PlayerContextProvider provider) {
        this.playerContextProvider = provider;
        // Re-register calculators with new provider
        contextManager.clear();
        registerDefaultContextCalculators();
    }

    /**
     * Registers the default context calculators.
     */
    private void registerDefaultContextCalculators() {
        // World context
        contextManager.registerCalculator(new WorldContextCalculator(playerContextProvider));

        // Game mode context
        contextManager.registerCalculator(new GameModeContextCalculator(playerContextProvider));

        // Time context (day/night/dawn/dusk)
        contextManager.registerCalculator(new com.hyperperms.context.calculators.TimeContextCalculator(playerContextProvider));

        // Biome context
        contextManager.registerCalculator(new com.hyperperms.context.calculators.BiomeContextCalculator(playerContextProvider));

        // Region context
        contextManager.registerCalculator(new com.hyperperms.context.calculators.RegionContextCalculator(playerContextProvider));

        // Server context (only if configured)
        String serverName = config.getServerName();
        if (!serverName.isEmpty()) {
            contextManager.registerCalculator(new ServerContextCalculator(serverName));
        }

        Logger.debug("Registered %d context calculators", contextManager.getCalculatorCount());
    }

    /**
     * Loads default groups from the default-groups.json resource.
     * <p>
     * This is called on first run when no groups exist in storage.
     * It creates a standard group hierarchy: default -> member -> builder -> moderator -> admin -> owner
     */
    private void loadDefaultGroups() {
        Logger.info("No groups found, loading default groups...");
        
        try (var inputStream = getClass().getClassLoader().getResourceAsStream("default-groups.json")) {
            if (inputStream == null) {
                Logger.warn("default-groups.json not found in resources, skipping default group creation");
                return;
            }

            String json = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonObject groups = root.getAsJsonObject("groups");

            if (groups == null) {
                Logger.warn("No 'groups' object found in default-groups.json");
                return;
            }

            int created = 0;
            for (var entry : groups.entrySet()) {
                String groupName = entry.getKey();
                com.google.gson.JsonObject groupData = entry.getValue().getAsJsonObject();

                // Create the group
                com.hyperperms.model.Group group = groupManager.createGroup(groupName);

                // Set weight
                if (groupData.has("weight")) {
                    group.setWeight(groupData.get("weight").getAsInt());
                }

                // Set prefix
                if (groupData.has("prefix")) {
                    group.setPrefix(groupData.get("prefix").getAsString());
                }

                // Set suffix
                if (groupData.has("suffix")) {
                    group.setSuffix(groupData.get("suffix").getAsString());
                }

                // Add permissions
                if (groupData.has("permissions")) {
                    for (var perm : groupData.getAsJsonArray("permissions")) {
                        group.addNode(com.hyperperms.model.Node.builder(perm.getAsString()).build());
                    }
                }

                // Add parent groups (will be resolved after all groups are created)
                if (groupData.has("parents")) {
                    for (var parent : groupData.getAsJsonArray("parents")) {
                        group.addParent(parent.getAsString());
                    }
                }

                // Save the group
                groupManager.saveGroup(group).join();
                created++;
                Logger.debug("Created default group: %s (weight=%d)", groupName, group.getWeight());
            }

            Logger.info("Loaded %d default groups from default-groups.json", created);

        } catch (Exception e) {
            Logger.warn("Failed to load default groups: %s", e.getMessage());
            Logger.debug("Stack trace: ", e);
        }
    }

    /**
     * Gets the chat manager.
     * <p>
     * The chat manager handles prefix/suffix resolution and chat formatting.
     *
     * @return the chat manager, or null if not yet initialized
     */
    @Nullable
    public com.hyperperms.chat.ChatManager getChatManager() {
        return chatManager;
    }

    /**
     * Gets the tab list manager.
     * <p>
     * The tab list manager handles tab list name formatting.
     *
     * @return the tab list manager, or null if not yet initialized
     */
    @Nullable
    public com.hyperperms.tablist.TabListManager getTabListManager() {
        return tabListManager;
    }

    /**
     * Gets the faction integration.
     * <p>
     * The faction integration provides HyFactions support for chat placeholders.
     * Returns null if HyFactions is not installed.
     *
     * @return the faction integration, or null if HyFactions is not available
     */
    @Nullable
    public FactionIntegration getFactionIntegration() {
        return factionIntegration;
    }

    /**
     * Gets the WerChat integration.
     * <p>
     * The WerChat integration provides WerChat support for chat channel placeholders.
     * Returns null if WerChat is not installed.
     *
     * @return the WerChat integration, or null if WerChat is not available
     */
    @Nullable
    public WerChatIntegration getWerChatIntegration() {
        return werchatIntegration;
    }

    /**
     * Gets the PlaceholderAPI integration.
     * <p>
     * The PlaceholderAPI integration provides two-way integration:
     * <ul>
     *   <li>Exposes HyperPerms placeholders to other plugins</li>
     *   <li>Parses external PAPI placeholders in chat format</li>
     * </ul>
     * Returns null if PlaceholderAPI is not installed.
     *
     * @return the PlaceholderAPI integration, or null if not available
     */
    @Nullable
    public PlaceholderAPIIntegration getPlaceholderAPIIntegration() {
        return placeholderApiIntegration;
    }

    /**
     * Gets the MysticNameTags integration.
     * <p>
     * The MysticNameTags integration provides tag cache invalidation when
     * permissions or groups change, ensuring tag availability updates immediately.
     * Returns null if MysticNameTags is not installed.
     *
     * @return the MysticNameTags integration, or null if not available
     */
    @Nullable
    public MysticNameTagsIntegration getMysticNameTagsIntegration() {
        return mysticNameTagsIntegration;
    }

    /**
     * Gets the backup manager.
     * <p>
     * The backup manager handles automatic and manual backups.
     *
     * @return the backup manager, or null if not yet initialized
     */
    @Nullable
    public com.hyperperms.backup.BackupManager getBackupManager() {
        return backupManager;
    }


    /**
     * Gets the web editor service.
     * <p>
     * The web editor service handles communication with the remote web editor.
     *
     * @return the web editor service, or null if not yet initialized
     */
    @Nullable
    public com.hyperperms.web.WebEditorService getWebEditorService() {
        return webEditorService;
    }


    /**
     * Gets the plugin version.
     *
     * @return the current plugin version
     */
    @NotNull
    public String getVersion() {
        return VERSION;
    }

    /**
     * Gets the update checker.
     * <p>
     * The update checker handles checking for and downloading plugin updates.
     *
     * @return the update checker, or null if update checking is disabled
     */
    @Nullable
    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    /**
     * Gets the analytics manager.
     * <p>
     * The analytics manager tracks permission check statistics and audit logs.
     *
     * @return the analytics manager, or null if analytics is disabled
     */
    @Nullable
    public com.hyperperms.analytics.AnalyticsManager getAnalyticsManager() {
        return analyticsManager;
    }

    /**
     * Gets the update notification preferences.
     * <p>
     * The notification preferences track which players want to receive
     * update notifications on join.
     *
     * @return the notification preferences, or null if not yet initialized
     */
    @Nullable
    public com.hyperperms.update.UpdateNotificationPreferences getNotificationPreferences() {
        return notificationPreferences;
    }

    /**
     * Gets the plugin data directory.
     *
     * @return the data directory path
     */
    @NotNull
    public Path getDataDirectory() {
        return dataDirectory;
    }

    /**
     * Resolves the plugins/mods directory from the data directory.
     * Supports both "mods" and "plugins" folder names for compatibility.
     * Logs warnings if the folder structure is unexpected.
     *
     * @param dataDirectory the plugin's data directory
     * @return the resolved plugins directory path
     */
    @NotNull
    private Path resolvePluginsDirectory(@NotNull Path dataDirectory) {
        // Try to derive from dataDirectory structure: mods/com.hyperperms_HyperPerms/data -> mods/
        Path derivedDir = null;
        if (dataDirectory.getParent() != null && dataDirectory.getParent().getParent() != null) {
            derivedDir = dataDirectory.getParent().getParent();
        }

        if (derivedDir != null && java.nio.file.Files.isDirectory(derivedDir)) {
            String dirName = derivedDir.getFileName().toString().toLowerCase();
            if (dirName.equals("mods") || dirName.equals("plugins")) {
                Logger.debug("[Discovery] Using plugins directory: %s", derivedDir.toAbsolutePath());
                return derivedDir;
            } else {
                // Directory exists but has unexpected name
                Logger.warn("[Discovery] Plugin folder has unexpected name '%s'. Expected 'mods' or 'plugins'.", 
                    derivedDir.getFileName().toString());
                Logger.warn("[Discovery] Plugin discovery will still scan '%s', but consider renaming to 'mods' or 'plugins'.",
                    derivedDir.toAbsolutePath());
                return derivedDir;
            }
        }

        // Fallback: try to find mods or plugins in working directory
        Path workingDir = Path.of("").toAbsolutePath();
        Path modsDir = workingDir.resolve("mods");
        Path pluginsDir = workingDir.resolve("plugins");

        if (java.nio.file.Files.isDirectory(modsDir)) {
            Logger.debug("[Discovery] Using mods directory: %s", modsDir);
            return modsDir;
        } else if (java.nio.file.Files.isDirectory(pluginsDir)) {
            Logger.debug("[Discovery] Using plugins directory: %s", pluginsDir);
            return pluginsDir;
        }

        // Neither exists - log warning and return default
        Logger.warn("[Discovery] Could not find 'mods' or 'plugins' directory!");
        Logger.warn("[Discovery] Checked locations:");
        if (derivedDir != null) {
            Logger.warn("[Discovery]   - Derived: %s (does not exist)", derivedDir.toAbsolutePath());
        }
        Logger.warn("[Discovery]   - %s (does not exist)", modsDir);
        Logger.warn("[Discovery]   - %s (does not exist)", pluginsDir);
        Logger.warn("[Discovery] Plugin permission discovery will be limited. Please ensure your plugins are in a 'mods' or 'plugins' folder.");
        
        // Return mods as default even if it doesn't exist
        return modsDir;
    }
}
