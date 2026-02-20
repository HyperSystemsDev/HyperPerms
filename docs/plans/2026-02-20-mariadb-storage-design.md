# MariaDB Storage Backend Design

**Date:** 2026-02-20
**Status:** Approved
**Scope:** Add MariaDB/MySQL storage backend + review existing SQLite provider

## Summary

Add a new `MariaDBStorageProvider` implementing the existing `StorageProvider` interface,
using HikariCP for connection pooling. Bundle both the MariaDB JDBC driver and HikariCP
in the shadow JAR. The SQLite provider is already implemented and does not require changes.

## Architecture Decision

**Approach: Direct MariaDB Provider** (standalone, not sharing a base class with SQLite)

Rationale: The connection management models are fundamentally different between SQLite
(single connection, single-threaded executor) and MariaDB (connection pool, concurrent
access). A shared abstraction would be leaky and harder to debug. The SQL duplication
is manageable and the code is clearer when each provider is self-contained.

## Build & Dependencies

### Changes to `build.gradle`

Uncomment and activate:
- `com.zaxxer:HikariCP:6.2.1` (implementation)
- `org.mariadb.jdbc:mariadb-java-client:3.5.1` (implementation)

Shadow JAR relocations:
- `com.zaxxer.hikari` -> `com.hyperperms.lib.hikari`
- `org.mariadb.jdbc` -> `com.hyperperms.lib.mariadb`

SQLite remains `compileOnly` (user-downloaded externally).

## MariaDB Schema

```sql
CREATE TABLE IF NOT EXISTS users (
    uuid VARCHAR(36) PRIMARY KEY,
    username VARCHAR(16),
    primary_group VARCHAR(64) NOT NULL DEFAULT 'default',
    custom_prefix VARCHAR(256),
    custom_suffix VARCHAR(256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `groups` (
    name VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128),
    weight INT NOT NULL DEFAULT 0,
    prefix VARCHAR(256),
    suffix VARCHAR(256),
    prefix_priority INT NOT NULL DEFAULT 0,
    suffix_priority INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tracks (
    name VARCHAR(64) PRIMARY KEY,
    groups_json TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_nodes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_uuid VARCHAR(36) NOT NULL,
    permission VARCHAR(256) NOT NULL,
    value TINYINT NOT NULL DEFAULT 1,
    expiry BIGINT,
    contexts_json TEXT NOT NULL,
    FOREIGN KEY (user_uuid) REFERENCES users(uuid) ON DELETE CASCADE,
    UNIQUE KEY uk_user_perm_ctx (user_uuid, permission, contexts_json(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS group_nodes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_name VARCHAR(64) NOT NULL,
    permission VARCHAR(256) NOT NULL,
    value TINYINT NOT NULL DEFAULT 1,
    expiry BIGINT,
    contexts_json TEXT NOT NULL,
    FOREIGN KEY (group_name) REFERENCES `groups`(name) ON DELETE CASCADE,
    UNIQUE KEY uk_group_perm_ctx (group_name, permission, contexts_json(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

Key MySQL differences from SQLite:
- `VARCHAR(n)` for indexed/keyed columns
- `BIGINT AUTO_INCREMENT` instead of `INTEGER AUTOINCREMENT`
- `groups` backtick-escaped (reserved word)
- `utf8mb4` charset, `InnoDB` engine
- Prefix index `(255)` on `contexts_json` in unique keys

## Connection Management

### HikariCP Configuration

```
jdbcUrl: jdbc:mariadb://{host}:{port}/{database}
username / password from config
maximumPoolSize: config (default 10)
minimumIdle: same as maximumPoolSize
connectionTimeout: 5000ms
maxLifetime: 1800000ms (30 min)
useSSL: config (default false)
poolName: "HyperPerms-MariaDB"
```

### Threading Model

Unlike SQLite (single connection, single-threaded executor), MariaDB uses:
- Connection pool via HikariCP
- Each CompletableFuture gets its own connection: `try (Connection conn = dataSource.getConnection())`
- No shared connection state
- Writes use explicit transactions within a single connection scope
- Reads are simple single-statement queries

### Upsert Pattern

```sql
INSERT INTO users (uuid, username, primary_group, custom_prefix, custom_suffix)
VALUES (?, ?, ?, ?, ?)
ON DUPLICATE KEY UPDATE
    username = VALUES(username),
    primary_group = VALUES(primary_group),
    custom_prefix = VALUES(custom_prefix),
    custom_suffix = VALUES(custom_suffix)
```

## Backup Strategy

JSON dump approach:
- Read all users/groups/tracks from database
- Serialize to JSON files in timestamped backup directory
- Portable, no special MySQL privileges needed
- Restore: parse JSON files, insert back into database

## Configuration

### Config structure (`config.json`)

```json
{
    "storage": {
        "type": "mariadb",
        "mysql": {
            "host": "localhost",
            "port": 3306,
            "database": "hyperperms",
            "username": "root",
            "password": "",
            "useSSL": false,
            "maxPoolSize": 10
        }
    }
}
```

### New config getters

- `getMysqlUseSSL()` -> boolean (default false)
- Existing `getMysqlPoolSize()` already covers pool size

### Config migration

Add `useSSL` field to existing mysql config section if missing.

## File Changes

| File | Action |
|------|--------|
| `build.gradle` | Uncomment HikariCP + MariaDB deps, add relocations |
| `StorageFactory.java` | Wire up MariaDB case (remove JSON fallback) |
| `HyperPermsConfig.java` | Add `getMysqlUseSSL()`, add `useSSL` to default config, migration |
| `storage/mariadb/MariaDBStorageProvider.java` | **New** - full implementation |

## SQLite Provider Status

The existing SQLite provider is complete and functional. No changes required.
