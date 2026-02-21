package com.hyperperms.storage.mariadb;

import com.hyperperms.model.Group;
import com.hyperperms.model.Node;
import com.hyperperms.model.Track;
import com.hyperperms.model.User;
import com.hyperperms.storage.StorageProvider;
import com.hyperperms.util.Logger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.hyperperms.api.context.Context;
import com.hyperperms.api.context.ContextSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
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
    private final Path backupsDirectory;

    private HikariDataSource dataSource;
    private volatile boolean healthy = false;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
        this.backupsDirectory = dataDirectory.resolve("backups");
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
                config.setConnectionTimeout(30000);
                config.setIdleTimeout(600000);
                config.setMaxLifetime(1800000);
                config.setMinimumIdle(Math.max(1, maxPoolSize / 2));

                dataSource = new HikariDataSource(config);

                // Create directories
                Files.createDirectories(backupsDirectory);

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

    // ==================== User Operations ====================

    @Override
    public CompletableFuture<Optional<User>> loadUser(@NotNull UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT * FROM users WHERE uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, uuid.toString());
                    ResultSet rs = stmt.executeQuery();

                    if (!rs.next()) {
                        return Optional.<User>empty();
                    }

                    String username = rs.getString("username");
                    User user = new User(uuid, username);
                    user.setPrimaryGroup(rs.getString("primary_group"));
                    user.setCustomPrefix(rs.getString("custom_prefix"));
                    user.setCustomSuffix(rs.getString("custom_suffix"));

                    // Load nodes using the same connection
                    loadUserNodes(conn, user);

                    return Optional.of(user);
                }
            } catch (SQLException e) {
                Logger.severe("Failed to load user: " + uuid, e);
                return Optional.<User>empty();
            }
        });
    }

    private void loadUserNodes(Connection conn, User user) throws SQLException {
        String sql = "SELECT * FROM user_nodes WHERE user_uuid = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.getUuid().toString());
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String permission = rs.getString("permission");
                boolean value = rs.getInt("value") == 1;
                Long expiryMs = rs.getObject("expiry") != null ? rs.getLong("expiry") : null;
                Instant expiry = expiryMs != null ? Instant.ofEpochMilli(expiryMs) : null;
                ContextSet contexts = deserializeContexts(rs.getString("contexts_json"));

                Node node = Node.builder(permission)
                    .value(value)
                    .expiry(expiry)
                    .contexts(contexts)
                    .build();
                user.addNode(node);
            }
        }
    }

    @Override
    public CompletableFuture<Void> saveUser(@NotNull User user) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Upsert user
                    String sql = """
                        INSERT INTO users (uuid, username, primary_group, custom_prefix, custom_suffix)
                        VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            username = VALUES(username),
                            primary_group = VALUES(primary_group),
                            custom_prefix = VALUES(custom_prefix),
                            custom_suffix = VALUES(custom_suffix)
                    """;
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, user.getUuid().toString());
                        stmt.setString(2, user.getUsername());
                        stmt.setString(3, user.getPrimaryGroup());
                        stmt.setString(4, user.getCustomPrefix());
                        stmt.setString(5, user.getCustomSuffix());
                        stmt.executeUpdate();
                    }

                    // Clear existing nodes
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM user_nodes WHERE user_uuid = ?")) {
                        stmt.setString(1, user.getUuid().toString());
                        stmt.executeUpdate();
                    }

                    // Insert nodes
                    String nodeSql = """
                        INSERT INTO user_nodes (user_uuid, permission, value, expiry, contexts_json)
                        VALUES (?, ?, ?, ?, ?)
                    """;
                    try (PreparedStatement stmt = conn.prepareStatement(nodeSql)) {
                        for (Node node : user.getNodes()) {
                            stmt.setString(1, user.getUuid().toString());
                            stmt.setString(2, node.getPermission());
                            stmt.setInt(3, node.getValue() ? 1 : 0);
                            stmt.setObject(4, node.getExpiry() != null ? node.getExpiry().toEpochMilli() : null);
                            stmt.setString(5, serializeContexts(node.getContexts()));
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }

                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                Logger.severe("Failed to save user: " + user.getUuid(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteUser(@NotNull UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM users WHERE uuid = ?")) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                Logger.severe("Failed to delete user: " + uuid, e);
            }
        });
    }

    @Override
    public CompletableFuture<Map<UUID, User>> loadAllUsers() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, User> users = new HashMap<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT * FROM users";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String username = rs.getString("username");
                        User user = new User(uuid, username);
                        user.setPrimaryGroup(rs.getString("primary_group"));
                        user.setCustomPrefix(rs.getString("custom_prefix"));
                        user.setCustomSuffix(rs.getString("custom_suffix"));
                        loadUserNodes(conn, user);
                        users.put(uuid, user);
                    }
                }
            } catch (SQLException e) {
                Logger.severe("Failed to load all users", e);
            }
            return users;
        });
    }

    @Override
    public CompletableFuture<Set<UUID>> getUserUuids() {
        return CompletableFuture.supplyAsync(() -> {
            Set<UUID> uuids = new HashSet<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT uuid FROM users";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        uuids.add(UUID.fromString(rs.getString("uuid")));
                    }
                }
            } catch (SQLException e) {
                Logger.severe("Failed to get user UUIDs", e);
            }
            return uuids;
        });
    }

    @Override
    public CompletableFuture<Optional<UUID>> lookupUuid(@NotNull String username) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT uuid FROM users WHERE LOWER(username) = LOWER(?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, username);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        return Optional.of(UUID.fromString(rs.getString("uuid")));
                    }
                }
            } catch (SQLException e) {
                Logger.severe("Failed to lookup UUID for: " + username, e);
            }
            return Optional.empty();
        });
    }

    // ==================== Group Operations ====================

    @Override
    public CompletableFuture<Optional<Group>> loadGroup(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT * FROM `groups` WHERE name = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, name.toLowerCase());
                    ResultSet rs = stmt.executeQuery();

                    if (!rs.next()) {
                        return Optional.<Group>empty();
                    }

                    Group group = new Group(rs.getString("name"), rs.getInt("weight"));
                    group.setDisplayName(rs.getString("display_name"));
                    group.setPrefix(rs.getString("prefix"));
                    group.setSuffix(rs.getString("suffix"));
                    group.setPrefixPriority(rs.getInt("prefix_priority"));
                    group.setSuffixPriority(rs.getInt("suffix_priority"));

                    // Load nodes using the same connection
                    loadGroupNodes(conn, group);

                    return Optional.of(group);
                }
            } catch (SQLException e) {
                Logger.severe("Failed to load group: " + name, e);
                return Optional.<Group>empty();
            }
        });
    }

    private void loadGroupNodes(Connection conn, Group group) throws SQLException {
        String sql = "SELECT * FROM group_nodes WHERE group_name = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, group.getName());
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String permission = rs.getString("permission");
                boolean value = rs.getInt("value") == 1;
                Long expiryMs = rs.getObject("expiry") != null ? rs.getLong("expiry") : null;
                Instant expiry = expiryMs != null ? Instant.ofEpochMilli(expiryMs) : null;
                ContextSet contexts = deserializeContexts(rs.getString("contexts_json"));

                Node node = Node.builder(permission)
                    .value(value)
                    .expiry(expiry)
                    .contexts(contexts)
                    .build();
                group.addNode(node);
            }
        }
    }

    @Override
    public CompletableFuture<Void> saveGroup(@NotNull Group group) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Upsert group
                    String sql = """
                        INSERT INTO `groups` (name, display_name, weight, prefix, suffix, prefix_priority, suffix_priority)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            display_name = VALUES(display_name),
                            weight = VALUES(weight),
                            prefix = VALUES(prefix),
                            suffix = VALUES(suffix),
                            prefix_priority = VALUES(prefix_priority),
                            suffix_priority = VALUES(suffix_priority)
                    """;
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, group.getName());
                        stmt.setString(2, group.getDisplayName());
                        stmt.setInt(3, group.getWeight());
                        stmt.setString(4, group.getPrefix());
                        stmt.setString(5, group.getSuffix());
                        stmt.setInt(6, group.getPrefixPriority());
                        stmt.setInt(7, group.getSuffixPriority());
                        stmt.executeUpdate();
                    }

                    // Clear existing nodes
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "DELETE FROM group_nodes WHERE group_name = ?")) {
                        stmt.setString(1, group.getName());
                        stmt.executeUpdate();
                    }

                    // Insert nodes
                    String nodeSql = """
                        INSERT INTO group_nodes (group_name, permission, value, expiry, contexts_json)
                        VALUES (?, ?, ?, ?, ?)
                    """;
                    try (PreparedStatement stmt = conn.prepareStatement(nodeSql)) {
                        for (Node node : group.getNodes()) {
                            stmt.setString(1, group.getName());
                            stmt.setString(2, node.getPermission());
                            stmt.setInt(3, node.getValue() ? 1 : 0);
                            stmt.setObject(4, node.getExpiry() != null ? node.getExpiry().toEpochMilli() : null);
                            stmt.setString(5, serializeContexts(node.getContexts()));
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }

                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                Logger.severe("Failed to save group: " + group.getName(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteGroup(@NotNull String name) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM `groups` WHERE name = ?")) {
                stmt.setString(1, name.toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                Logger.severe("Failed to delete group: " + name, e);
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, Group>> loadAllGroups() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Group> groups = new HashMap<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT * FROM `groups`";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        String name = rs.getString("name");
                        Group group = new Group(name, rs.getInt("weight"));
                        group.setDisplayName(rs.getString("display_name"));
                        group.setPrefix(rs.getString("prefix"));
                        group.setSuffix(rs.getString("suffix"));
                        group.setPrefixPriority(rs.getInt("prefix_priority"));
                        group.setSuffixPriority(rs.getInt("suffix_priority"));
                        loadGroupNodes(conn, group);
                        groups.put(name, group);
                    }
                }
            } catch (SQLException e) {
                Logger.severe("Failed to load all groups", e);
            }
            return groups;
        });
    }

    @Override
    public CompletableFuture<Set<String>> getGroupNames() {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> names = new HashSet<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT name FROM `groups`";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        names.add(rs.getString("name"));
                    }
                }
            } catch (SQLException e) {
                Logger.severe("Failed to get group names", e);
            }
            return names;
        });
    }

    // ==================== Track Operations ====================

    @Override
    public CompletableFuture<Optional<Track>> loadTrack(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT * FROM tracks WHERE name = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, name.toLowerCase());
                    ResultSet rs = stmt.executeQuery();

                    if (!rs.next()) {
                        return Optional.<Track>empty();
                    }

                    String groupsJson = rs.getString("groups_json");
                    List<String> groups = parseGroupsList(groupsJson);

                    return Optional.of(new Track(rs.getString("name"), groups));
                }
            } catch (SQLException e) {
                Logger.severe("Failed to load track: " + name, e);
                return Optional.<Track>empty();
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveTrack(@NotNull Track track) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = """
                    INSERT INTO tracks (name, groups_json) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE groups_json = VALUES(groups_json)
                """;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, track.getName());
                    stmt.setString(2, serializeGroupsList(track.getGroups()));
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                Logger.severe("Failed to save track: " + track.getName(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteTrack(@NotNull String name) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM tracks WHERE name = ?")) {
                stmt.setString(1, name.toLowerCase());
                stmt.executeUpdate();
            } catch (SQLException e) {
                Logger.severe("Failed to delete track: " + name, e);
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, Track>> loadAllTracks() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Track> tracks = new HashMap<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT * FROM tracks";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        String trackName = rs.getString("name");
                        String groupsJson = rs.getString("groups_json");
                        List<String> groups = parseGroupsList(groupsJson);
                        tracks.put(trackName, new Track(trackName, groups));
                    }
                }
            } catch (SQLException e) {
                Logger.severe("Failed to load all tracks", e);
            }
            return tracks;
        });
    }

    @Override
    public CompletableFuture<Set<String>> getTrackNames() {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> names = new HashSet<>();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT name FROM tracks";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        names.add(rs.getString("name"));
                    }
                }
            } catch (SQLException e) {
                Logger.severe("Failed to get track names", e);
            }
            return names;
        });
    }

    // ==================== Backup Operations ====================

    @Override
    public CompletableFuture<String> createBackup(@Nullable String name) {
        return CompletableFuture.supplyAsync(() -> {
            String backupName = name != null ? name :
                "backup-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));

            Path backupDir = backupsDirectory.resolve(backupName);

            try {
                Files.createDirectories(backupDir);

                exportUsersToJson(backupDir);
                exportGroupsToJson(backupDir);
                exportTracksToJson(backupDir);

                Logger.info("MariaDB backup created: " + backupName);
                return backupName;

            } catch (Exception e) {
                Logger.severe("Failed to create MariaDB backup: " + backupName, e);
                throw new RuntimeException("Backup failed", e);
            }
        });
    }

    private void exportUsersToJson(Path backupDir) throws SQLException, IOException {
        Path usersDir = backupDir.resolve("users");
        Files.createDirectories(usersDir);

        try (Connection conn = dataSource.getConnection()) {
            String userSql = "SELECT * FROM users";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(userSql)) {

                while (rs.next()) {
                    String uuid = rs.getString("uuid");
                    JsonObject obj = new JsonObject();
                    obj.addProperty("uuid", uuid);
                    obj.addProperty("username", rs.getString("username"));
                    obj.addProperty("primaryGroup", rs.getString("primary_group"));
                    obj.addProperty("customPrefix", rs.getString("custom_prefix"));
                    obj.addProperty("customSuffix", rs.getString("custom_suffix"));

                    // Load nodes for this user
                    JsonArray nodesArray = new JsonArray();
                    String nodeSql = "SELECT * FROM user_nodes WHERE user_uuid = ?";
                    try (PreparedStatement nodeStmt = conn.prepareStatement(nodeSql)) {
                        nodeStmt.setString(1, uuid);
                        ResultSet nodeRs = nodeStmt.executeQuery();

                        while (nodeRs.next()) {
                            JsonObject nodeObj = new JsonObject();
                            nodeObj.addProperty("permission", nodeRs.getString("permission"));
                            nodeObj.addProperty("value", nodeRs.getInt("value") == 1);
                            Long expiry = nodeRs.getObject("expiry") != null ? nodeRs.getLong("expiry") : null;
                            if (expiry != null) {
                                nodeObj.addProperty("expiry", expiry);
                            }
                            nodeObj.addProperty("contexts", nodeRs.getString("contexts_json"));
                            nodesArray.add(nodeObj);
                        }
                    }
                    obj.add("nodes", nodesArray);

                    Files.writeString(usersDir.resolve(uuid + ".json"), GSON.toJson(obj));
                }
            }
        }
    }

    private void exportGroupsToJson(Path backupDir) throws SQLException, IOException {
        Path groupsDir = backupDir.resolve("groups");
        Files.createDirectories(groupsDir);

        try (Connection conn = dataSource.getConnection()) {
            String groupSql = "SELECT * FROM `groups`";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(groupSql)) {

                while (rs.next()) {
                    String name = rs.getString("name");
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", name);
                    obj.addProperty("displayName", rs.getString("display_name"));
                    obj.addProperty("weight", rs.getInt("weight"));
                    obj.addProperty("prefix", rs.getString("prefix"));
                    obj.addProperty("suffix", rs.getString("suffix"));
                    obj.addProperty("prefixPriority", rs.getInt("prefix_priority"));
                    obj.addProperty("suffixPriority", rs.getInt("suffix_priority"));

                    // Load nodes for this group
                    JsonArray nodesArray = new JsonArray();
                    String nodeSql = "SELECT * FROM group_nodes WHERE group_name = ?";
                    try (PreparedStatement nodeStmt = conn.prepareStatement(nodeSql)) {
                        nodeStmt.setString(1, name);
                        ResultSet nodeRs = nodeStmt.executeQuery();

                        while (nodeRs.next()) {
                            JsonObject nodeObj = new JsonObject();
                            nodeObj.addProperty("permission", nodeRs.getString("permission"));
                            nodeObj.addProperty("value", nodeRs.getInt("value") == 1);
                            Long expiry = nodeRs.getObject("expiry") != null ? nodeRs.getLong("expiry") : null;
                            if (expiry != null) {
                                nodeObj.addProperty("expiry", expiry);
                            }
                            nodeObj.addProperty("contexts", nodeRs.getString("contexts_json"));
                            nodesArray.add(nodeObj);
                        }
                    }
                    obj.add("nodes", nodesArray);

                    Files.writeString(groupsDir.resolve(name + ".json"), GSON.toJson(obj));
                }
            }
        }
    }

    private void exportTracksToJson(Path backupDir) throws SQLException, IOException {
        Path tracksDir = backupDir.resolve("tracks");
        Files.createDirectories(tracksDir);

        try (Connection conn = dataSource.getConnection()) {
            String trackSql = "SELECT * FROM tracks";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(trackSql)) {

                while (rs.next()) {
                    String name = rs.getString("name");
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name", name);
                    obj.addProperty("groups", rs.getString("groups_json"));

                    Files.writeString(tracksDir.resolve(name + ".json"), GSON.toJson(obj));
                }
            }
        }
    }

    @Override
    public CompletableFuture<Boolean> restoreBackup(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> {
            Path backupDir = backupsDirectory.resolve(name);

            if (!Files.exists(backupDir)) {
                Logger.warn("MariaDB backup not found: " + name);
                return false;
            }

            try {
                // Create safety backup before restore
                createBackup("pre-restore-" + System.currentTimeMillis()).join();

                try (Connection conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    try {
                        // Delete all existing data (FK order)
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute("DELETE FROM user_nodes");
                            stmt.execute("DELETE FROM group_nodes");
                            stmt.execute("DELETE FROM users");
                            stmt.execute("DELETE FROM `groups`");
                            stmt.execute("DELETE FROM tracks");
                        }

                        // Restore groups first (users may reference groups)
                        restoreGroupsFromJson(conn, backupDir);
                        restoreUsersFromJson(conn, backupDir);
                        restoreTracksFromJson(conn, backupDir);

                        conn.commit();
                        Logger.info("MariaDB restored from backup: " + name);
                        return true;

                    } catch (Exception e) {
                        conn.rollback();
                        throw e;
                    } finally {
                        conn.setAutoCommit(true);
                    }
                }

            } catch (Exception e) {
                Logger.severe("Failed to restore MariaDB backup: " + name, e);
                return false;
            }
        });
    }

    private void restoreUsersFromJson(Connection conn, Path backupDir) throws SQLException, IOException {
        Path usersDir = backupDir.resolve("users");
        if (!Files.exists(usersDir)) {
            return;
        }

        String userSql = "INSERT INTO users (uuid, username, primary_group, custom_prefix, custom_suffix) VALUES (?, ?, ?, ?, ?)";
        String nodeSql = "INSERT INTO user_nodes (user_uuid, permission, value, expiry, contexts_json) VALUES (?, ?, ?, ?, ?)";

        List<Path> files;
        try (var stream = Files.list(usersDir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).toList();
        }

        for (Path file : files) {
            String content = Files.readString(file);
            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();

            try (PreparedStatement stmt = conn.prepareStatement(userSql)) {
                stmt.setString(1, obj.get("uuid").getAsString());
                stmt.setString(2, obj.has("username") && !obj.get("username").isJsonNull()
                    ? obj.get("username").getAsString() : null);
                stmt.setString(3, obj.has("primaryGroup") && !obj.get("primaryGroup").isJsonNull()
                    ? obj.get("primaryGroup").getAsString() : "default");
                stmt.setString(4, obj.has("customPrefix") && !obj.get("customPrefix").isJsonNull()
                    ? obj.get("customPrefix").getAsString() : null);
                stmt.setString(5, obj.has("customSuffix") && !obj.get("customSuffix").isJsonNull()
                    ? obj.get("customSuffix").getAsString() : null);
                stmt.executeUpdate();
            }

            if (obj.has("nodes") && obj.get("nodes").isJsonArray()) {
                JsonArray nodes = obj.getAsJsonArray("nodes");
                try (PreparedStatement stmt = conn.prepareStatement(nodeSql)) {
                    for (int i = 0; i < nodes.size(); i++) {
                        JsonObject node = nodes.get(i).getAsJsonObject();
                        stmt.setString(1, obj.get("uuid").getAsString());
                        stmt.setString(2, node.get("permission").getAsString());
                        stmt.setInt(3, node.get("value").getAsBoolean() ? 1 : 0);
                        stmt.setObject(4, node.has("expiry") && !node.get("expiry").isJsonNull()
                            ? node.get("expiry").getAsLong() : null);
                        stmt.setString(5, node.has("contexts") && !node.get("contexts").isJsonNull()
                            ? node.get("contexts").getAsString() : "[]");
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        }
    }

    private void restoreGroupsFromJson(Connection conn, Path backupDir) throws SQLException, IOException {
        Path groupsDir = backupDir.resolve("groups");
        if (!Files.exists(groupsDir)) {
            return;
        }

        String groupSql = "INSERT INTO `groups` (name, display_name, weight, prefix, suffix, prefix_priority, suffix_priority) VALUES (?, ?, ?, ?, ?, ?, ?)";
        String nodeSql = "INSERT INTO group_nodes (group_name, permission, value, expiry, contexts_json) VALUES (?, ?, ?, ?, ?)";

        List<Path> files;
        try (var stream = Files.list(groupsDir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).toList();
        }

        for (Path file : files) {
            String content = Files.readString(file);
            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();

            try (PreparedStatement stmt = conn.prepareStatement(groupSql)) {
                stmt.setString(1, obj.get("name").getAsString());
                stmt.setString(2, obj.has("displayName") && !obj.get("displayName").isJsonNull()
                    ? obj.get("displayName").getAsString() : null);
                stmt.setInt(3, obj.has("weight") && !obj.get("weight").isJsonNull()
                    ? obj.get("weight").getAsInt() : 0);
                stmt.setString(4, obj.has("prefix") && !obj.get("prefix").isJsonNull()
                    ? obj.get("prefix").getAsString() : null);
                stmt.setString(5, obj.has("suffix") && !obj.get("suffix").isJsonNull()
                    ? obj.get("suffix").getAsString() : null);
                stmt.setInt(6, obj.has("prefixPriority") && !obj.get("prefixPriority").isJsonNull()
                    ? obj.get("prefixPriority").getAsInt() : 0);
                stmt.setInt(7, obj.has("suffixPriority") && !obj.get("suffixPriority").isJsonNull()
                    ? obj.get("suffixPriority").getAsInt() : 0);
                stmt.executeUpdate();
            }

            if (obj.has("nodes") && obj.get("nodes").isJsonArray()) {
                JsonArray nodes = obj.getAsJsonArray("nodes");
                try (PreparedStatement stmt = conn.prepareStatement(nodeSql)) {
                    for (int i = 0; i < nodes.size(); i++) {
                        JsonObject node = nodes.get(i).getAsJsonObject();
                        stmt.setString(1, obj.get("name").getAsString());
                        stmt.setString(2, node.get("permission").getAsString());
                        stmt.setInt(3, node.get("value").getAsBoolean() ? 1 : 0);
                        stmt.setObject(4, node.has("expiry") && !node.get("expiry").isJsonNull()
                            ? node.get("expiry").getAsLong() : null);
                        stmt.setString(5, node.has("contexts") && !node.get("contexts").isJsonNull()
                            ? node.get("contexts").getAsString() : "[]");
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        }
    }

    private void restoreTracksFromJson(Connection conn, Path backupDir) throws SQLException, IOException {
        Path tracksDir = backupDir.resolve("tracks");
        if (!Files.exists(tracksDir)) {
            return;
        }

        String trackSql = "INSERT INTO tracks (name, groups_json) VALUES (?, ?)";

        List<Path> files;
        try (var stream = Files.list(tracksDir)) {
            files = stream.filter(p -> p.toString().endsWith(".json")).toList();
        }

        for (Path file : files) {
            String content = Files.readString(file);
            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();

            try (PreparedStatement stmt = conn.prepareStatement(trackSql)) {
                stmt.setString(1, obj.get("name").getAsString());
                stmt.setString(2, obj.has("groups") && !obj.get("groups").isJsonNull()
                    ? obj.get("groups").getAsString() : "[]");
                stmt.executeUpdate();
            }
        }
    }

    @Override
    public CompletableFuture<List<String>> listBackups() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> backups = new ArrayList<>();

            try {
                if (Files.exists(backupsDirectory)) {
                    try (var stream = Files.list(backupsDirectory)) {
                        stream.filter(Files::isDirectory)
                              .map(p -> p.getFileName().toString())
                              .sorted(Comparator.reverseOrder())
                              .forEach(backups::add);
                    }
                }
            } catch (IOException e) {
                Logger.severe("Failed to list MariaDB backups", e);
            }

            return backups;
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteBackup(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> {
            Path backupDir = backupsDirectory.resolve(name);

            if (!Files.exists(backupDir)) {
                return false;
            }

            try (var walk = Files.walk(backupDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            Logger.warn("Failed to delete: " + path);
                        }
                    });
                return true;
            } catch (IOException e) {
                Logger.severe("Failed to delete MariaDB backup: " + name, e);
                return false;
            }
        });
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
