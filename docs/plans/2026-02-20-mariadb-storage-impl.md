# MariaDB Storage Backend Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a production-ready MariaDB/MySQL storage backend with HikariCP connection pooling to HyperPerms.

**Architecture:** Standalone `MariaDBStorageProvider` implementing `StorageProvider`, using HikariCP for connection pooling. Bundled in shadow JAR with dependency relocation. Same logical schema as SQLite, adapted for MySQL syntax.

**Tech Stack:** MariaDB JDBC 3.5.1, HikariCP 6.2.1, Java 25, Gradle Shadow plugin

**Design doc:** `docs/plans/2026-02-20-mariadb-storage-design.md`

---

### Task 1: Update Build Dependencies

**Files:**
- Modify: `build.gradle:48-49` (uncomment HikariCP)
- Modify: `build.gradle:56-57` (uncomment MariaDB driver)
- Modify: `build.gradle:136-139` (uncomment shadow relocations)

**Step 1: Activate HikariCP dependency**

Change line 48-49 from:
```groovy
// Database - HikariCP connection pooling (P5 - deferred, for future MariaDB support)
// implementation 'com.zaxxer:HikariCP:6.2.1'
```
to:
```groovy
// Database - HikariCP connection pooling (for MariaDB support)
implementation 'com.zaxxer:HikariCP:6.2.1'
```

**Step 2: Activate MariaDB JDBC driver dependency**

Change lines 56-57 from:
```groovy
// Database - MariaDB JDBC driver (P5 - deferred)
// implementation 'org.mariadb.jdbc:mariadb-java-client:3.5.1'
```
to:
```groovy
// Database - MariaDB JDBC driver (bundled for MariaDB/MySQL storage)
implementation 'org.mariadb.jdbc:mariadb-java-client:3.5.1'
```

**Step 3: Activate shadow JAR relocations**

Change lines 136-139 from:
```groovy
// Future relocations when database support is added:
// relocate 'com.zaxxer.hikari', 'com.hyperperms.lib.hikari'
// relocate 'org.mariadb.jdbc', 'com.hyperperms.lib.mariadb'
// relocate 'io.lettuce', 'com.hyperperms.lib.lettuce'
```
to:
```groovy
// Database support relocations
relocate 'com.zaxxer.hikari', 'com.hyperperms.lib.hikari'
relocate 'org.mariadb.jdbc', 'com.hyperperms.lib.mariadb'
// Future: relocate 'io.lettuce', 'com.hyperperms.lib.lettuce'
```

**Step 4: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL (dependencies resolve, no compile errors)

**Step 5: Commit**

```bash
git add build.gradle
git commit -m "build: activate HikariCP and MariaDB JDBC dependencies"
```

---

### Task 2: Update Configuration

**Files:**
- Modify: `src/main/java/com/hyperperms/config/HyperPermsConfig.java`

**Step 1: Add `getMysqlUseSSL()` getter**

Add after `getMysqlPoolSize()` (around line 394):
```java
public boolean getMysqlUseSSL() {
    return getNestedBoolean("storage", "mysql", "useSSL", false);
}
```

This requires a new 3-arg `getNestedBoolean` overload. Add after the existing 2-arg version (around line 961):
```java
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
```

**Step 2: Add `useSSL` to default config**

In `createDefaultConfig()`, add after `mysqlSettings.addProperty("poolSize", 10);` (line 228):
```java
mysqlSettings.addProperty("useSSL", false);
```

**Step 3: Add config migration for useSSL**

In `migrateConfig()`, add before the config version update block:
```java
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
```

**Step 4: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add src/main/java/com/hyperperms/config/HyperPermsConfig.java
git commit -m "feat: add MySQL useSSL config option with migration"
```

---

### Task 3: Create MariaDBStorageProvider - Core Structure + Init/Shutdown

**Files:**
- Create: `src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java`

**Step 1: Create the package directory and provider class with init/shutdown**

Create `src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java`:

```java
package com.hyperperms.storage.mariadb;

