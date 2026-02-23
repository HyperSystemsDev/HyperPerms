package com.hyperperms.web;

import com.hyperperms.HyperPerms;
import com.hyperperms.model.Group;
import com.hyperperms.model.Node;
import com.hyperperms.model.Track;
import com.hyperperms.model.User;
import com.hyperperms.util.Logger;
import com.hyperperms.web.dto.Change;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies changes from the web editor to the permission system.
 */
public final class ChangeApplier {

    private static final ThreadLocal<Boolean> applyingFromWebSocket = ThreadLocal.withInitial(() -> false);

    /**
     * Checks if the current thread is applying changes from a WebSocket message.
     * EventBus listeners can use this to avoid echo loops.
     *
     * @return true if changes are being applied from a WebSocket message
     */
    public static boolean isApplyingFromWebSocket() {
        return applyingFromWebSocket.get();
    }

    private final HyperPerms hyperPerms;

    public ChangeApplier(@NotNull HyperPerms hyperPerms) {
        this.hyperPerms = hyperPerms;
    }

    /**
     * Applies changes originating from a WebSocket message.
     * Sets a thread-local flag so EventBus listeners can detect the source
     * and avoid echo loops.
     *
     * @param changes The changes to apply
     * @return Result with success/failure counts
     */
    public ApplyResult applyChangesFromWebSocket(@NotNull List<Change> changes) {
        applyingFromWebSocket.set(true);
        try {
            return applyChanges(changes);
        } finally {
            applyingFromWebSocket.set(false);
        }
    }

    /**
     * Result of applying changes.
     */
    public static final class ApplyResult {
        private final int successCount;
        private final int failureCount;
        private final List<String> errors;

        public ApplyResult(int successCount, int failureCount, @NotNull List<String> errors) {
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.errors = errors;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailureCount() {
            return failureCount;
        }

        public List<String> getErrors() {
            return errors;
        }

        public boolean hasErrors() {
            return failureCount > 0;
        }
    }

    /**
     * Applies a list of changes to the permission system.
     *
     * @param changes The changes to apply
     * @return Result with success/failure counts
     */
    public ApplyResult applyChanges(@NotNull List<Change> changes) {
        int successCount = 0;
        int failureCount = 0;
        List<String> errors = new ArrayList<>();

        for (Change change : changes) {
            try {
                boolean success = applyChange(change);
                if (success) {
                    successCount++;
                    logChange(change);
                } else {
                    failureCount++;
                    errors.add("Failed to apply " + change.getType() + " for " + getTargetDescription(change));
                }
            } catch (Exception e) {
                failureCount++;
                errors.add(change.getType() + ": " + e.getMessage());
                Logger.warn("Error applying change " + change.getType() + ": " + e.getMessage());
            }
        }

        // Invalidate all caches after applying changes
        hyperPerms.getCache().invalidateAll();
        
        // Also invalidate chat display data cache
        var chatManager = hyperPerms.getChatManager();
        if (chatManager != null) {
            chatManager.invalidateAllCaches();
        }

        return new ApplyResult(successCount, failureCount, errors);
    }

    private boolean applyChange(Change change) {
        return switch (change.getType()) {
            case PERMISSION_ADDED -> applyPermissionAdded(change);
            case PERMISSION_REMOVED -> applyPermissionRemoved(change);
            case PERMISSION_MODIFIED -> applyPermissionModified(change);
            case GROUP_CREATED -> applyGroupCreated(change);
            case GROUP_DELETED -> applyGroupDeleted(change);
            case PARENT_ADDED -> applyParentAdded(change);
            case PARENT_REMOVED -> applyParentRemoved(change);
            case META_CHANGED -> applyMetaChanged(change);
            case WEIGHT_CHANGED -> applyWeightChanged(change);
            case TRACK_CREATED -> applyTrackCreated(change);
            case TRACK_DELETED -> applyTrackDeleted(change);
            case TRACK_MODIFIED -> applyTrackModified(change);
        };
    }

    private boolean applyPermissionAdded(Change change) {
        Node node = buildNode(change.getNode(), change.getValue(), change.getContexts(), change.getExpiry());
        
        if ("group".equalsIgnoreCase(change.getTargetType())) {
            Group group = hyperPerms.getGroupManager().getGroup(change.getTarget());
            if (group == null) return false;
            group.setNode(node);
            hyperPerms.getGroupManager().saveGroup(group);
            return true;
        } else if ("user".equalsIgnoreCase(change.getTargetType())) {
            User user = getUser(change.getTarget());
            if (user == null) return false;
            user.setNode(node);
            hyperPerms.getUserManager().saveUser(user);
            return true;
        }
        return false;
    }

