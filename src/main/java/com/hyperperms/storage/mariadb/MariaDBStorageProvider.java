package com.hyperperms.storage.mariadb;

import com.hyperperms.model.Group;
import com.hyperperms.model.Node;
import com.hyperperms.model.Track;
import com.hyperperms.model.User;
import com.hyperperms.storage.StorageProvider;
import com.hyperperms.util.Logger;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.hyperperms.api.context.Context;
import com.hyperperms.api.context.ContextSet;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MariaDB/MySQL-based storage provider using HikariCP connection pooling.
 * <p>
 * This implementation bundles the MariaDB JDBC driver and uses HikariCP for
 * high-performance connection pooling, making it suitable for multi-server
 * deployments sharing a central database.
 * <p>
 * <b>Thread Safety:</b> All database operations obtain connections from the
 * HikariCP pool, which handles concurrent access safely. Unlike the SQLite
 * provider, this implementation supports true multi-threaded access.
 */
public final class MariaDBStorageProvider implements StorageProvider {

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final boolean useSSL;
    private final int maxPoolSize;
    private final Path dataDirectory;

    private HikariDataSource dataSource;
    private volatile boolean healthy = false;

    public MariaDBStorageProvider(@NotNull String host, int port, @NotNull String database,
                                  @NotNull String username, @NotNull String password,
                                  boolean useSSL, int maxPoolSize, @NotNull Path dataDirectory) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.useSSL = useSSL;
        this.maxPoolSize = maxPoolSize;
        this.dataDirectory = dataDirectory;
    }

    @Override
    @NotNull
    public String getName() {
        return "MariaDB";
    }

    @Override
    @NotNull
    public String getType() {
        return "mariadb";
    }

    @Override
    public CompletableFuture<Void> init() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Configure HikariCP connection pool
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database);
                config.setUsername(username);
                config.setPassword(password);
                config.setMaximumPoolSize(maxPoolSize);
                config.setPoolName("HyperPerms-MariaDB");

                // Connection properties
                config.addDataSourceProperty("useSSL", String.valueOf(useSSL));
                config.addDataSourceProperty("characterEncoding", "utf8mb4");
                config.addDataSourceProperty("useUnicode", "true");

                // Pool tuning
                config.setConnectionTimeout(30000);  // 30 seconds
                config.setIdleTimeout(600000);        // 10 minutes
                config.setMaxLifetime(1800000);       // 30 minutes
                config.setMinimumIdle(Math.max(1, maxPoolSize / 2));

                dataSource = new HikariDataSource(config);

                // Create tables
                createTables();

                healthy = true;
                Logger.info("MariaDB storage initialized (pool: " + maxPoolSize + ", host: " + host + ":" + port + "/" + database + ")");

            } catch (Exception e) {
                healthy = false;
                throw new RuntimeException("Failed to initialize MariaDB storage", e);
            }
        });
    }

    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Users table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(16),
                    primary_group VARCHAR(64) NOT NULL DEFAULT 'default',
                    custom_prefix VARCHAR(256),
                    custom_suffix VARCHAR(256)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            // Groups table (backtick-escaped since 'groups' is a reserved word)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS `groups` (
                    name VARCHAR(64) PRIMARY KEY,
                    display_name VARCHAR(128),
                    weight INT NOT NULL DEFAULT 0,
                    prefix VARCHAR(256),
                    suffix VARCHAR(256),
                    prefix_priority INT NOT NULL DEFAULT 0,
                    suffix_priority INT NOT NULL DEFAULT 0
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            // Tracks table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tracks (
                    name VARCHAR(64) PRIMARY KEY,
                    groups_json TEXT NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            // User nodes table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_nodes (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_uuid VARCHAR(36) NOT NULL,
                    permission VARCHAR(256) NOT NULL,
                    value TINYINT NOT NULL DEFAULT 1,
                    expiry BIGINT,
                    contexts_json TEXT NOT NULL,
                    FOREIGN KEY (user_uuid) REFERENCES users(uuid) ON DELETE CASCADE,
                    UNIQUE KEY uk_user_perm_ctx (user_uuid, permission, contexts_json(255))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            // Group nodes table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS group_nodes (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    group_name VARCHAR(64) NOT NULL,
                    permission VARCHAR(256) NOT NULL,
                    value TINYINT NOT NULL DEFAULT 1,
                    expiry BIGINT,
                    contexts_json TEXT NOT NULL,
                    FOREIGN KEY (group_name) REFERENCES `groups`(name) ON DELETE CASCADE,
                    UNIQUE KEY uk_group_perm_ctx (group_name, permission, contexts_json(255))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
        }
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            healthy = false;

            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }

            Logger.info("MariaDB storage shut down");
        });
    }

    @Override
    public CompletableFuture<Void> saveAll() {
        // MariaDB auto-commits each operation, nothing to flush
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    // ==================== User Operations (TODO) ====================

    @Override
    public CompletableFuture<Optional<User>> loadUser(@NotNull UUID uuid) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Void> saveUser(@NotNull User user) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Void> deleteUser(@NotNull UUID uuid) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Map<UUID, User>> loadAllUsers() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Set<UUID>> getUserUuids() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Optional<UUID>> lookupUuid(@NotNull String username) {
        throw new UnsupportedOperationException("TODO");
    }

    // ==================== Group Operations (TODO) ====================

    @Override
    public CompletableFuture<Optional<Group>> loadGroup(@NotNull String name) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Void> saveGroup(@NotNull Group group) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Void> deleteGroup(@NotNull String name) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Map<String, Group>> loadAllGroups() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Set<String>> getGroupNames() {
        throw new UnsupportedOperationException("TODO");
    }

    // ==================== Track Operations (TODO) ====================

    @Override
    public CompletableFuture<Optional<Track>> loadTrack(@NotNull String name) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Void> saveTrack(@NotNull Track track) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Void> deleteTrack(@NotNull String name) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Map<String, Track>> loadAllTracks() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Set<String>> getTrackNames() {
        throw new UnsupportedOperationException("TODO");
    }

    // ==================== Backup Operations (TODO) ====================

    @Override
    public CompletableFuture<String> createBackup(@Nullable String name) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Boolean> restoreBackup(@NotNull String name) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<List<String>> listBackups() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public CompletableFuture<Boolean> deleteBackup(@NotNull String name) {
        throw new UnsupportedOperationException("TODO");
    }

    // ==================== Helper Methods ====================

    private List<String> parseGroupsList(String json) {
        List<String> groups = new ArrayList<>();
        if (json != null && json.startsWith("[") && json.endsWith("]")) {
            String content = json.substring(1, json.length() - 1).trim();
            if (!content.isEmpty()) {
                for (String item : content.split(",")) {
                    String trimmed = item.trim();
                    if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                        groups.add(trimmed.substring(1, trimmed.length() - 1));
                    }
                }
            }
        }
        return groups;
    }

    private String serializeGroupsList(List<String> groups) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(groups.get(i)).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private String serializeContexts(ContextSet contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Context ctx : contexts) {
            if (!first) sb.append(",");
            sb.append("\"").append(ctx.key()).append("=").append(ctx.value()).append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private ContextSet deserializeContexts(String json) {
        if (json == null || json.equals("[]") || json.isBlank()) {
            return ContextSet.empty();
        }
        String content = json.trim();
        if (content.startsWith("[") && content.endsWith("]")) {
            content = content.substring(1, content.length() - 1).trim();
        }
        if (content.isEmpty()) {
            return ContextSet.empty();
        }
        ContextSet.Builder builder = ContextSet.builder();
        for (String item : content.split(",")) {
            String trimmed = item.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            if (trimmed.contains("=")) {
                try {
                    builder.add(Context.parse(trimmed));
                } catch (IllegalArgumentException e) {
                    Logger.warn("Skipping invalid context entry: " + trimmed);
                }
            }
        }
        return builder.build();
    }
}