import com.hyperperms.model.Group;
import com.hyperperms.model.Node;
import com.hyperperms.model.Track;
import com.hyperperms.model.User;
import com.hyperperms.storage.StorageProvider;
import com.hyperperms.util.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.hyperperms.api.context.Context;
import com.hyperperms.api.context.ContextSet;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MariaDB/MySQL-based storage provider using HikariCP connection pooling.
 * <p>
 * Unlike the SQLite provider which uses a single connection with a single-threaded
 * executor, this provider uses a connection pool for concurrent database access.
 * Each operation acquires its own connection from the pool.
 * <p>
 * Configuration is read from config.json under storage.mysql:
 * <ul>
 *   <li>host - database server hostname (default: localhost)</li>
 *   <li>port - database server port (default: 3306)</li>
 *   <li>database - database name (default: hyperperms)</li>
 *   <li>username - database username (default: root)</li>
 *   <li>password - database password (default: empty)</li>
 *   <li>useSSL - whether to use SSL connections (default: false)</li>
 *   <li>maxPoolSize - maximum connection pool size (default: 10)</li>
 * </ul>
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

    public MariaDBStorageProvider(
            @NotNull String host,
            int port,
            @NotNull String database,
            @NotNull String username,
            @NotNull String password,
            boolean useSSL,
            int maxPoolSize,
            @NotNull Path dataDirectory
    ) {
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
                // Create backups directory
                Files.createDirectories(backupsDirectory);

                // Configure HikariCP
                HikariConfig hikariConfig = new HikariConfig();
                hikariConfig.setJdbcUrl(String.format("jdbc:mariadb://%s:%d/%s", host, port, database));
                hikariConfig.setUsername(username);
                hikariConfig.setPassword(password);
                hikariConfig.setMaximumPoolSize(maxPoolSize);
                hikariConfig.setMinimumIdle(maxPoolSize);
                hikariConfig.setConnectionTimeout(5000);
                hikariConfig.setMaxLifetime(1800000);
                hikariConfig.setPoolName("HyperPerms-MariaDB");

                // Connection properties
                hikariConfig.addDataSourceProperty("useSSL", String.valueOf(useSSL));
                hikariConfig.addDataSourceProperty("characterEncoding", "utf8mb4");
                hikariConfig.addDataSourceProperty("useUnicode", "true");

                dataSource = new HikariDataSource(hikariConfig);

                // Create tables
                createTables();

                healthy = true;
                Logger.info("MariaDB storage initialized: %s:%d/%s (pool size: %d)",
                        host, port, database, maxPoolSize);

            } catch (Exception e) {
                healthy = false;
                throw new RuntimeException("Failed to initialize MariaDB storage", e);
            }
        });
    }

    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    uuid VARCHAR(36) PRIMARY KEY,
                    username VARCHAR(16),
                    primary_group VARCHAR(64) NOT NULL DEFAULT 'default',
                    custom_prefix VARCHAR(256),
                    custom_suffix VARCHAR(256)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

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

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tracks (
                    name VARCHAR(64) PRIMARY KEY,
                    groups_json TEXT NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

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
        // MariaDB auto-commits, nothing to flush
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    // Placeholder methods - implemented in subsequent tasks

    @Override public CompletableFuture<Optional<User>> loadUser(@NotNull UUID uuid) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Void> saveUser(@NotNull User user) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Void> deleteUser(@NotNull UUID uuid) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Map<UUID, User>> loadAllUsers() { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Set<UUID>> getUserUuids() { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Optional<UUID>> lookupUuid(@NotNull String username) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Optional<Group>> loadGroup(@NotNull String name) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Void> saveGroup(@NotNull Group group) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Void> deleteGroup(@NotNull String name) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Map<String, Group>> loadAllGroups() { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Set<String>> getGroupNames() { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Optional<Track>> loadTrack(@NotNull String name) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Void> saveTrack(@NotNull Track track) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Void> deleteTrack(@NotNull String name) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Map<String, Track>> loadAllTracks() { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Set<String>> getTrackNames() { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<String> createBackup(@Nullable String name) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Boolean> restoreBackup(@NotNull String name) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<java.util.List<String>> listBackups() { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Boolean> deleteBackup(@NotNull String name) { throw new UnsupportedOperationException("TODO"); }
}
```

**Step 2: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java
git commit -m "feat: add MariaDBStorageProvider skeleton with init/shutdown and schema"
```

---

### Task 4: MariaDBStorageProvider - Helper Methods

**Files:**
- Modify: `src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java`

**Step 1: Add helper methods at the bottom of the class (before the closing brace)**

Replace nothing — add these methods to the bottom of MariaDBStorageProvider, above the closing `}`. These are the same serialization helpers used by the SQLite provider:

```java
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
```

**Step 2: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java
git commit -m "feat: add MariaDB serialization helper methods"
```

---

### Task 5: MariaDBStorageProvider - User Operations

**Files:**
- Modify: `src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java`

**Step 1: Replace all user operation placeholder methods**

Replace the user placeholder methods with full implementations:

```java
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
                    return Optional.empty();
                }

                String username = rs.getString("username");
                User user = new User(uuid, username);
                user.setPrimaryGroup(rs.getString("primary_group"));
                user.setCustomPrefix(rs.getString("custom_prefix"));
                user.setCustomSuffix(rs.getString("custom_suffix"));

                loadUserNodes(conn, user);
                return Optional.of(user);
            }
        } catch (SQLException e) {
            Logger.severe("Failed to load user: " + uuid, e);
            return Optional.empty();
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

                // Batch insert nodes
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
            } finally {
                conn.setAutoCommit(true);
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
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT uuid FROM users")) {
            while (rs.next()) {
                uuids.add(UUID.fromString(rs.getString("uuid")));
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
```

**Step 2: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java
git commit -m "feat: implement MariaDB user CRUD operations"
```

---

### Task 6: MariaDBStorageProvider - Group Operations

**Files:**
- Modify: `src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java`

**Step 1: Replace all group operation placeholder methods**

```java
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
                    return Optional.empty();
                }

                Group group = new Group(rs.getString("name"), rs.getInt("weight"));
                group.setDisplayName(rs.getString("display_name"));
                group.setPrefix(rs.getString("prefix"));
                group.setSuffix(rs.getString("suffix"));
                group.setPrefixPriority(rs.getInt("prefix_priority"));
                group.setSuffixPriority(rs.getInt("suffix_priority"));

                loadGroupNodes(conn, group);
                return Optional.of(group);
            }
        } catch (SQLException e) {
            Logger.severe("Failed to load group: " + name, e);
            return Optional.empty();
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

                // Batch insert nodes
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
            } finally {
                conn.setAutoCommit(true);
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
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM `groups`")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            Logger.severe("Failed to get group names", e);
        }
        return names;
    });
}
```

**Step 2: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java
git commit -m "feat: implement MariaDB group CRUD operations"
```

---

### Task 7: MariaDBStorageProvider - Track Operations

**Files:**
- Modify: `src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java`

**Step 1: Replace all track operation placeholder methods**

```java
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
                    return Optional.empty();
                }

                String groupsJson = rs.getString("groups_json");
                List<String> groups = parseGroupsList(groupsJson);
                return Optional.of(new Track(rs.getString("name"), groups));
            }
        } catch (SQLException e) {
            Logger.severe("Failed to load track: " + name, e);
            return Optional.empty();
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
                    String name = rs.getString("name");
                    String groupsJson = rs.getString("groups_json");
                    List<String> groups = parseGroupsList(groupsJson);
                    tracks.put(name, new Track(name, groups));
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
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM tracks")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            Logger.severe("Failed to get track names", e);
        }
        return names;
    });
}
```

**Step 2: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java
git commit -m "feat: implement MariaDB track CRUD operations"
```

