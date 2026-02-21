package com.hyperperms.storage;

import com.hyperperms.config.HyperPermsConfig;
import com.hyperperms.storage.json.JsonStorageProvider;
import com.hyperperms.storage.mariadb.MariaDBStorageProvider;
import com.hyperperms.storage.sqlite.SQLiteStorageProvider;
import com.hyperperms.util.Logger;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Factory for creating the appropriate {@link StorageProvider} based on configuration.
 */
public final class StorageFactory {

    private StorageFactory() {}

    /**
     * Creates a storage provider based on the configuration.
     *
     * @param config        the plugin configuration
     * @param dataDirectory the plugin's data directory
     * @return the storage provider
     */
    @NotNull
    public static StorageProvider createStorage(@NotNull HyperPermsConfig config, @NotNull Path dataDirectory) {
        String type = config.getStorageType().toLowerCase();

        return switch (type) {
            case "json", "file", "flatfile" -> {
                Path jsonDir = dataDirectory.resolve(config.getJsonDirectory());
                Logger.info("Using JSON storage at: " + jsonDir);
                yield new JsonStorageProvider(jsonDir);
            }
            case "sqlite" -> {
                if (SQLiteStorageProvider.isDriverAvailable()) {
                    Path dbFile = dataDirectory.resolve(config.getSqliteFile());
                    Logger.info("Using SQLite storage at: " + dbFile);
                    yield new SQLiteStorageProvider(dbFile);
                } else {
                    Logger.warn("SQLite JDBC driver not found. To use SQLite storage:");
                    Logger.warn("  1. Download sqlite-jdbc from Maven Central");
                    Logger.warn("  2. Place it in plugins/HyperPerms/lib/");
                    Logger.warn("  3. Restart the server");
                    Logger.warn("Falling back to JSON storage.");
                    Path jsonDir = dataDirectory.resolve(config.getJsonDirectory());
                    yield new JsonStorageProvider(jsonDir);
                }
            }
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
            default -> {
                Logger.warn("Unknown storage type '%s', falling back to JSON", type);
                Path jsonDir = dataDirectory.resolve(config.getJsonDirectory());
                yield new JsonStorageProvider(jsonDir);
            }
        };
    }
}
