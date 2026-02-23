package com.hyperperms.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hyperperms.HyperPerms;
import com.hyperperms.util.Logger;
import com.hyperperms.web.dto.Change;
import com.hyperperms.web.dto.SessionCreateResponse;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for communicating with the web editor API.
 * Uses Java 11+ HttpClient for async HTTP requests.
 */
public final class WebEditorService {

    private static final Gson GSON = new GsonBuilder().create();

    private final HyperPerms hyperPerms;
    private final HttpClient httpClient;

    public WebEditorService(@NotNull HyperPerms hyperPerms) {
        this.hyperPerms = hyperPerms;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(hyperPerms.getConfig().getWebEditorTimeoutSeconds()))
                .build();
    }

    /**
     * Creates a new editor session by uploading current permission state.
     *
     * @param playerCount Current player count for metadata
     * @return CompletableFuture with the session response
     */
    public CompletableFuture<SessionCreateResponse> createSession(int playerCount) {
        // Use apiUrl for API calls (may be Cloudflare Worker)
        String apiBaseUrl = hyperPerms.getConfig().getWebEditorApiUrl();
        String url = apiBaseUrl + "/api/session/create";

        SessionData data = SessionData.fromHyperPerms(hyperPerms, playerCount);
        String json = GSON.toJson(data);

        Logger.debug("Creating web editor session at: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(hyperPerms.getConfig().getWebEditorTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
                            String sessionId = obj.get("sessionId").getAsString();
                            String editorUrl = obj.get("url").getAsString();
                            String expiresAt = obj.has("expiresAt") ? obj.get("expiresAt").getAsString() : "";
                            String wsUrl = obj.has("wsUrl") && !obj.get("wsUrl").isJsonNull()
                                    ? obj.get("wsUrl").getAsString() : null;
                            return new SessionCreateResponse(sessionId, editorUrl, expiresAt, wsUrl);
                        } catch (Exception e) {
                            Logger.warn("Failed to parse session response: " + e.getMessage());
                            return SessionCreateResponse.error("Invalid response from server");
                        }
                    } else {
                        Logger.warn("Web editor API returned status " + response.statusCode() + ": " + response.body());
                        return SessionCreateResponse.error("Server returned status " + response.statusCode());
                    }
                })
                .exceptionally(e -> {
                    Logger.warn("Failed to create web editor session: " + e.getMessage());
                    return SessionCreateResponse.error("Connection failed: " + e.getMessage());
                });
    }


    /**
     * Fetches changes from the web editor API using a session ID.
     *
     * @param sessionId The session ID from the web editor
     * @return CompletableFuture with the list of changes
     */
    public CompletableFuture<FetchChangesResult> fetchChanges(@NotNull String sessionId) {
        // Use apiUrl for API calls (may be Cloudflare Worker)
        String apiBaseUrl = hyperPerms.getConfig().getWebEditorApiUrl();
        String url = apiBaseUrl + "/api/session/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + "/changes";

        Logger.debug("Fetching changes from: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(hyperPerms.getConfig().getWebEditorTimeoutSeconds()))
                .header("Accept", "application/json")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    String body = response.body();
                    Logger.debug("Changes API response (status " + response.statusCode() + "): " + body);
                    
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        try {
                            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                            List<Change> changes = new ArrayList<>();
                            
                            Logger.debug("Response has 'changes' key: " + obj.has("changes"));
                            if (obj.has("changes")) {
                                JsonElement changesElement = obj.get("changes");
                                Logger.debug("changes isJsonObject: " + changesElement.isJsonObject() +
                                           ", isJsonArray: " + changesElement.isJsonArray() +
                                           ", isJsonNull: " + changesElement.isJsonNull());
                            }
                            
                            if (obj.has("changes") && obj.get("changes").isJsonObject()) {
                                // API returns categorized changes object
                                JsonObject changesObj = obj.getAsJsonObject("changes");
                                Logger.debug("Parsing categorized changes. Keys: " + changesObj.keySet());
                                
                                // Parse groups to create
                                if (changesObj.has("groupsToCreate") && changesObj.get("groupsToCreate").isJsonArray()) {
                                    JsonArray arr = changesObj.getAsJsonArray("groupsToCreate");
                                    Logger.debug("groupsToCreate has " + arr.size() + " items");
                                    for (JsonElement elem : arr) {
                                        Change change = parseGroupChange(elem.getAsJsonObject(), Change.Type.GROUP_CREATED);
                                        if (change != null) changes.add(change);
                                    }
                                }
                                
                                // Parse groups to update (modified)
                                if (changesObj.has("groupsToUpdate") && changesObj.get("groupsToUpdate").isJsonArray()) {
                                    JsonArray arr = changesObj.getAsJsonArray("groupsToUpdate");
                                    Logger.debug("groupsToUpdate has " + arr.size() + " items");
                                    for (JsonElement elem : arr) {
                                        List<Change> groupChanges = parseGroupUpdateChanges(elem.getAsJsonObject());
                                        Logger.debug("Parsed " + groupChanges.size() + " changes from group update");
                                        changes.addAll(groupChanges);
                                    }
                                }
                                
                                // Parse groups to delete
                                if (changesObj.has("groupsToDelete") && changesObj.get("groupsToDelete").isJsonArray()) {
                                    for (JsonElement elem : changesObj.getAsJsonArray("groupsToDelete")) {
                                        String groupName = elem.isJsonPrimitive() ? elem.getAsString() 
                                            : elem.getAsJsonObject().get("name").getAsString();
                                        changes.add(Change.builder(Change.Type.GROUP_DELETED)
                                            .targetType("group")
                                            .target(groupName)
                                            .groupName(groupName)
                                            .build());
                                    }
                                }
                                
                                // Parse users to update
                                if (changesObj.has("usersToUpdate") && changesObj.get("usersToUpdate").isJsonArray()) {
                                    JsonArray arr = changesObj.getAsJsonArray("usersToUpdate");
                                    Logger.debug("usersToUpdate has " + arr.size() + " items");
                                    for (JsonElement elem : arr) {
                                        List<Change> userChanges = parseUserUpdateChanges(elem.getAsJsonObject());
                                        Logger.debug("Parsed " + userChanges.size() + " changes from user update");
                                        changes.addAll(userChanges);
                                    }
                                }
                                
                                // Parse users to delete
                                if (changesObj.has("usersToDelete") && changesObj.get("usersToDelete").isJsonArray()) {
                                    for (JsonElement elem : changesObj.getAsJsonArray("usersToDelete")) {
                                        String userId = elem.isJsonPrimitive() ? elem.getAsString()
                                            : elem.getAsJsonObject().get("uuid").getAsString();
                                        // User deletion is handled as removing all permissions/groups
                                        Logger.debug("User deletion requested for: " + userId);
                                    }
                                }
                                
                                // Parse tracks to create
                                if (changesObj.has("tracksToCreate") && changesObj.get("tracksToCreate").isJsonArray()) {
                                    for (JsonElement elem : changesObj.getAsJsonArray("tracksToCreate")) {
                                        Change change = parseTrackChange(elem.getAsJsonObject(), Change.Type.TRACK_CREATED);
                                        if (change != null) changes.add(change);
                                    }
                                }
                                
                                // Parse tracks to update
                                if (changesObj.has("tracksToUpdate") && changesObj.get("tracksToUpdate").isJsonArray()) {
                                    for (JsonElement elem : changesObj.getAsJsonArray("tracksToUpdate")) {
                                        Change change = parseTrackChange(elem.getAsJsonObject(), Change.Type.TRACK_MODIFIED);
                                        if (change != null) changes.add(change);
                                    }
                                }
                                
                                // Parse tracks to delete
                                if (changesObj.has("tracksToDelete") && changesObj.get("tracksToDelete").isJsonArray()) {
                                    for (JsonElement elem : changesObj.getAsJsonArray("tracksToDelete")) {
                                        String trackName = elem.isJsonPrimitive() ? elem.getAsString()
                                            : elem.getAsJsonObject().get("name").getAsString();
                                        changes.add(Change.builder(Change.Type.TRACK_DELETED)
                                            .targetType("track")
                                            .target(trackName)
                                            .trackName(trackName)
                                            .build());
                                    }
                                }
                                
                                Logger.debug("Parsed " + changes.size() + " change(s) from web editor");
                            } else if (obj.has("changes") && obj.get("changes").isJsonArray()) {
                                // Legacy flat array format
                                for (JsonElement element : obj.getAsJsonArray("changes")) {
                                    Change change = parseChange(element.getAsJsonObject());
                                    if (change != null) changes.add(change);
                                }
                            }

                            // Diagnostic logging for empty changes
                            if (changes.isEmpty()) {
                                Logger.warn("Session " + sessionId + " returned 0 changes. This may indicate:");
                                Logger.warn("  - No edits were made in the web editor");
                                Logger.warn("  - Session expired or was never saved");
                                Logger.warn("  - API response format mismatch");

                                // Check if 'changes' key was missing entirely
                                if (!obj.has("changes") || obj.get("changes").isJsonNull()) {
                                    Logger.warn("API response missing 'changes' key entirely - possible API issue");
                                }
                            }

                            return new FetchChangesResult(changes, null);
                        } catch (Exception e) {
                            Logger.warn("Failed to parse changes response: " + e.getMessage());
                            e.printStackTrace();
                            return new FetchChangesResult(null, "Invalid response from server: " + e.getMessage());
                        }
                    } else if (response.statusCode() == 404) {
                        return new FetchChangesResult(null, "Session not found or expired");
                    } else {
                        Logger.warn("Web editor API returned status " + response.statusCode() + ": " + body);
                        return new FetchChangesResult(null, "Server returned status " + response.statusCode());
                    }
                })
                .exceptionally(e -> {
                    Logger.warn("Failed to fetch changes: " + e.getMessage());
                    return new FetchChangesResult(null, "Connection failed: " + e.getMessage());
                });
    }

    /**
     * Result of fetching changes from the API.
     */
    public static final class FetchChangesResult {
        private final List<Change> changes;
        private final String error;

        public FetchChangesResult(List<Change> changes, String error) {
            this.changes = changes;
            this.error = error;
        }

        public List<Change> getChanges() {
            return changes;
        }

        public String getError() {
            return error;
        }

        public boolean isSuccess() {
            return error == null && changes != null;
        }
    }

    /**
     * Parses changes from a base64-encoded apply code.
     *
     * @param applyCode The base64-encoded JSON array of changes
     * @return List of parsed changes
     * @throws IllegalArgumentException if the code is invalid
     */
    public List<Change> parseApplyCode(@NotNull String applyCode) {
        try {
            byte[] decoded = Base64.getDecoder().decode(applyCode);
            String json = new String(decoded, StandardCharsets.UTF_8);
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();

            List<Change> changes = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                Change change = parseChange(obj);
                if (change != null) {
                    changes.add(change);
                }
            }
            return changes;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 encoding in apply code", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse apply code: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a group creation change from the API format.
     */
    private Change parseGroupChange(JsonObject groupObj, Change.Type type) {
        String name = safeGetString(groupObj, "name");
        if (name == null || name.isEmpty()) {
            Logger.warn("Group change missing 'name' field: " + groupObj);
            return null;
        }
        String displayName = safeGetString(groupObj, "displayName");
        int weight = safeGetInt(groupObj, "weight", 0);
        String prefix = safeGetString(groupObj, "prefix");
        String suffix = safeGetString(groupObj, "suffix");
        int prefixPriority = safeGetInt(groupObj, "prefixPriority", 0);
        int suffixPriority = safeGetInt(groupObj, "suffixPriority", 0);

        List<Change.PermissionNode> permissions = new ArrayList<>();
        if (groupObj.has("permissions") && groupObj.get("permissions").isJsonArray()) {
            for (JsonElement elem : groupObj.getAsJsonArray("permissions")) {
                if (!elem.isJsonObject()) continue;
                JsonObject permObj = elem.getAsJsonObject();
                String node = safeGetString(permObj, "node");
                if (node == null || node.isEmpty()) {
                    // Also try "permission" or "name" field
                    node = safeGetString(permObj, "permission");
                    if (node == null || node.isEmpty()) {
                        node = safeGetString(permObj, "name");
                    }
                }
                if (node == null || node.isEmpty()) {
                    Logger.warn("Skipping permission with no node field: " + permObj);
                    continue;
                }
                boolean value = safeGetBoolean(permObj, "value", true);
                Map<String, String> contexts = permObj.has("contexts") && !permObj.get("contexts").isJsonNull()
                        ? parseContexts(permObj.getAsJsonObject("contexts"))
                        : Collections.emptyMap();
                Long expiry = permObj.has("expiry") && !permObj.get("expiry").isJsonNull()
                        ? permObj.get("expiry").getAsLong()
                        : null;
                permissions.add(new Change.PermissionNode(node, value, contexts, expiry));
            }
        }

        List<String> parents = groupObj.has("parents") && groupObj.get("parents").isJsonArray()
                ? parseStringList(groupObj.getAsJsonArray("parents"))
                : Collections.emptyList();

        Change.GroupData groupData = new Change.GroupData(name, displayName, weight, prefix, suffix, prefixPriority, suffixPriority, permissions, parents);

        return Change.builder(type)
                .targetType("group")
                .target(name)
                .groupName(name)
                .group(groupData)
                .build();
    }

    /**
     * Parses group update changes - creates a single GROUP_CREATED change
     * that will either create a new group or update an existing one.
     */
    private List<Change> parseGroupUpdateChanges(JsonObject groupObj) {
        List<Change> changes = new ArrayList<>();
        String groupName = safeGetString(groupObj, "name");
        if (groupName == null || groupName.isEmpty()) {
            Logger.warn("Group update missing 'name' field: " + groupObj);
            return changes;
        }

        // Create full group data - the ChangeApplier will handle create vs update
        Change.GroupData groupData = new Change.GroupData(
            groupName,
            safeGetString(groupObj, "displayName"),
            safeGetInt(groupObj, "weight", 0),
            safeGetString(groupObj, "prefix"),
            safeGetString(groupObj, "suffix"),
            safeGetInt(groupObj, "prefixPriority", 0),
            safeGetInt(groupObj, "suffixPriority", 0),
            parsePermissionNodes(groupObj),
            groupObj.has("parents") && groupObj.get("parents").isJsonArray()
                ? parseStringList(groupObj.getAsJsonArray("parents"))
                : Collections.emptyList()
        );

        // Create a single change that will handle full group sync
        changes.add(Change.builder(Change.Type.GROUP_CREATED)
            .targetType("group")
            .target(groupName)
            .groupName(groupName)
            .group(groupData)
            .build());

        return changes;
    }

    /**
     * Parses permission nodes from a group/user object.
     */
    private List<Change.PermissionNode> parsePermissionNodes(JsonObject obj) {
        List<Change.PermissionNode> permissions = new ArrayList<>();
        if (obj.has("permissions") && obj.get("permissions").isJsonArray()) {
            for (JsonElement elem : obj.getAsJsonArray("permissions")) {
                JsonObject permObj = elem.getAsJsonObject();
                
                // Try different field names for the permission node
                String node = null;
                if (permObj.has("node") && !permObj.get("node").isJsonNull()) {
                    node = permObj.get("node").getAsString();
                } else if (permObj.has("permission") && !permObj.get("permission").isJsonNull()) {
                    node = permObj.get("permission").getAsString();
                } else if (permObj.has("name") && !permObj.get("name").isJsonNull()) {
                    node = permObj.get("name").getAsString();
                }
                
                if (node == null || node.isEmpty()) {
                    Logger.warn("Skipping permission with no node/permission/name field: " + permObj);
                    continue;
                }
                
                boolean value = !permObj.has("value") || permObj.get("value").isJsonNull() || permObj.get("value").getAsBoolean();
                Map<String, String> contexts = permObj.has("contexts") && !permObj.get("contexts").isJsonNull()
                        ? parseContexts(permObj.getAsJsonObject("contexts"))
                        : Collections.emptyMap();
                Long expiry = permObj.has("expiry") && !permObj.get("expiry").isJsonNull()
                        ? permObj.get("expiry").getAsLong()
                        : null;
                permissions.add(new Change.PermissionNode(node, value, contexts, expiry));
            }
        }
        return permissions;
    }

    /**
     * Parses user update changes - creates a USER_SYNC change that replaces all user data.
     */
    private List<Change> parseUserUpdateChanges(JsonObject userObj) {
        List<Change> changes = new ArrayList<>();
        String uuid = safeGetString(userObj, "uuid");
        if (uuid == null || uuid.isEmpty()) {
            Logger.warn("User update missing 'uuid' field: " + userObj);
            return changes;
        }
        String username = safeGetString(userObj, "username");
        if (username == null || username.isEmpty()) {
            username = uuid;
        }
        
        Logger.debug("Parsing user update for: " + username + " (" + uuid + ")");

        // Get all the user data from the API
        String primaryGroup = safeGetString(userObj, "primaryGroup");
        
        // Get custom prefix/suffix
        String customPrefix = safeGetString(userObj, "customPrefix");
        String customSuffix = safeGetString(userObj, "customSuffix");
        
        List<Change.PermissionNode> permissions = parsePermissionNodes(userObj);
        
        // Get parent groups - prefer "groups" field (edited state) over "parents" (original state)
        // IMPORTANT: If "groups" field exists (even if empty), use it - empty means "no groups"
        // Only fall back to "parents" if "groups" field doesn't exist at all
        List<String> parentGroups = new ArrayList<>();
        if (userObj.has("groups") && userObj.get("groups").isJsonArray()) {
            // Use groups field (this is the edited/current state from web app)
            // Empty array means user has no additional groups - that's valid!
            parentGroups.addAll(parseStringList(userObj.getAsJsonArray("groups")));
            Logger.debug("  - Using 'groups' field for parent groups (count: " + parentGroups.size() + ")");
        } else if (userObj.has("parents") && userObj.get("parents").isJsonArray()) {
            // Fall back to parents field only if groups doesn't exist
            parentGroups.addAll(parseStringList(userObj.getAsJsonArray("parents")));
            Logger.debug("  - Using 'parents' field for parent groups (fallback)");
        }
        
        Logger.debug("  - primaryGroup: " + primaryGroup);
        Logger.debug("  - customPrefix: " + customPrefix);
        Logger.debug("  - customSuffix: " + customSuffix);
        Logger.debug("  - permissions: " + permissions.size());
        Logger.debug("  - parent groups: " + parentGroups.size() + " " + parentGroups);

        // Create a single USER_SYNC change that will handle full replacement
        // We'll use a special marker in the Change to indicate this is a full sync
        Change.Builder builder = Change.builder(Change.Type.META_CHANGED)
            .targetType("user_sync")  // Special marker for full user sync
            .target(uuid);
        
        // Store all data in the change
        if (primaryGroup != null) {
            builder.key("primaryGroup");
            builder.metaNewValue(primaryGroup);
        }
        
        // Store parent groups as comma-separated for now
        builder.parent(String.join(",", parentGroups));
        
        changes.add(builder.build());
        
        // Add custom prefix change if present
        if (customPrefix != null) {
            changes.add(Change.builder(Change.Type.META_CHANGED)
                .targetType("user")
                .target(uuid)
                .key("prefix")
                .metaNewValue(customPrefix)
                .build());
        }
        
        // Add custom suffix change if present
        if (customSuffix != null) {
            changes.add(Change.builder(Change.Type.META_CHANGED)
                .targetType("user")
                .target(uuid)
                .key("suffix")
                .metaNewValue(customSuffix)
                .build());
        }
        
        // Also add individual permission changes
        for (Change.PermissionNode perm : permissions) {
            changes.add(Change.builder(Change.Type.PERMISSION_ADDED)
                .targetType("user")
                .target(uuid)
                .node(perm.getNode())
                .value(perm.getValue())
                .contexts(perm.getContexts())
                .expiry(perm.getExpiry())
                .build());
        }

        return changes;
    }

    /**
     * Parses a track change from the API format.
     */
    private Change parseTrackChange(JsonObject trackObj, Change.Type type) {
        String name = safeGetString(trackObj, "name");
        if (name == null || name.isEmpty()) {
            Logger.warn("Track change missing 'name' field: " + trackObj);
            return null;
        }
        List<String> groups = trackObj.has("groups") && trackObj.get("groups").isJsonArray()
                ? parseStringList(trackObj.getAsJsonArray("groups"))
                : Collections.emptyList();

        Change.TrackData trackData = new Change.TrackData(name, groups);

        return Change.builder(type)
                .targetType("track")
                .target(name)
                .trackName(name)
                .track(trackData)
                .build();
    }

    /**
     * Safely gets a string from a JsonObject, returning null if the field is missing or null.
     */
    private String safeGetString(JsonObject obj, String field) {
        if (!obj.has(field)) return null;
        JsonElement elem = obj.get(field);
        if (elem == null || elem.isJsonNull()) return null;
        return elem.getAsString();
    }

    /**
     * Safely gets an int from a JsonObject, returning the default value if the field is missing or null.
     */
    private int safeGetInt(JsonObject obj, String field, int defaultValue) {
        if (!obj.has(field)) return defaultValue;
        JsonElement elem = obj.get(field);
        if (elem == null || elem.isJsonNull()) return defaultValue;
        return elem.getAsInt();
    }

    /**
     * Safely gets a boolean from a JsonObject, returning the default value if the field is missing or null.
     */
    private boolean safeGetBoolean(JsonObject obj, String field, boolean defaultValue) {
        if (!obj.has(field)) return defaultValue;
        JsonElement elem = obj.get(field);
        if (elem == null || elem.isJsonNull()) return defaultValue;
        return elem.getAsBoolean();
    }

    private Change parseChange(JsonObject obj) {
        String typeStr = safeGetString(obj, "type");
        if (typeStr == null) {
            Logger.warn("Change object missing 'type' field: " + obj);
            return null;
        }
        Change.Type type = parseChangeType(typeStr);
        if (type == null) {
            Logger.warn("Unknown change type: " + typeStr);
            return null;
        }

        Change.Builder builder = Change.builder(type);

        // Common fields
        String targetType = safeGetString(obj, "targetType");
        if (targetType != null) {
            builder.targetType(targetType);
        }
        String target = safeGetString(obj, "target");
        if (target != null) {
            builder.target(target);
        }

        // Permission fields
        String node = safeGetString(obj, "node");
        if (node != null) {
            builder.node(node);
        }
        if (obj.has("value") && !obj.get("value").isJsonNull()) {
            builder.value(obj.get("value").getAsBoolean());
        }
        if (obj.has("oldValue") && !obj.get("oldValue").isJsonNull() && obj.get("oldValue").isJsonPrimitive()) {
            if (obj.get("oldValue").getAsJsonPrimitive().isBoolean()) {
                builder.oldValue(obj.get("oldValue").getAsBoolean());
            }
        }
        if (obj.has("newValue") && !obj.get("newValue").isJsonNull() && obj.get("newValue").isJsonPrimitive()) {
            if (obj.get("newValue").getAsJsonPrimitive().isBoolean()) {
                builder.newValue(obj.get("newValue").getAsBoolean());
            }
        }
        if (obj.has("contexts") && !obj.get("contexts").isJsonNull() && obj.get("contexts").isJsonObject()) {
            builder.contexts(parseContexts(obj.getAsJsonObject("contexts")));
        }
        if (obj.has("expiry") && !obj.get("expiry").isJsonNull()) {
            builder.expiry(obj.get("expiry").getAsLong());
        }

        // Group fields
        String groupName = safeGetString(obj, "groupName");
        if (groupName != null) {
            builder.groupName(groupName);
        }
        if (obj.has("group") && !obj.get("group").isJsonNull() && obj.get("group").isJsonObject()) {
            Change.GroupData groupData = parseGroupData(obj.getAsJsonObject("group"));
            if (groupData != null) {
                builder.group(groupData);
            }
        }

        // Parent fields
        String parent = safeGetString(obj, "parent");
        if (parent != null) {
            builder.parent(parent);
        }

        // Meta fields
        String key = safeGetString(obj, "key");
        if (key != null) {
            builder.key(key);
        }
        // Handle both meta changes (string) and permission changes (boolean)
        if (obj.has("oldValue") && !obj.get("oldValue").isJsonNull() && obj.get("oldValue").isJsonPrimitive()) {
            if (obj.get("oldValue").getAsJsonPrimitive().isString()) {
                builder.metaOldValue(obj.get("oldValue").getAsString());
            }
        }
        if (obj.has("newValue") && !obj.get("newValue").isJsonNull() && obj.get("newValue").isJsonPrimitive()) {
            if (obj.get("newValue").getAsJsonPrimitive().isString()) {
                builder.metaNewValue(obj.get("newValue").getAsString());
            }
        }

        // Weight fields
        if (obj.has("oldWeight") && !obj.get("oldWeight").isJsonNull()) {
            builder.oldWeight(obj.get("oldWeight").getAsInt());
        }
        if (obj.has("newWeight") && !obj.get("newWeight").isJsonNull()) {
            builder.newWeight(obj.get("newWeight").getAsInt());
        }

        // Track fields
        String trackName = safeGetString(obj, "trackName");
        if (trackName != null) {
            builder.trackName(trackName);
        }
        if (obj.has("track") && !obj.get("track").isJsonNull() && obj.get("track").isJsonObject()) {
            Change.TrackData trackData = parseTrackData(obj.getAsJsonObject("track"));
            if (trackData != null) {
                builder.track(trackData);
            }
        }
        if (obj.has("oldGroups") && !obj.get("oldGroups").isJsonNull() && obj.get("oldGroups").isJsonArray()) {
            builder.oldGroups(parseStringList(obj.getAsJsonArray("oldGroups")));
        }
        if (obj.has("newGroups") && !obj.get("newGroups").isJsonNull() && obj.get("newGroups").isJsonArray()) {
            builder.newGroups(parseStringList(obj.getAsJsonArray("newGroups")));
        }

        return builder.build();
    }

    private Change.Type parseChangeType(String type) {
        return switch (type.toLowerCase()) {
            case "permission_added" -> Change.Type.PERMISSION_ADDED;
            case "permission_removed" -> Change.Type.PERMISSION_REMOVED;
            case "permission_modified" -> Change.Type.PERMISSION_MODIFIED;
            case "group_created" -> Change.Type.GROUP_CREATED;
            case "group_deleted" -> Change.Type.GROUP_DELETED;
            case "parent_added" -> Change.Type.PARENT_ADDED;
            case "parent_removed" -> Change.Type.PARENT_REMOVED;
            case "meta_changed" -> Change.Type.META_CHANGED;
            case "weight_changed" -> Change.Type.WEIGHT_CHANGED;
            case "track_created" -> Change.Type.TRACK_CREATED;
            case "track_deleted" -> Change.Type.TRACK_DELETED;
            case "track_modified" -> Change.Type.TRACK_MODIFIED;
            default -> null;
        };
    }

    private Map<String, String> parseContexts(JsonObject obj) {
        Map<String, String> contexts = new HashMap<>();
        for (String key : obj.keySet()) {
            contexts.put(key, obj.get(key).getAsString());
        }
        return contexts;
    }

    private Change.GroupData parseGroupData(JsonObject obj) {
        String name = safeGetString(obj, "name");
        if (name == null || name.isEmpty()) {
            Logger.warn("GroupData missing 'name' field: " + obj);
            return null;
        }
        String displayName = safeGetString(obj, "displayName");
        int weight = safeGetInt(obj, "weight", 0);
        String prefix = safeGetString(obj, "prefix");
        String suffix = safeGetString(obj, "suffix");
        int prefixPriority = safeGetInt(obj, "prefixPriority", 0);
        int suffixPriority = safeGetInt(obj, "suffixPriority", 0);

        List<Change.PermissionNode> permissions = new ArrayList<>();
        if (obj.has("permissions") && obj.get("permissions").isJsonArray()) {
            for (JsonElement elem : obj.getAsJsonArray("permissions")) {
                if (!elem.isJsonObject()) continue;
                JsonObject permObj = elem.getAsJsonObject();
                String node = safeGetString(permObj, "node");
                if (node == null || node.isEmpty()) {
                    node = safeGetString(permObj, "permission");
                }
                if (node == null || node.isEmpty()) {
                    Logger.warn("Skipping permission with no node field in parseGroupData: " + permObj);
                    continue;
                }
                boolean value = safeGetBoolean(permObj, "value", true);
                Map<String, String> contexts = permObj.has("contexts") && !permObj.get("contexts").isJsonNull()
                        ? parseContexts(permObj.getAsJsonObject("contexts"))
                        : Collections.emptyMap();
                Long expiry = permObj.has("expiry") && !permObj.get("expiry").isJsonNull()
                        ? permObj.get("expiry").getAsLong()
                        : null;
                permissions.add(new Change.PermissionNode(node, value, contexts, expiry));
            }
        }

        List<String> parents = obj.has("parents") && obj.get("parents").isJsonArray()
                ? parseStringList(obj.getAsJsonArray("parents"))
                : Collections.emptyList();

        return new Change.GroupData(name, displayName, weight, prefix, suffix, prefixPriority, suffixPriority, permissions, parents);
    }

    private Change.TrackData parseTrackData(JsonObject obj) {
        String name = safeGetString(obj, "name");
        if (name == null || name.isEmpty()) {
            Logger.warn("TrackData missing 'name' field: " + obj);
            return null;
        }
        List<String> groups = obj.has("groups") && obj.get("groups").isJsonArray()
                ? parseStringList(obj.getAsJsonArray("groups")) 
                : Collections.emptyList();
        return new Change.TrackData(name, groups);
    }

    private List<String> parseStringList(JsonArray array) {
        List<String> list = new ArrayList<>();
        for (JsonElement elem : array) {
            list.add(elem.getAsString());
        }
        return list;
    }
}