---

### Task 8: MariaDBStorageProvider - Backup Operations

**Files:**
- Modify: `src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java`

**Step 1: Add Gson import at the top of the file**

Add to the imports section:
```java
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
```

**Step 2: Add a static GSON field after the class declaration**

Add after the `private volatile boolean healthy = false;` field:
```java
private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
```

**Step 3: Replace all backup operation placeholder methods**

```java
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

            // Export users
            exportUsersToJson(backupDir);
            // Export groups
            exportGroupsToJson(backupDir);
            // Export tracks
            exportTracksToJson(backupDir);

            Logger.info("MariaDB backup created: " + backupName);
            return backupName;
        } catch (Exception e) {
            Logger.severe("Failed to create MariaDB backup", e);
            throw new RuntimeException("Backup failed", e);
        }
    });
}

private void exportUsersToJson(Path backupDir) throws SQLException, IOException {
    Path usersDir = backupDir.resolve("users");
    Files.createDirectories(usersDir);

    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT * FROM users")) {
        while (rs.next()) {
            String uuid = rs.getString("uuid");
            JsonObject userJson = new JsonObject();
            userJson.addProperty("uuid", uuid);
            userJson.addProperty("username", rs.getString("username"));
            userJson.addProperty("primaryGroup", rs.getString("primary_group"));
            userJson.addProperty("customPrefix", rs.getString("custom_prefix"));
            userJson.addProperty("customSuffix", rs.getString("custom_suffix"));

            // Export nodes
            JsonArray nodesArray = new JsonArray();
            try (PreparedStatement nodeStmt = conn.prepareStatement(
                    "SELECT * FROM user_nodes WHERE user_uuid = ?")) {
                nodeStmt.setString(1, uuid);
                ResultSet nodeRs = nodeStmt.executeQuery();
                while (nodeRs.next()) {
                    JsonObject nodeJson = new JsonObject();
                    nodeJson.addProperty("permission", nodeRs.getString("permission"));
                    nodeJson.addProperty("value", nodeRs.getInt("value") == 1);
                    Long expiry = nodeRs.getObject("expiry") != null ? nodeRs.getLong("expiry") : null;
                    if (expiry != null) {
                        nodeJson.addProperty("expiry", expiry);
                    }
                    nodeJson.addProperty("contexts", nodeRs.getString("contexts_json"));
                    nodesArray.add(nodeJson);
                }
            }
            userJson.add("nodes", nodesArray);

            Files.writeString(usersDir.resolve(uuid + ".json"), GSON.toJson(userJson));
        }
    }
}

private void exportGroupsToJson(Path backupDir) throws SQLException, IOException {
    Path groupsDir = backupDir.resolve("groups");
    Files.createDirectories(groupsDir);

    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT * FROM `groups`")) {
        while (rs.next()) {
            String name = rs.getString("name");
            JsonObject groupJson = new JsonObject();
            groupJson.addProperty("name", name);
            groupJson.addProperty("displayName", rs.getString("display_name"));
            groupJson.addProperty("weight", rs.getInt("weight"));
            groupJson.addProperty("prefix", rs.getString("prefix"));
            groupJson.addProperty("suffix", rs.getString("suffix"));
            groupJson.addProperty("prefixPriority", rs.getInt("prefix_priority"));
            groupJson.addProperty("suffixPriority", rs.getInt("suffix_priority"));

            // Export nodes
            JsonArray nodesArray = new JsonArray();
            try (PreparedStatement nodeStmt = conn.prepareStatement(
                    "SELECT * FROM group_nodes WHERE group_name = ?")) {
                nodeStmt.setString(1, name);
                ResultSet nodeRs = nodeStmt.executeQuery();
                while (nodeRs.next()) {
                    JsonObject nodeJson = new JsonObject();
                    nodeJson.addProperty("permission", nodeRs.getString("permission"));
                    nodeJson.addProperty("value", nodeRs.getInt("value") == 1);
                    Long expiry = nodeRs.getObject("expiry") != null ? nodeRs.getLong("expiry") : null;
                    if (expiry != null) {
                        nodeJson.addProperty("expiry", expiry);
                    }
                    nodeJson.addProperty("contexts", nodeRs.getString("contexts_json"));
                    nodesArray.add(nodeJson);
                }
            }
            groupJson.add("nodes", nodesArray);

            Files.writeString(groupsDir.resolve(name + ".json"), GSON.toJson(groupJson));
        }
    }
}

private void exportTracksToJson(Path backupDir) throws SQLException, IOException {
    Path tracksDir = backupDir.resolve("tracks");
    Files.createDirectories(tracksDir);

    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT * FROM tracks")) {
        while (rs.next()) {
            String name = rs.getString("name");
            JsonObject trackJson = new JsonObject();
            trackJson.addProperty("name", name);
            trackJson.addProperty("groups", rs.getString("groups_json"));

            Files.writeString(tracksDir.resolve(name + ".json"), GSON.toJson(trackJson));
        }
    }
}

@Override
public CompletableFuture<Boolean> restoreBackup(@NotNull String name) {
    return CompletableFuture.supplyAsync(() -> {
        Path backupDir = backupsDirectory.resolve(name);
        if (!Files.isDirectory(backupDir)) {
            Logger.warn("MariaDB backup not found: " + name);
            return false;
        }

        try {
            // Create safety backup first
            createBackup("pre-restore-" + System.currentTimeMillis()).join();

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Clear all existing data (order matters for FK constraints)
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("DELETE FROM user_nodes");
                        stmt.execute("DELETE FROM group_nodes");
                        stmt.execute("DELETE FROM users");
                        stmt.execute("DELETE FROM `groups`");
                        stmt.execute("DELETE FROM tracks");
                    }

                    // Restore groups first (users may reference them)
                    restoreGroupsFromJson(conn, backupDir);
                    // Restore users
                    restoreUsersFromJson(conn, backupDir);
                    // Restore tracks
                    restoreTracksFromJson(conn, backupDir);

                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }

            Logger.info("MariaDB backup restored: " + name);
            return true;
        } catch (Exception e) {
            Logger.severe("Failed to restore MariaDB backup", e);
            return false;
        }
    });
}

private void restoreUsersFromJson(Connection conn, Path backupDir) throws SQLException, IOException {
    Path usersDir = backupDir.resolve("users");
    if (!Files.isDirectory(usersDir)) return;

    try (var stream = Files.list(usersDir)) {
        for (Path file : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
            String json = Files.readString(file);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            String sql = """
                INSERT INTO users (uuid, username, primary_group, custom_prefix, custom_suffix)
                VALUES (?, ?, ?, ?, ?)
            """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, obj.get("uuid").getAsString());
                stmt.setString(2, obj.has("username") && !obj.get("username").isJsonNull()
                        ? obj.get("username").getAsString() : null);
                stmt.setString(3, obj.has("primaryGroup") ? obj.get("primaryGroup").getAsString() : "default");
                stmt.setString(4, obj.has("customPrefix") && !obj.get("customPrefix").isJsonNull()
                        ? obj.get("customPrefix").getAsString() : null);
                stmt.setString(5, obj.has("customSuffix") && !obj.get("customSuffix").isJsonNull()
                        ? obj.get("customSuffix").getAsString() : null);
                stmt.executeUpdate();
            }

            // Restore nodes
            if (obj.has("nodes")) {
                String nodeSql = """
                    INSERT INTO user_nodes (user_uuid, permission, value, expiry, contexts_json)
                    VALUES (?, ?, ?, ?, ?)
                """;
                try (PreparedStatement stmt = conn.prepareStatement(nodeSql)) {
                    for (var elem : obj.getAsJsonArray("nodes")) {
                        JsonObject node = elem.getAsJsonObject();
                        stmt.setString(1, obj.get("uuid").getAsString());
                        stmt.setString(2, node.get("permission").getAsString());
                        stmt.setInt(3, node.get("value").getAsBoolean() ? 1 : 0);
                        stmt.setObject(4, node.has("expiry") && !node.get("expiry").isJsonNull()
                                ? node.get("expiry").getAsLong() : null);
                        stmt.setString(5, node.has("contexts") ? node.get("contexts").getAsString() : "[]");
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        }
    }
}

private void restoreGroupsFromJson(Connection conn, Path backupDir) throws SQLException, IOException {
    Path groupsDir = backupDir.resolve("groups");
    if (!Files.isDirectory(groupsDir)) return;

    try (var stream = Files.list(groupsDir)) {
        for (Path file : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
            String json = Files.readString(file);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            String sql = """
                INSERT INTO `groups` (name, display_name, weight, prefix, suffix, prefix_priority, suffix_priority)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, obj.get("name").getAsString());
                stmt.setString(2, obj.has("displayName") && !obj.get("displayName").isJsonNull()
                        ? obj.get("displayName").getAsString() : null);
                stmt.setInt(3, obj.has("weight") ? obj.get("weight").getAsInt() : 0);
                stmt.setString(4, obj.has("prefix") && !obj.get("prefix").isJsonNull()
                        ? obj.get("prefix").getAsString() : null);
                stmt.setString(5, obj.has("suffix") && !obj.get("suffix").isJsonNull()
                        ? obj.get("suffix").getAsString() : null);
                stmt.setInt(6, obj.has("prefixPriority") ? obj.get("prefixPriority").getAsInt() : 0);
                stmt.setInt(7, obj.has("suffixPriority") ? obj.get("suffixPriority").getAsInt() : 0);
                stmt.executeUpdate();
            }

            // Restore nodes
            if (obj.has("nodes")) {
                String nodeSql = """
                    INSERT INTO group_nodes (group_name, permission, value, expiry, contexts_json)
                    VALUES (?, ?, ?, ?, ?)
                """;
                try (PreparedStatement stmt = conn.prepareStatement(nodeSql)) {
                    for (var elem : obj.getAsJsonArray("nodes")) {
                        JsonObject node = elem.getAsJsonObject();
                        stmt.setString(1, obj.get("name").getAsString());
                        stmt.setString(2, node.get("permission").getAsString());
                        stmt.setInt(3, node.get("value").getAsBoolean() ? 1 : 0);
                        stmt.setObject(4, node.has("expiry") && !node.get("expiry").isJsonNull()
                                ? node.get("expiry").getAsLong() : null);
                        stmt.setString(5, node.has("contexts") ? node.get("contexts").getAsString() : "[]");
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        }
    }
}

private void restoreTracksFromJson(Connection conn, Path backupDir) throws SQLException, IOException {
    Path tracksDir = backupDir.resolve("tracks");
    if (!Files.isDirectory(tracksDir)) return;

    try (var stream = Files.list(tracksDir)) {
        for (Path file : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
            String json = Files.readString(file);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

            String sql = "INSERT INTO tracks (name, groups_json) VALUES (?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, obj.get("name").getAsString());
                stmt.setString(2, obj.has("groups") ? obj.get("groups").getAsString() : "[]");
                stmt.executeUpdate();
            }
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
        try {
            // Delete directory recursively
            try (var walk = Files.walk(backupDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            Logger.warn("Failed to delete backup file: " + path);
                        }
                    });
            }
            return true;
        } catch (IOException e) {
            Logger.severe("Failed to delete MariaDB backup: " + name, e);
            return false;
        }
    });
}
```