    private boolean applyPermissionRemoved(Change change) {
        if ("group".equalsIgnoreCase(change.getTargetType())) {
            Group group = hyperPerms.getGroupManager().getGroup(change.getTarget());
            if (group == null) return false;
            group.removeNode(change.getNode());
            hyperPerms.getGroupManager().saveGroup(group);
            return true;
        } else if ("user".equalsIgnoreCase(change.getTargetType())) {
            User user = getUser(change.getTarget());
            if (user == null) return false;
            user.removeNode(change.getNode());
            hyperPerms.getUserManager().saveUser(user);
            return true;
        }
        return false;
    }

    private boolean applyPermissionModified(Change change) {
        // For modified, we remove the old and add the new
        Node node = buildNode(change.getNode(), change.getNewValue(), change.getContexts(), change.getExpiry());
        
        if ("group".equalsIgnoreCase(change.getTargetType())) {
            Group group = hyperPerms.getGroupManager().getGroup(change.getTarget());
            if (group == null) return false;
            group.removeNode(change.getNode());
            group.setNode(node);
            hyperPerms.getGroupManager().saveGroup(group);
            return true;
        } else if ("user".equalsIgnoreCase(change.getTargetType())) {
            User user = getUser(change.getTarget());
            if (user == null) return false;
            user.removeNode(change.getNode());
            user.setNode(node);
            hyperPerms.getUserManager().saveUser(user);
            return true;
        }
        return false;
    }

    private boolean applyGroupCreated(Change change) {
        Change.GroupData data = change.getGroup();
        if (data == null) return false;

        // Get or create the group
        Group group = hyperPerms.getGroupManager().getGroup(data.getName());
        boolean isNew = (group == null);
        
        if (isNew) {
            group = hyperPerms.getGroupManager().createGroup(data.getName());
            if (group == null) return false;
            Logger.info("Created new group: " + data.getName());
        } else {
            Logger.info("Updating existing group: " + data.getName());
        }

        // Set properties
        group.setDisplayName(data.getDisplayName());
        group.setWeight(data.getWeight());
        group.setPrefix(data.getPrefix());
        group.setSuffix(data.getSuffix());
        group.setPrefixPriority(data.getPrefixPriority());
        group.setSuffixPriority(data.getSuffixPriority());

        // Clear existing permissions and parents if updating (clearNodes removes all including parent groups)
        if (!isNew) {
            group.clearNodes();
        }

        // Add permissions
        for (Change.PermissionNode perm : data.getPermissions()) {
            Node node = buildNode(perm.getNode(), perm.getValue(), perm.getContexts(), perm.getExpiry());
            group.setNode(node);
        }

        // Add parent groups
        for (String parent : data.getParents()) {
            group.addParent(parent);
        }

        hyperPerms.getGroupManager().saveGroup(group);
        return true;
    }

    private boolean applyGroupDeleted(Change change) {
        String groupName = change.getGroupName();
        if (groupName == null) return false;

        Group group = hyperPerms.getGroupManager().getGroup(groupName);
        if (group == null) return false;

        hyperPerms.getGroupManager().deleteGroup(groupName);
        return true;
    }

    private boolean applyParentAdded(Change change) {
        if ("group".equalsIgnoreCase(change.getTargetType())) {
            Group group = hyperPerms.getGroupManager().getGroup(change.getTarget());
            if (group == null) return false;
            group.addParent(change.getParent());
            hyperPerms.getGroupManager().saveGroup(group);
            return true;
        } else if ("user".equalsIgnoreCase(change.getTargetType())) {
            User user = getUser(change.getTarget());
            if (user == null) return false;
            user.addGroup(change.getParent());
            hyperPerms.getUserManager().saveUser(user);
            return true;
        }
        return false;
    }

    private boolean applyParentRemoved(Change change) {
        if ("group".equalsIgnoreCase(change.getTargetType())) {
            Group group = hyperPerms.getGroupManager().getGroup(change.getTarget());
            if (group == null) return false;
            group.removeParent(change.getParent());
            hyperPerms.getGroupManager().saveGroup(group);
            return true;
        } else if ("user".equalsIgnoreCase(change.getTargetType())) {
            User user = getUser(change.getTarget());
            if (user == null) return false;
            user.removeGroup(change.getParent());
            hyperPerms.getUserManager().saveUser(user);
            return true;
        }
        return false;
    }