**Step 2: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java
git commit -m "feat: implement MariaDB backup/restore with JSON dump strategy"
```

---

### Task 9: Wire Up StorageFactory

**Files:**
- Modify: `src/main/java/com/hyperperms/storage/StorageFactory.java`

**Step 1: Add import for MariaDBStorageProvider**

Add to imports:
```java
import com.hyperperms.storage.mariadb.MariaDBStorageProvider;
```

**Step 2: Replace the mysql/mariadb case**

Replace lines 50-54:
```java
case "mysql", "mariadb" -> {
    // TODO: Implement MySQL storage
    Logger.warn("MySQL storage not yet implemented, falling back to JSON");
    Path jsonDir = dataDirectory.resolve(config.getJsonDirectory());
    yield new JsonStorageProvider(jsonDir);
}
```

With:
```java
case "mysql", "mariadb" -> {
    Logger.info("Using MariaDB storage: %s:%d/%s",
            config.getMysqlHost(), config.getMysqlPort(), config.getMysqlDatabase());
    yield new MariaDBStorageProvider(
            config.getMysqlHost(),
            config.getMysqlPort(),
            config.getMysqlDatabase(),
            config.getMysqlUsername(),
            config.getMysqlPassword(),
            config.getMysqlUseSSL(),
            config.getMysqlPoolSize(),
            dataDirectory
    );
}
```

**Step 3: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/java/com/hyperperms/storage/StorageFactory.java
git commit -m "feat: wire MariaDB storage provider into StorageFactory"
```

---

### Task 10: Remove Placeholder Methods and Final Build Verification

**Files:**
- Modify: `src/main/java/com/hyperperms/storage/mariadb/MariaDBStorageProvider.java`

**Step 1: Remove all remaining placeholder methods**

Search for any remaining `throw new UnsupportedOperationException("TODO")` lines and ensure they've all been replaced by actual implementations. By this point all placeholders should already be replaced, but verify.

**Step 2: Run full build with tests**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (compile + existing tests pass)

**Step 3: Run shadow JAR build to verify relocations work**

Run: `./gradlew shadowJar`
Expected: BUILD SUCCESSFUL, shadow JAR created with relocated MariaDB + HikariCP classes

**Step 4: Commit any cleanup**

If any changes were needed:
```bash
git add -A
git commit -m "chore: final cleanup for MariaDB storage provider"
```

---

### Summary

| Task | Description | Key Files |
|------|-------------|-----------|
| 1 | Build dependencies | `build.gradle` |
| 2 | Config updates | `HyperPermsConfig.java` |
| 3 | Provider skeleton + init/shutdown | `MariaDBStorageProvider.java` (new) |
| 4 | Helper methods | `MariaDBStorageProvider.java` |
| 5 | User CRUD | `MariaDBStorageProvider.java` |
| 6 | Group CRUD | `MariaDBStorageProvider.java` |
| 7 | Track CRUD | `MariaDBStorageProvider.java` |
| 8 | Backup operations | `MariaDBStorageProvider.java` |
| 9 | StorageFactory wiring | `StorageFactory.java` |
| 10 | Final build verification | All files |

**Testing note:** The MariaDB provider requires a running MariaDB instance for integration testing. The existing unit tests (model, resolver, context) should continue to pass. Manual testing against a real MariaDB instance is recommended before release.