    private boolean applyMetaChanged(Change change) {
        String key = change.getKey();
        String newValue = change.getMetaNewValue();

        // Handle full user sync (replaces all groups, not just adds)
        if ("user_sync".equalsIgnoreCase(change.getTargetType())) {
            return applyUserSync(change);
        }

        if ("group".equalsIgnoreCase(change.getTargetType())) {
            Group group = hyperPerms.getGroupManager().getGroup(change.getTarget());
            if (group == null) return false;

            switch (key.toLowerCase()) {
                case "prefix" -> group.setPrefix(newValue);
                case "suffix" -> group.setSuffix(newValue);
                case "displayname" -> group.setDisplayName(newValue);
                case "prefixpriority" -> {
                    try {
                        group.setPrefixPriority(Integer.parseInt(newValue));
                    } catch (NumberFormatException e) {
                        Logger.warn("Invalid prefixPriority value: " + newValue);
                        return false;
                    }
                }
                case "suffixpriority" -> {
                    try {
                        group.setSuffixPriority(Integer.parseInt(newValue));
                    } catch (NumberFormatException e) {
                        Logger.warn("Invalid suffixPriority value: " + newValue);
                        return false;
                    }
                }
                default -> {
                    Logger.warn("Unknown meta key: " + key);
                    return false;
                }
            }
            hyperPerms.getGroupManager().saveGroup(group);
            return true;
        } else if ("user".equalsIgnoreCase(change.getTargetType())) {
            User user = getUser(change.getTarget());
            if (user == null) return false;

            switch (key.toLowerCase()) {
                case "prefix" -> user.setCustomPrefix(newValue);
                case "suffix" -> user.setCustomSuffix(newValue);
                case "primarygroup" -> user.setPrimaryGroup(newValue);
                default -> {
                    Logger.warn("Unknown meta key for user: " + key);
                    return false;
                }
            }
            hyperPerms.getUserManager().saveUser(user);
            return true;
        }
        return false;
    }

    /**
     * Applies a full user sync from the web editor.
     * This replaces ALL user groups (not just adds), allowing proper removal of groups.
     */
    private boolean applyUserSync(Change change) {
        User user = getUser(change.getTarget());
        if (user == null) {
            Logger.warn("User not found for sync: " + change.getTarget());
            return false;
        }

        Logger.info("[WebEditor] Syncing user " + user.getFriendlyName() + " (" + change.getTarget() + ")");

        // Get current groups before clearing
        Set<String> oldGroups = user.getInheritedGroups();
        Logger.info("  - Current groups: " + oldGroups);

        // Clear all existing group nodes (but keep permission nodes)
        for (String groupName : oldGroups) {
            user.removeGroup(groupName);
        }

        // Set primary group if provided
        String primaryGroup = change.getMetaNewValue();
        if (primaryGroup != null && !primaryGroup.isEmpty()) {
            user.setPrimaryGroup(primaryGroup);
            Logger.info("  - Set primary group: " + primaryGroup);
        }

        // Add all parent groups from the comma-separated string
        String parentsStr = change.getParent();
        if (parentsStr != null && !parentsStr.isEmpty()) {
            String[] groups = parentsStr.split(",");
            for (String group : groups) {
                String trimmed = group.trim();
                if (!trimmed.isEmpty()) {
                    user.addGroup(trimmed);
                    Logger.info("  - Added group: " + trimmed);
                }
            }
        }

        // Log final state
        Logger.info("  - Final groups: " + user.getInheritedGroups());

        hyperPerms.getUserManager().saveUser(user);
        return true;
    }

    private boolean applyWeightChanged(Change change) {
        Group group = hyperPerms.getGroupManager().getGroup(change.getTarget());
        if (group == null) return false;

        Integer newWeight = change.getNewWeight();
        if (newWeight == null) return false;

        group.setWeight(newWeight);
        hyperPerms.getGroupManager().saveGroup(group);
        return true;
    }

    private boolean applyTrackCreated(Change change) {
        Change.TrackData data = change.getTrack();
        if (data == null) return false;

        // Check if track already exists
        if (hyperPerms.getTrackManager().getTrack(data.getName()) != null) {
            Logger.warn("Track already exists: " + data.getName());
            return false;
        }

        Track track = hyperPerms.getTrackManager().createTrack(data.getName());
        if (track == null) return false;

        track.setGroups(data.getGroups());
        hyperPerms.getTrackManager().saveTrack(track);
        return true;
    }

    private boolean applyTrackDeleted(Change change) {
        String trackName = change.getTrackName();
        if (trackName == null) return false;

        Track track = hyperPerms.getTrackManager().getTrack(trackName);
        if (track == null) return false;

        hyperPerms.getTrackManager().deleteTrack(trackName);
        return true;
    }

    private boolean applyTrackModified(Change change) {
        String trackName = change.getTrackName();
        if (trackName == null) return false;

        Track track = hyperPerms.getTrackManager().getTrack(trackName);
        if (track == null) return false;

        List<String> newGroups = change.getNewGroups();
        if (newGroups == null) return false;

        track.setGroups(newGroups);
        hyperPerms.getTrackManager().saveTrack(track);
        return true;
    }

    private Node buildNode(String permission, Boolean value, Map<String, String> contexts, Long expiry) {
        // Normalize negation: -prefix format always uses value=true
        // The - prefix already encodes "deny", so value must be true to avoid double negation
        // (PermissionResolver strips - and flips value: -perm/true → perm/false = DENIED)
        boolean effectiveValue;
        if (permission.startsWith("-")) {
            effectiveValue = true;
        } else {
            effectiveValue = value != null ? value : true;
        }

        var builder = Node.builder(permission)
                .value(effectiveValue);

        if (contexts != null) {
            for (var entry : contexts.entrySet()) {
                builder.context(entry.getKey(), entry.getValue());
            }
        }

        if (expiry != null) {
            builder.expiry(Instant.ofEpochMilli(expiry));
        }

        return builder.build();
    }

    private User getUser(String identifier) {
        try {
            UUID uuid = UUID.fromString(identifier);
            User user = hyperPerms.getUserManager().getUser(uuid);
            if (user != null) return user;
            // Try to load from storage
            return hyperPerms.getUserManager().loadUser(uuid).join().orElse(null);
        } catch (IllegalArgumentException e) {
            // Not a UUID, try by name
            for (User user : hyperPerms.getUserManager().getLoadedUsers()) {
                if (identifier.equalsIgnoreCase(user.getUsername())) {
                    return user;
                }
            }
            return null;
        }
    }

    private String getTargetDescription(Change change) {
        if (change.getTarget() != null) {
            return change.getTargetType() + ":" + change.getTarget();
        }
        if (change.getGroupName() != null) {
            return "group:" + change.getGroupName();
        }
        if (change.getTrackName() != null) {
            return "track:" + change.getTrackName();
        }
        return "unknown";
    }

    private void logChange(Change change) {
        String desc = switch (change.getType()) {
            case PERMISSION_ADDED -> "Added permission " + change.getNode() + " to " + change.getTargetType() + " " + change.getTarget();
            case PERMISSION_REMOVED -> "Removed permission " + change.getNode() + " from " + change.getTargetType() + " " + change.getTarget();
            case PERMISSION_MODIFIED -> "Modified permission " + change.getNode() + " on " + change.getTargetType() + " " + change.getTarget();
            case GROUP_CREATED -> "Created group " + (change.getGroup() != null ? change.getGroup().getName() : "unknown");
            case GROUP_DELETED -> "Deleted group " + change.getGroupName();
            case PARENT_ADDED -> "Added parent " + change.getParent() + " to " + change.getTargetType() + " " + change.getTarget();
            case PARENT_REMOVED -> "Removed parent " + change.getParent() + " from " + change.getTargetType() + " " + change.getTarget();
            case META_CHANGED -> "Changed " + change.getKey() + " on " + change.getTargetType() + " " + change.getTarget();
            case WEIGHT_CHANGED -> "Changed weight to " + change.getNewWeight() + " on group " + change.getTarget();
            case TRACK_CREATED -> "Created track " + (change.getTrack() != null ? change.getTrack().getName() : "unknown");
            case TRACK_DELETED -> "Deleted track " + change.getTrackName();
            case TRACK_MODIFIED -> "Modified track " + change.getTrackName();
        };
        Logger.info("[WebEditor] " + desc);
    }
}
